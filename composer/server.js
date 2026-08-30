import http from 'node:http';
import { createReadStream, readFileSync, statSync, writeFileSync, renameSync } from 'node:fs';
import { dirname, extname, join, normalize, resolve } from 'node:path';
import { Readable } from 'node:stream';
import YAML from 'yaml';

const port = Number(process.env.PORT || 8081);
const upstream = (process.env.IMMICHFRAME_URL || 'http://immichframe:8080').replace(/\/$/, '');
const configPath = process.env.COMPOSER_CONFIG || '/config/frame.yml';
const publicRoot = resolve('/app/public');
const customCssPath = join(resolve(configPath, '..'), 'custom.css');
const blocksPath = process.env.COMPOSER_BLOCKS || '/data/blocks.json';
const states = new Map();
let latestState = null;
const controllerActions = [];
let configCache = { mtime: 0, value: null };

function loadBlocks() {
  try {
    const parsed = JSON.parse(readFileSync(blocksPath, 'utf8'));
    return {
      assets: Array.isArray(parsed.assets) ? parsed.assets : [],
      folders: Array.isArray(parsed.folders) ? parsed.folders : []
    };
  } catch (error) {
    if (error.code !== 'ENOENT') console.error('Could not read block list', error);
    return { assets: [], folders: [] };
  }
}

function saveBlocks(blocks) {
  const temporary = `${blocksPath}.tmp`;
  writeFileSync(temporary, `${JSON.stringify({ schema_version: 1, ...blocks }, null, 2)}\n`, 'utf8');
  renameSync(temporary, blocksPath);
}

function assetFolder(sourcePath) {
  const normalized = String(sourcePath || '').replace(/\\/g, '/').replace(/\/+$/, '');
  const boundary = normalized.lastIndexOf('/');
  return boundary > 0 ? normalized.slice(0, boundary) : '';
}

function addBlock(kind, value, label) {
  const blocks = loadBlocks();
  const key = kind === 'folder' ? 'folders' : 'assets';
  if (!blocks[key].some(entry => entry.value === value)) {
    blocks[key].push({ value, label: label || value, blocked_at: new Date().toISOString() });
    saveBlocks(blocks);
  }
  return blocks;
}

const mime = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp'
};

function json(res, status, body) {
  const data = JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(data),
    'cache-control': 'no-store'
  });
  res.end(data);
}

function loadConfig() {
  const modified = statSync(configPath).mtimeMs;
  if (!configCache.value || modified !== configCache.mtime) {
    const value = YAML.parse(readFileSync(configPath, 'utf8'));
    if (value?.schema_version !== 1) throw new Error('frame.yml must use schema_version: 1');
    if (!value?.profiles || Object.keys(value.profiles).length === 0) {
      throw new Error('frame.yml must define at least one profile');
    }
    const defaultProfile = value.default_profile || Object.keys(value.profiles)[0];
    if (!value.profiles[defaultProfile]) throw new Error(`Unknown default_profile: ${defaultProfile}`);
    configCache = { mtime: modified, value: { ...value, default_profile: defaultProfile } };
  }
  return configCache.value;
}

function serveFile(res, path, cache = true) {
  try {
    const info = statSync(path);
    if (!info.isFile()) throw new Error('not a file');
    res.writeHead(200, {
      'content-type': mime[extname(path).toLowerCase()] || 'application/octet-stream',
      'content-length': info.size,
      'cache-control': cache ? 'public, max-age=300' : 'no-store'
    });
    createReadStream(path).pipe(res);
  } catch {
    res.writeHead(404).end();
  }
}

async function proxy(res, target) {
  const response = await fetch(target, { headers: { accept: '*/*' }, signal: AbortSignal.timeout(30000) });
  const headers = {
    'content-type': response.headers.get('content-type') || 'application/octet-stream',
    'cache-control': response.headers.get('content-type')?.startsWith('image/')
      ? 'private, max-age=300'
      : 'no-store'
  };
  const length = response.headers.get('content-length');
  if (length) headers['content-length'] = length;
  res.writeHead(response.status, headers);
  if (response.body) Readable.fromWeb(response.body).pipe(res);
  else res.end();
}

