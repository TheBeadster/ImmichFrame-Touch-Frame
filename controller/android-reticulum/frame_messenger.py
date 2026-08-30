#!/usr/bin/env python3
"""X88-local Reticulum/LXMF bridge for the ImmichFrame controller helper."""

import json
import multiprocessing
import os
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Python 3.14 changed POSIX's default to forkserver. LXMF's Android stamp
# worker intentionally uses a nested target and therefore requires fork.
multiprocessing.set_start_method("fork", force=True)

import LXMF
import RNS


HOME = os.path.expanduser("~")
STATE_DIR = os.environ.get("FRAME_MESSENGER_STATE", os.path.join(HOME, ".frame-messenger"))
CONFIG_PATH = os.environ.get("FRAME_MESSENGER_CONFIG", os.path.join(STATE_DIR, "config.json"))
RNS_CONFIG = os.environ.get("FRAME_MESSENGER_RNS", os.path.join(STATE_DIR, "reticulum"))
IDENTITY_PATH = os.path.join(STATE_DIR, "sender.identity")
RECIPIENT_PATH = os.path.join(STATE_DIR, "recipient.json")
MESSAGE_PATH = os.path.join(STATE_DIR, "messages.json")


def atomic_json(path, value):
    temporary = path + ".tmp"
    with open(temporary, "w", encoding="utf-8") as output:
        json.dump(value, output, indent=2, ensure_ascii=False)
        output.write("\n")
    os.replace(temporary, path)


class RecipientAnnounceHandler:
    aspect_filter = "lxmf.delivery"

    def __init__(self, service):
        self.service = service

    def received_announce(self, destination_hash, announced_identity, app_data):
        try:
            display_name = LXMF.display_name_from_app_data(app_data)
            self.service.record_announce(destination_hash, display_name)
        except Exception as error:
            RNS.log(f"Could not process LXMF announce: {error}", RNS.LOG_ERROR)


class MessengerService:
    def __init__(self):
        os.makedirs(STATE_DIR, exist_ok=True)
        with open(CONFIG_PATH, "r", encoding="utf-8") as source:
            self.config = json.load(source)
        self.lock = threading.RLock()
        self.messages = self._load_json(MESSAGE_PATH, {})
        self.recipient = self._load_json(RECIPIENT_PATH, {})

        self.reticulum = RNS.Reticulum(configdir=RNS_CONFIG)
        if os.path.exists(IDENTITY_PATH):
            self.identity = RNS.Identity.from_file(IDENTITY_PATH)
        else:
            self.identity = RNS.Identity()
            self.identity.to_file(IDENTITY_PATH)

        self.router = LXMF.LXMRouter(storagepath=os.path.join(STATE_DIR, "lxmf"))
        self.source = self.router.register_delivery_identity(
            self.identity, display_name=self.config.get("sender_display_name", "Mum Frame")
        )
        RNS.Transport.register_announce_handler(RecipientAnnounceHandler(self))
        self.source.announce()

    @staticmethod
    def _load_json(path, default):
        try:
            with open(path, "r", encoding="utf-8") as source:
                return json.load(source)
        except (OSError, ValueError):
            return default

    def record_announce(self, destination_hash, display_name):
        if display_name != self.config.get("recipient_display_name", "Admin"):
            return
        with self.lock:
            self.recipient = {
                "display_name": display_name,
                "destination_hash": RNS.hexrep(destination_hash, delimit=False),
                "last_announce": int(time.time()),
            }
            atomic_json(RECIPIENT_PATH, self.recipient)
        RNS.log(f"Captured recipient announce for {display_name}", RNS.LOG_NOTICE)

    def health(self):
        with self.lock:
            return {
                "ok": True,
                "sender": self.config.get("sender_display_name", "Mum Frame"),
                "recipient": self.recipient.get("display_name"),
                "recipient_known": bool(self.recipient.get("destination_hash")),
                "hub": self.config.get("hub", "192.168.1.20:4242"),
                "messages": len(self.messages),
            }

    def _render(self, asset):
        lines = [self.config.get("heading", "Photo on Mum's frame")]
        filename = asset.get("filename") or "Photo"
        lines.append(filename)
        if asset.get("taken_at"):
            lines.append("Taken: " + str(asset["taken_at"]))
        if asset.get("people"):
            lines.append("People: " + str(asset["people"]))
        if asset.get("location"):
            lines.append("Location: " + str(asset["location"]))
        if asset.get("asset_id"):
            lines.append("Immich: " + self.config.get("immich_photo_url", "http://192.168.1.50:2283/photos/{asset_id}").format(asset_id=asset["asset_id"]))
        return "\n".join(lines)

    def send(self, asset):
        with self.lock:
            destination_hex = self.recipient.get("destination_hash")
        if not destination_hex:
            raise RuntimeError("Recipient announce has not been captured yet")
        destination_hash = bytes.fromhex(destination_hex)
        identity = RNS.Identity.recall(destination_hash)
        if identity is None:
            raise RuntimeError("Recipient identity is not present in the Reticulum cache")

        destination = RNS.Destination(identity, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
        message_id = uuid.uuid4().hex
        record = {"id": message_id, "status": "queued", "created_at": int(time.time()), "filename": asset.get("filename", "Photo")}
        with self.lock:
            self.messages[message_id] = record
            atomic_json(MESSAGE_PATH, self.messages)

        message = LXMF.LXMessage(destination, self.source, self._render(asset), title="ImmichFrame photo")
        message.register_delivery_callback(lambda delivered: self._finish(message_id, "sent"))
        message.register_failed_callback(lambda failed: self._finish(message_id, "failed", "LXMF delivery failed"))
        self.router.handle_outbound(message)
        return record.copy()

    def _finish(self, message_id, status, error=None):
        with self.lock:
            if message_id not in self.messages:
                return
            self.messages[message_id]["status"] = status
            self.messages[message_id]["updated_at"] = int(time.time())
            if error:
                self.messages[message_id]["error"] = error
            atomic_json(MESSAGE_PATH, self.messages)


SERVICE = None


class Handler(BaseHTTPRequestHandler):
    server_version = "FrameMessenger/1.0"

    def log_message(self, format_string, *args):
        RNS.log(format_string % args, RNS.LOG_VERBOSE)

    def reply(self, status, value):
        body = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self.reply(200, SERVICE.health())
        elif self.path == "/recipient":
            self.reply(200, SERVICE.recipient)
        elif self.path.startswith("/messages/"):
            message_id = self.path.rsplit("/", 1)[-1]
            message = SERVICE.messages.get(message_id)
            self.reply(200 if message else 404, message or {"error": "message not found"})
        else:
            self.reply(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/send":
            self.reply(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 2 or length > 16384:
                raise ValueError("invalid request size")
            asset = json.loads(self.rfile.read(length).decode("utf-8"))
            self.reply(202, SERVICE.send(asset))
        except Exception as error:
            self.reply(503, {"status": "failed", "error": str(error)[:240]})


def main():
    global SERVICE
    SERVICE = MessengerService()
    address = SERVICE.config.get("listen", "127.0.0.1")
    port = int(SERVICE.config.get("port", 8090))
    RNS.log(f"Frame Messenger listening at {address}:{port}", RNS.LOG_NOTICE)
    ThreadingHTTPServer((address, port), Handler).serve_forever()


if __name__ == "__main__":
    main()

