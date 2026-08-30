const frame = document.querySelector('#frame');
const status = document.querySelector('#status');
const controls = document.querySelector('#controls');
const pauseButton = document.querySelector('#pause');
const mapOverlay = document.querySelector('#map-overlay');
const mapTiles = document.querySelector('#map-tiles');
const mapCaption = document.querySelector('#map-caption');

function createClientId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  if (globalThis.crypto?.getRandomValues) {
    const bytes = new Uint8Array(16);
    globalThis.crypto.getRandomValues(bytes);
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const value = [...bytes].map(byte => byte.toString(16).padStart(2, '0')).join('');
    return `${value.slice(0, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}-${value.slice(16, 20)}-${value.slice(20)}`;
  }
  return `frame-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

const clientId = localStorage.getItem('frame-composer-client') || createClientId();
localStorage.setItem('frame-composer-client', clientId);

let config;
let profileName;
let profile;
let slots = [];
let pool = [];
let current = [];
let previousSets = [];
let paused = false;
let timer;
let controlsTimer;
let pointerStart;
let blocked = { assets: [], folders: [] };
const mapState = { active: false, latitude: 0, longitude: 0, zoom: 14 };

function validCoordinate(value) {
  return value !== null && value !== '' && Number.isFinite(Number(value));
}

function clampLatitude(value) {
  return Math.max(-85.05112878, Math.min(85.05112878, value));
}

function worldPoint(latitude, longitude, zoom) {
  const size = 256 * (2 ** zoom);
  const lat = clampLatitude(latitude) * Math.PI / 180;
  return {
    x: (longitude + 180) / 360 * size,
    y: (1 - Math.log(Math.tan(lat) + 1 / Math.cos(lat)) / Math.PI) / 2 * size
  };
}

function coordinateAt(point, zoom) {
  const size = 256 * (2 ** zoom);
  const longitude = point.x / size * 360 - 180;
  const mercator = Math.PI * (1 - 2 * point.y / size);
  const latitude = Math.atan(Math.sinh(mercator)) * 180 / Math.PI;
  return { latitude: clampLatitude(latitude), longitude: ((longitude + 540) % 360) - 180 };
}

function renderMap() {
  if (!mapState.active) return;
  const width = mapOverlay.clientWidth;
  const height = mapOverlay.clientHeight;
  if (!width || !height) return;
  const zoom = mapState.zoom;
  const count = 2 ** zoom;
  const centre = worldPoint(mapState.latitude, mapState.longitude, zoom);
  const left = centre.x - width / 2;
  const top = centre.y - height / 2;
  const firstX = Math.floor(left / 256);
  const lastX = Math.floor((left + width) / 256);
  const firstY = Math.max(0, Math.floor(top / 256));
  const lastY = Math.min(count - 1, Math.floor((top + height) / 256));
  const fragment = document.createDocumentFragment();
  for (let tileY = firstY; tileY <= lastY; tileY++) {
    for (let tileX = firstX; tileX <= lastX; tileX++) {
      const wrappedX = ((tileX % count) + count) % count;
      const image = document.createElement('img');
      image.alt = '';
      image.decoding = 'async';
      image.src = `https://tile.openstreetmap.org/${zoom}/${wrappedX}/${tileY}.png`;
      image.style.left = `${Math.round(tileX * 256 - left)}px`;
      image.style.top = `${Math.round(tileY * 256 - top)}px`;
      fragment.append(image);
    }
  }
  mapTiles.replaceChildren(fragment);
}

function openMap(slot) {
  const asset = current[Number(slot)];
  if (!asset || !validCoordinate(asset.exifInfo?.latitude) || !validCoordinate(asset.exifInfo?.longitude)) return;
  mapState.active = true;
  mapState.latitude = Number(asset.exifInfo.latitude);
  mapState.longitude = Number(asset.exifInfo.longitude);
  mapState.zoom = 14;
  const exif = asset.exifInfo || {};
  mapCaption.textContent = [exif.city, exif.state, exif.country].filter(Boolean).join(', ') || 'Photo location';
  mapOverlay.classList.remove('hidden');
  controls.classList.add('hidden');
  requestAnimationFrame(renderMap);
}