async function readJson(req, limit = 65536) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > limit) throw new Error('request body too large');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

    if (req.method === 'GET' && url.pathname === '/health') {
      const config = loadConfig();
      const response = await fetch(`${upstream}/api/Config?clientIdentifier=composer-health`, {
        signal: AbortSignal.timeout(10000)
      });
      return json(res, response.ok ? 200 : 503, {
        status: response.ok ? 'healthy' : 'upstream-error',
        default_profile: config.default_profile,
        profiles: Object.keys(config.profiles),
        upstream_status: response.status
      });
    }

    if (req.method === 'GET' && url.pathname === '/api/config') {
      return json(res, 200, loadConfig());
    }

    if (req.method === 'GET' && url.pathname === '/api/assets') {
      const client = url.searchParams.get('client') || 'frame-composer';
      return await proxy(res, `${upstream}/api/Asset?clientIdentifier=${encodeURIComponent(client)}`);
    }

    const imageMatch = url.pathname.match(/^\/api\/image\/([0-9a-f-]+)$/i);
    if (req.method === 'GET' && imageMatch) {
      const client = url.searchParams.get('client') || 'frame-composer';
      const type = url.searchParams.get('type') || '0';
      const target = `${upstream}/api/Asset/${encodeURIComponent(imageMatch[1])}/Asset` +
        `?clientIdentifier=${encodeURIComponent(client)}&assetType=${encodeURIComponent(type)}`;
      return await proxy(res, target);
    }

    if (url.pathname === '/api/state') {
      const client = url.searchParams.get('client') || 'frame-composer';
      if (req.method === 'GET') return json(res, 200, states.get(client) || null);
      if (req.method === 'POST') {
        const state = await readJson(req);
        const published = { ...state, client, updated_at: new Date().toISOString() };
        states.set(client, published);
        latestState = published;
        return json(res, 200, { ok: true });
      }
    }

    if (req.method === 'GET' && url.pathname === '/api/controller/state') {
      return json(res, 200, latestState);
    }

    if (req.method === 'GET' && url.pathname === '/api/controller/blocks') {
      return json(res, 200, loadBlocks());
    }

    if (url.pathname === '/api/controller/actions') {
      if (req.method === 'GET') return json(res, 200, controllerActions.splice(0));
      if (req.method === 'POST') {
        const action = await readJson(req, 4096);
        const supported = ['previous', 'next', 'toggle_paused', 'pause', 'resume', 'show_map',
          'map_up', 'map_down', 'map_left', 'map_right', 'map_zoom_in', 'map_zoom_out', 'close_map',
          'block_image', 'block_folder'];
        if (!supported.includes(action.name)) return json(res, 400, { error: 'unsupported action' });
        const queued = { name: action.name, received_at: new Date().toISOString() };
        if (Number.isInteger(action.slot) && action.slot >= 0 && action.slot <= 2) queued.slot = action.slot;
        if (action.name === 'block_image' || action.name === 'block_folder') {
          const asset = latestState?.assets?.[queued.slot];
          if (!asset?.id) return json(res, 409, { error: 'selected photo is no longer current' });
          if (action.name === 'block_image') addBlock('asset', asset.id, asset.filename);
          else {
            const folder = assetFolder(asset.source_path);
            if (!folder) return json(res, 409, { error: 'source folder is unavailable' });
            addBlock('folder', folder, folder.split('/').pop());
          }
        }
        controllerActions.push(queued);
        return json(res, 200, { ok: true });
      }
    }

    if (req.method === 'GET' && url.pathname === '/custom.css') {
      return serveFile(res, customCssPath, false);
    }

    if (req.method === 'GET' && (url.pathname === '/' || url.pathname.startsWith('/frame/'))) {
      return serveFile(res, join(publicRoot, 'index.html'), false);
    }

    if (req.method === 'GET') {
      const relative = normalize(decodeURIComponent(url.pathname)).replace(/^[/\\]+/, '');
      const path = resolve(publicRoot, relative);
      if (path.startsWith(publicRoot + '/')) return serveFile(res, path);
    }

    res.writeHead(404).end();
  } catch (error) {
    console.error(error);
    json(res, 500, { error: error.message || 'Internal server error' });
  }
});

server.listen(port, '0.0.0.0', () => {
  console.log(`Frame Composer listening on :${port}; ImmichFrame upstream ${upstream}`);
});

