#!/usr/bin/env python3
"""
zephyr_link.py — connector for Zephyr Browser's AI Assistant.

Zephyr asks the questions; YOU (the agent) answer them with your own brain.
No API key is involved anywhere in this script.

Standard library only: no pip install, works in any sandbox with network access.

USAGE
  export ZEPHYR_CODE=ZEPH-XXXX-XXXX-XXXX     # the code Zephyr showed you

  python3 zephyr_link.py hello               # announce yourself, confirm the link
  python3 zephyr_link.py listen              # block until a question arrives, print it, exit
  python3 zephyr_link.py reply "<answer>"    # send your answer back
  python3 zephyr_link.py loop                # hello + listen forever, one question per line

TYPICAL AGENT LOOP
  1. run `hello`  → Zephyr lights up "agent online"
  2. run `listen` → prints JSON: {"id": "...", "question": "...", "history": [...]}
  3. think
  4. run `reply --id <id> "your answer"`
  5. back to 2

Transport: a public ntfy.sh topic whose name is derived from the pairing code.
Payloads are encrypted with a key derived from the same code, so the broker
relays ciphertext only.
"""

import base64
import hashlib
import hmac
import json
import os
import struct
import sys
import time
import urllib.request

PROTO = 1
CHUNK = 2400
SERVER = os.environ.get("ZEPHYR_SERVER", "https://ntfy.sh").rstrip("/")
STATE_FILE = os.environ.get("ZEPHYR_STATE", ".zephyr_link_state.json")


# ---------------------------------------------------------------- crypto

def _normalize(code):
    return "".join(ch for ch in code.upper() if ch.isalnum())


def derive_key(code):
    norm = _normalize(code)
    salt = hashlib.sha256(("zephyr-salt|" + norm).encode()).digest()[:16]
    return hashlib.pbkdf2_hmac("sha256", norm.encode(), salt, 60000, 32)


def topic_base(code):
    h = hashlib.sha256(("zephyr-topic|" + _normalize(code)).encode()).hexdigest()
    return "zephyr" + h[:20]


def _hmac(key, data):
    return hmac.new(key, data, hashlib.sha256).digest()


def _keystream_xor(enc_key, nonce, data):
    out = bytearray(len(data))
    off = 0
    block = 0
    while off < len(data):
        ks = _hmac(enc_key, nonce + struct.pack(">I", block))
        for b in ks:
            if off >= len(data):
                break
            out[off] = data[off] ^ b
            off += 1
        block += 1
    return bytes(out)


def encrypt(key, plaintext):
    nonce = os.urandom(12)
    ct = _keystream_xor(_hmac(key, b"zephyr-enc"), nonce, plaintext)
    tag = _hmac(_hmac(key, b"zephyr-mac"), nonce + ct)[:16]
    return base64.b64encode(nonce + ct + tag).decode()


def decrypt(key, wire):
    raw = base64.b64decode(wire)
    if len(raw) < 28:
        return None
    nonce, ct, tag = raw[:12], raw[12:-16], raw[-16:]
    expect = _hmac(_hmac(key, b"zephyr-mac"), nonce + ct)[:16]
    if not hmac.compare_digest(tag, expect):
        return None
    return _keystream_xor(_hmac(key, b"zephyr-enc"), nonce, ct)


# ---------------------------------------------------------------- transport