function panMap(dx, dy) {
  if (!mapState.active) return;
  const point = worldPoint(mapState.latitude, mapState.longitude, mapState.zoom);
  const next = coordinateAt({ x: point.x + dx, y: point.y + dy }, mapState.zoom);
  mapState.latitude = next.latitude;
  mapState.longitude = next.longitude;
  renderMap();
}

function zoomMap(delta) {
  if (!mapState.active) return;
  mapState.zoom = Math.max(3, Math.min(19, mapState.zoom + delta));
  renderMap();
}

function closeMap() {
  mapState.active = false;
  mapOverlay.classList.add('hidden');
  mapTiles.replaceChildren();
}

function requestedProfile() {
  const match = location.pathname.match(/^\/frame\/([^/]+)/);
  return match ? decodeURIComponent(match[1]) : null;
}

function shuffle(values) {
  const copy = [...values];
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [copy[i], copy[j]] = [copy[j], copy[i]];
  }
  return copy;
}

function orientation(asset) {
  const ratio = Number(asset.width || 1) / Number(asset.height || 1);
  if (ratio > 1.12) return 'landscape';
  if (ratio < 0.89) return 'portrait';
  return 'square';
}

function globPattern(pattern) {
  const escaped = String(pattern)
    .replace(/\\/g, '/')
    .replace(/[.+^${}()|[\]\\]/g, '\\$&')
    .replace(/\*\*/g, '\u0000')
    .replace(/\*/g, '[^/]*')
    .replace(/\?/g, '[^/]')
    .replace(/\u0000/g, '.*');
  return new RegExp(`^${escaped}$`, 'i');
}

function assetTagValues(asset) {
  const tags = asset.tags ?? asset.additionalProperties?.tags ?? [];
  if (!Array.isArray(tags)) return [];
  return tags.flatMap(tag => {
    if (typeof tag === 'string') return [tag];
    if (!tag || typeof tag !== 'object') return [];
    return [tag.value, tag.path, tag.name].filter(value => typeof value === 'string');
  });
}

function isExcluded(asset) {
  if (blocked.assets.some(entry => entry.value === asset.id)) return true;
  const assetPath = String(asset.originalPath || '').replace(/\\/g, '/');
  if (blocked.folders.some(entry => assetPath === entry.value || assetPath.startsWith(`${entry.value}/`))) return true;
  const exclude = profile.exclude || {};
  const ignoredTags = new Set(exclude.tags || []);
  if (assetTagValues(asset).some(tag => ignoredTags.has(tag))) return true;

  const path = String(asset.originalPath || '').replace(/\\/g, '/');
  return (exclude.folders || []).some(rule => {
    const normalized = String(rule).replace(/\\/g, '/').replace(/\/$/, '');
    if (!normalized) return false;
    if (normalized.includes('*') || normalized.includes('?')) return globPattern(normalized).test(path);
    return path === normalized || path.startsWith(`${normalized}/`) || path.includes(`/${normalized}/`);
  });
}

function requiredUpscale(asset, slot) {
  const bounds = slot.element.getBoundingClientRect();
  const source = slot.definition.source || {};
  const resolutionScale = Number(source.resolution_scale ?? profile.selection?.resolution_scale ?? 1);
  const targetWidth = Math.max(1, bounds.width * resolutionScale);
  const targetHeight = Math.max(1, bounds.height * resolutionScale);
  const widthScale = targetWidth / Math.max(1, Number(asset.width || 0));
  const heightScale = targetHeight / Math.max(1, Number(asset.height || 0));
  const fit = slot.definition.fit || 'cover';
  return fit === 'contain' || fit === 'scale-down'
    ? Math.min(widthScale, heightScale)
    : Math.max(widthScale, heightScale);
}

function eligible(asset, slot) {
  if (isExcluded(asset)) return false;
  if (asset.type !== 0) return false;
  const source = slot.definition.source || {};
  const pixels = Number(asset.width || 0) * Number(asset.height || 0);
  const shortEdge = Math.min(Number(asset.width || 0), Number(asset.height || 0));
  const minimumPixels = Number(source.minimum_pixels ?? profile.selection?.minimum_pixels ?? 0);
  const maximumPixels = Number(source.maximum_pixels ?? profile.selection?.maximum_pixels ?? Infinity);
  const minimumShortEdge = Number(source.minimum_short_edge ?? profile.selection?.minimum_short_edge ?? 0);
  const maxUpscale = Number(source.max_upscale ?? profile.selection?.max_upscale ?? Infinity);
  if (pixels < minimumPixels || pixels > maximumPixels) return false;
  if (shortEdge < minimumShortEdge) return false;
  if (requiredUpscale(asset, slot) > maxUpscale) return false;
  const wanted = source.orientation || 'any';
  return wanted === 'any' || wanted === orientation(asset);
}

async function refreshPool() {
  const response = await fetch(`/api/assets?client=${encodeURIComponent(clientId)}`, { cache: 'no-store' });
  if (!response.ok) throw new Error(`Asset request failed: ${response.status}`);
  const incoming = shuffle(await response.json());
  const known = new Set([...pool, ...current].map(asset => asset.id));
  for (const asset of incoming) {
    if (!known.has(asset.id)) {
      pool.push(asset);
      known.add(asset.id);
    }
  }
  pool = shuffle(pool).slice(0, 250);
}

async function chooseSet() {
  if (pool.length < slots.length * 2) await refreshPool();
  const selected = [];
  for (const slot of slots) {
    let index = pool.findIndex(asset => !selected.some(item => item.id === asset.id) && eligible(asset, slot));
    for (let attempt = 0; index < 0 && attempt < 4; attempt++) {
      await refreshPool();
      index = pool.findIndex(asset => !selected.some(item => item.id === asset.id) && eligible(asset, slot));
    }
    if (index < 0) throw new Error(`No photograph meets the hard resolution rules for slot ${slot.definition.id}`);
    selected.push(pool.splice(index, 1)[0]);
  }
  return selected;
}

function imageUrl(asset) {
  return `/api/image/${encodeURIComponent(asset.id)}?client=${encodeURIComponent(clientId)}&type=${encodeURIComponent(asset.type)}`;
}

function preloadLayer(slot, asset) {
  return new Promise((resolve, reject) => {
    const next = slot.layers[slot.active === 0 ? 1 : 0];
    next.onload = () => resolve(next);
    next.onerror = () => reject(new Error(`Could not load ${asset.id}`));
    next.src = imageUrl(asset);
  });
}

async function showSet(assets, remember = true) {
  const loaded = await Promise.all(slots.map((slot, index) => preloadLayer(slot, assets[index])));
  if (remember && current.length) {
    previousSets.push(current);
    if (previousSets.length > 30) previousSets.shift();
  }
  slots.forEach((slot, index) => {
    slot.layers.forEach(layer => layer.classList.remove('active'));
    loaded[index].classList.add('active');
    slot.active = slot.layers.indexOf(loaded[index]);
  });
  current = assets;
  status.classList.add('ready');
  publishState();
}

async function nextSet() {
  try {
    status.textContent = 'Loading photographs…';
    await showSet(await chooseSet());
    schedule();
  } catch (error) {
    console.error(error);
    status.textContent = error.message;
    status.classList.remove('ready');
    clearTimeout(timer);
    timer = setTimeout(nextSet, 10000);
  }
}

async function previousSet() {
  const previous = previousSets.pop();
  if (previous) await showSet(previous, false);
  schedule();
}