def publish(topic, body):
    req = urllib.request.Request(
        SERVER + "/" + topic,
        data=body.encode(),
        headers={"Content-Type": "text/plain; charset=utf-8", "Title": "zephyr-agent"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=20) as r:
        return 200 <= r.status < 300


def send(code, obj):
    """Encrypt, chunk and publish one envelope to Zephyr."""
    key = derive_key(code)
    wire = encrypt(key, json.dumps(obj, ensure_ascii=False).encode())
    mid = base64.b32encode(os.urandom(5)).decode().strip("=").lower()
    total = (len(wire) + CHUNK - 1) // CHUNK
    for i in range(total):
        frame = {
            "v": PROTO, "id": mid, "i": i, "n": total,
            "d": wire[i * CHUNK:(i + 1) * CHUNK],
        }
        publish(topic_base(code) + "d", json.dumps(frame))
    return True


def stream(code, on_message, timeout=None):
    """Read the up-topic until on_message returns True (or the timeout hits)."""
    key = derive_key(code)
    url = SERVER + "/" + topic_base(code) + "u/json"
    started = time.time()
    partials = {}
    while True:
        try:
            with urllib.request.urlopen(url, timeout=65) as r:
                for raw in r:
                    if timeout and time.time() - started > timeout:
                        return None
                    line = raw.decode("utf-8", "replace").strip()
                    if not line:
                        continue
                    try:
                        env = json.loads(line)
                    except Exception:
                        continue
                    if env.get("event") != "message":
                        continue
                    try:
                        frame = json.loads(env.get("message", ""))
                    except Exception:
                        continue
                    mid, idx, total = frame.get("id"), frame.get("i", 0), frame.get("n", 1)
                    if not mid or "d" not in frame:
                        continue
                    slots = partials.setdefault(mid, [None] * total)
                    if idx >= len(slots):
                        continue
                    slots[idx] = frame["d"]
                    if any(s is None for s in slots):
                        continue
                    partials.pop(mid, None)
                    plain = decrypt(key, "".join(slots))
                    if not plain:
                        continue
                    try:
                        msg = json.loads(plain.decode())
                    except Exception:
                        continue
                    result = on_message(msg)
                    if result is not None:
                        return result
        except KeyboardInterrupt:
            raise
        except Exception:
            if timeout and time.time() - started > timeout:
                return None
            time.sleep(2)


# ---------------------------------------------------------------- state

def _load_state():
    try:
        with open(STATE_FILE) as f:
            return json.load(f)
    except Exception:
        return {}


def _save_state(s):
    try:
        with open(STATE_FILE, "w") as f:
            json.dump(s, f)
    except Exception:
        pass


# ---------------------------------------------------------------- commands

def cmd_hello(code, name):
    send(code, {"type": "ready", "v": PROTO, "agent": name})
    print("linked: Zephyr should now show the agent as online")


def cmd_listen(code, name, timeout):
    def handle(msg):
        kind = msg.get("type")
        if kind == "hello":
            send(code, {"type": "ready", "v": PROTO, "agent": name})
            return None
        if kind == "ping":
            send(code, {"type": "pong", "v": PROTO, "agent": name})
            return None
        if kind == "chat":
            messages = msg.get("messages", [])
            question = ""
            for m in reversed(messages):
                if m.get("role") == "user":
                    question = m.get("content", "")
                    break
            state = _load_state()
            state["last_id"] = msg.get("id")
            _save_state(state)
            return {
                "id": msg.get("id"),
                "question": question,
                "system": msg.get("system", ""),
                "history": messages,
            }
        return None

    got = stream(code, handle, timeout=timeout)
    if got is None:
        print(json.dumps({"status": "timeout"}, ensure_ascii=False))
        return 2
    print(json.dumps(got, ensure_ascii=False))
    return 0


def cmd_reply(code, name, text, req_id):
    if not req_id:
        req_id = _load_state().get("last_id")
    if not req_id:
        print("no request id — run `listen` first, or pass --id", file=sys.stderr)
        return 1
    send(code, {"type": "reply", "v": PROTO, "id": req_id, "text": text, "agent": name})
    print("sent")
    return 0


def cmd_loop(code, name):
    """Announce, then print every question as it arrives (one JSON per line)."""
    cmd_hello(code, name)
    print("waiting for questions — answer each with: "
          "python3 zephyr_link.py reply --id <id> \"...\"", file=sys.stderr)

    def handle(msg):
        kind = msg.get("type")
        if kind in ("hello", "ping"):
            send(code, {"type": "pong" if kind == "ping" else "ready",
                        "v": PROTO, "agent": name})
            return None
        if kind == "chat":
            messages = msg.get("messages", [])
            question = ""
            for m in reversed(messages):
                if m.get("role") == "user":
                    question = m.get("content", "")
                    break
            state = _load_state()
            state["last_id"] = msg.get("id")
            _save_state(state)
            print(json.dumps(
                {"id": msg.get("id"), "question": question, "history": messages},
                ensure_ascii=False), flush=True)
        return None

    stream(code, handle, timeout=None)


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 1

    code = os.environ.get("ZEPHYR_CODE", "")
    name = os.environ.get("ZEPHYR_AGENT", "Agent")
    req_id = None
    timeout = 900

    rest = []
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--code" and i + 1 < len(args):
            code = args[i + 1]; i += 2
        elif a == "--id" and i + 1 < len(args):
            req_id = args[i + 1]; i += 2
        elif a == "--name" and i + 1 < len(args):
            name = args[i + 1]; i += 2
        elif a == "--timeout" and i + 1 < len(args):
            timeout = int(args[i + 1]); i += 2
        else:
            rest.append(a); i += 1

    if not code:
        print("set ZEPHYR_CODE (or pass --code ZEPH-XXXX-XXXX-XXXX)", file=sys.stderr)
        return 1

    cmd = rest[0] if rest else "loop"
    if cmd == "hello":
        cmd_hello(code, name); return 0
    if cmd == "listen":
        return cmd_listen(code, name, timeout)
    if cmd == "reply":
        return cmd_reply(code, name, " ".join(rest[1:]), req_id)
    if cmd == "loop":
        cmd_loop(code, name); return 0
    print("unknown command: " + cmd, file=sys.stderr)
    return 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