function schedule() {
  clearTimeout(timer);
  if (!paused) timer = setTimeout(nextSet, Number(profile.slideshow?.interval_seconds || 20) * 1000);
}

function togglePause() {
  paused = !paused;
  pauseButton.textContent = paused ? '▶' : 'Ⅱ';
  schedule();
  publishState();
}

function setPaused(value) {
  if (paused !== value) togglePause();
}

function showControls() {
  if (mapState.active) return;
  controls.classList.remove('hidden');
  clearTimeout(controlsTimer);
  controlsTimer = setTimeout(() => controls.classList.add('hidden'), Number(profile.controls?.hide_after_seconds || 7) * 1000);
}

function applyProfile(name) {
  profileName = config.profiles[name] ? name : config.default_profile;
  profile = config.profiles[profileName];
  document.title = profile.title || 'Frame Composer';
  frame.replaceChildren();
  const canvas = profile.canvas || {};
  frame.style.background = canvas.background || '#0b0b0b';
  frame.style.padding = canvas.padding || '0';
  frame.style.gap = canvas.gap || '0';
  frame.style.gridTemplateColumns = canvas.columns || '1fr';
  frame.style.gridTemplateRows = canvas.rows || 'auto';
  frame.style.alignContent = canvas.align_content || 'center';

  slots = (profile.slots || []).map(definition => {
    const element = document.createElement('section');
    element.className = 'photo-slot';
    element.id = `slot-${definition.id}`;
    element.style.gridColumn = definition.grid_column || 'auto';
    element.style.gridRow = definition.grid_row || 'auto';
    element.style.aspectRatio = definition.aspect_ratio || 'auto';
    element.style.borderRadius = definition.border_radius || canvas.border_radius || '0';
    const layers = [document.createElement('img'), document.createElement('img')];
    for (const image of layers) {
      image.alt = '';
      image.style.objectFit = definition.fit || 'cover';
      image.style.objectPosition = definition.position || 'center';
      image.style.transitionDuration = `${Number(profile.slideshow?.transition_seconds || 1.5)}s`;
      element.append(image);
    }
    frame.append(element);
    return { definition, element, layers, active: 0 };
  });
  if (slots.length === 0) throw new Error(`Profile ${profileName} has no slots`);
  previousSets = [];
  current = [];
  pool = [];
  window.history.replaceState({}, '', `/frame/${encodeURIComponent(profileName)}`);
}

function nextProfile() {
  const names = Object.keys(config.profiles);
  applyProfile(names[(names.indexOf(profileName) + 1) % names.length]);
  nextSet();
}

async function publishState() {
  try {
    await fetch(`/api/state?client=${encodeURIComponent(clientId)}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        profile: profileName,
        paused,
        assets: current.map(asset => {
          const exif = asset.exifInfo || {};
          const taken = exif.dateTimeOriginal || asset.localDateTime || asset.fileCreatedAt || asset.createdAt || '';
          const people = (asset.people || []).map(person => person?.name).filter(Boolean).join(', ');
          const hasCoordinates = validCoordinate(exif.latitude) && validCoordinate(exif.longitude);
          const locationName = [exif.city, exif.state, exif.country].filter(Boolean).join(', ');
          const location = hasCoordinates ? (locationName || 'Location recorded') : '';
          return {
            id: asset.id,
            width: asset.width,
            height: asset.height,
            filename: asset.originalFileName || '',
            year: String(taken).slice(0, 4),
            people,
            location,
            latitude: hasCoordinates ? Number(exif.latitude) : null,
            longitude: hasCoordinates ? Number(exif.longitude) : null,
            source_path: asset.originalPath || '',
            taken_at: taken,
            image_url: `/api/image/${encodeURIComponent(asset.id)}?client=${encodeURIComponent(clientId)}&type=${encodeURIComponent(asset.type)}`
          };
        })
      })
    });
  } catch (error) {
    console.warn('Could not publish frame state', error);
  }
}

async function requestWakeLock() {
  try { await navigator.wakeLock?.request('screen'); } catch (error) { console.warn('Wake lock unavailable', error); }
}

document.querySelector('#previous').addEventListener('click', event => { event.stopPropagation(); previousSet(); showControls(); });
document.querySelector('#pause').addEventListener('click', event => { event.stopPropagation(); togglePause(); showControls(); });
document.querySelector('#next').addEventListener('click', event => { event.stopPropagation(); nextSet(); showControls(); });
document.querySelector('#layout').addEventListener('click', event => { event.stopPropagation(); nextProfile(); showControls(); });
document.addEventListener('click', showControls);
document.addEventListener('pointerdown', event => { pointerStart = { x: event.clientX, y: event.clientY }; });
document.addEventListener('pointerup', event => {
  if (mapState.active) { pointerStart = null; return; }
  if (!pointerStart) return;
  const dx = event.clientX - pointerStart.x;
  const dy = event.clientY - pointerStart.y;
  pointerStart = null;
  if (Math.abs(dx) > 80 && Math.abs(dx) > Math.abs(dy) * 1.4) dx < 0 ? nextSet() : previousSet();
});
document.addEventListener('keydown', event => {
  if (mapState.active) {
    if (event.key === 'ArrowLeft') panMap(-220, 0);
    else if (event.key === 'ArrowRight') panMap(220, 0);
    else if (event.key === 'ArrowUp') panMap(0, -220);
    else if (event.key === 'ArrowDown') panMap(0, 220);
    else if (event.key === '+' || event.key === '=') zoomMap(1);
    else if (event.key === '-') zoomMap(-1);
    else if (event.key === 'Escape' || event.key === 'Backspace') closeMap();
    return;
  }
  if (event.key === 'ArrowLeft') previousSet();
  else if (event.key === 'ArrowRight') nextSet();
  else if (event.key === 'Enter' || event.key === ' ') togglePause();
  else if (event.key.toLowerCase() === 'l') nextProfile();
  showControls();
});
document.addEventListener('visibilitychange', () => { if (!document.hidden) requestWakeLock(); });
window.addEventListener('resize', () => { if (mapState.active) renderMap(); });

async function pollControllerActions() {
  try {
    const response = await fetch('/api/controller/actions', { cache: 'no-store' });
    if (!response.ok) return;
    for (const action of await response.json()) {
      if (action.name === 'previous') await previousSet();
      else if (action.name === 'next') await nextSet();
      else if (action.name === 'toggle_paused') togglePause();
      else if (action.name === 'pause') setPaused(true);
      else if (action.name === 'resume') setPaused(false);
      else if (action.name === 'show_map') openMap(action.slot);
      else if (action.name === 'map_up') panMap(0, -220);
      else if (action.name === 'map_down') panMap(0, 220);
      else if (action.name === 'map_left') panMap(-220, 0);
      else if (action.name === 'map_right') panMap(220, 0);
      else if (action.name === 'map_zoom_in') zoomMap(1);
      else if (action.name === 'map_zoom_out') zoomMap(-1);
      else if (action.name === 'close_map') closeMap();
      else if (action.name === 'block_image' || action.name === 'block_folder') {
        blocked = await (await fetch('/api/controller/blocks', { cache: 'no-store' })).json();
        pool = pool.filter(asset => !isExcluded(asset));
        previousSets = [];
        await showSet(await chooseSet(), false);
        schedule();
      }
    }
  } catch (error) {
    console.warn('Could not poll controller actions', error);
  }
}
setInterval(pollControllerActions, 100);

try {
  config = await (await fetch('/api/config', { cache: 'no-store' })).json();
  blocked = await (await fetch('/api/controller/blocks', { cache: 'no-store' })).json();
  applyProfile(requestedProfile() || config.default_profile);
  requestWakeLock();
  await nextSet();
} catch (error) {
  console.error(error);
  status.textContent = error.message || 'Frame Composer failed to start';
}

