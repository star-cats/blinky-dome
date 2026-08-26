#!/usr/bin/env python3
"""Virtual clicky-pots console -> ClickyPacketV1 over UDP.

Stands in for the physical clicky console (6 push-pull pots + 7 buttons).
Serves a browser UI on localhost and emits the identical 18-byte packet the
hardware sends, as UDP datagrams:

  offset size  field
  0      2     magic     0xC11C, little-endian
  2      1     ver       1
  3      1     seq       send counter, wraps at 256
  4      1     pullBits  bit i = pot i pulled out
  5      1     btnBits   bit i = button i pressed
  6      12    pos[6]    uint16 little-endian, 0..10000

Every packet is full state. Send policy matches the firmware: any change
sends immediately (coalesced to ~50 Hz during drags) plus a 1 s heartbeat.
Receivers should accept iff size == 18 and magic and ver match.

Usage:
  python3 clicky_mock.py                        # UDP to 127.0.0.1:49436
  python3 clicky_mock.py --target 10.0.0.42
  python3 clicky_mock.py --port 49436 --ui-port 8090
Then open http://localhost:8090
"""

import argparse
import json
import socket
import struct
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAGIC = 0xC11C
VER = 1
NUM_POTS = 6
NUM_BTNS = 7
HEARTBEAT_S = 1.0
MIN_SEND_GAP_S = 0.02

state_lock = threading.Lock()
state = {
    "pos": [5000, 5000, 5000, 5000, 5000, 10000],
    "pull": [False] * NUM_POTS,
    "btn": [False] * NUM_BTNS,
}
stats = {"seq": 0, "sent": 0, "last_reason": "none", "last_send": 0.0}

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
target = ("127.0.0.1", 49436)


def pack_packet():
    pull_bits = 0
    btn_bits = 0
    for i in range(NUM_POTS):
        if state["pull"][i]:
            pull_bits |= 1 << i
    for i in range(NUM_BTNS):
        if state["btn"][i]:
            btn_bits |= 1 << i
    return struct.pack(
        "<HBBBB6H", MAGIC, VER, stats["seq"] & 0xFF, pull_bits, btn_bits,
        *[max(0, min(10000, int(p))) for p in state["pos"]]
    )


def send_packet(reason):
    with state_lock:
        pkt = pack_packet()
        stats["seq"] = (stats["seq"] + 1) & 0xFF
        stats["sent"] += 1
        stats["last_reason"] = reason
        stats["last_send"] = time.monotonic()
    sock.sendto(pkt, target)


def heartbeat_loop():
    while True:
        time.sleep(0.05)
        if time.monotonic() - stats["last_send"] >= HEARTBEAT_S:
            send_packet("heartbeat")


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _json(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/":
            body = PAGE.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path == "/status":
            with state_lock:
                self._json({
                    "seq": stats["seq"], "sent": stats["sent"],
                    "last_reason": stats["last_reason"],
                    "target": f"{target[0]}:{target[1]}",
                    "state": state,
                })
        else:
            self.send_error(404)

    def do_POST(self):
        if self.path != "/state":
            self.send_error(404)
            return
        n = int(self.headers.get("Content-Length", 0))
        try:
            incoming = json.loads(self.rfile.read(n))
            pos = [max(0, min(10000, int(p))) for p in incoming["pos"]][:NUM_POTS]
            pull = [bool(v) for v in incoming["pull"]][:NUM_POTS]
            btn = [bool(v) for v in incoming["btn"]][:NUM_BTNS]
            if len(pos) != NUM_POTS or len(pull) != NUM_POTS or len(btn) != NUM_BTNS:
                raise ValueError("bad lengths")
        except (ValueError, KeyError, TypeError, json.JSONDecodeError) as e:
            self._json({"error": str(e)}, 400)
            return
        with state_lock:
            changed = (pos != state["pos"] or pull != state["pull"]
                       or btn != state["btn"])
            state["pos"], state["pull"], state["btn"] = pos, pull, btn
        if changed:
            gap = time.monotonic() - stats["last_send"]
            if gap < MIN_SEND_GAP_S:
                time.sleep(MIN_SEND_GAP_S - gap)
            send_packet("change")
        self._json({"ok": True, "sent": changed})


PAGE = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Clicky Console (virtual)</title>
<style>
  * { box-sizing: border-box; margin: 0; }
  body {
    background:
      repeating-linear-gradient(93deg,
        rgba(96, 62, 30, .10) 0 3px, rgba(0,0,0,0) 3px 9px,
        rgba(140, 96, 48, .08) 9px 14px, rgba(0,0,0,0) 14px 23px),
      linear-gradient(180deg, #c89f68, #b98e5c 45%, #a67c4e);
    color: #4a3826;
    font: 13px/1.4 ui-monospace, Menlo, monospace;
    display: flex; flex-direction: column; align-items: center;
    justify-content: center; gap: 26px; padding: 24px; min-height: 100vh;
  }
  h1 { font-size: 13px; letter-spacing: .3em; color: #6b5335; text-shadow: 0 1px 0 rgba(255,255,255,.25); }
  #plate {
    background: linear-gradient(180deg, #c3c8cf, #a6abb4 40%, #8e939c);
    border: 1px solid #6f747c; border-radius: 4px;
    box-shadow: 0 10px 28px rgba(40, 24, 8, .45), inset 0 1px 0 rgba(255,255,255,.55), inset 0 -2px 4px rgba(0,0,0,.18);
    padding: 34px 30px 26px; display: flex; align-items: center; gap: 26px;
  }
  .grp { display: flex; align-items: center; }
  .cell { display: flex; flex-direction: column; align-items: center; gap: 7px; }
  .cap {
    font-size: 9px; letter-spacing: .08em; color: #565b63;
    text-shadow: 0 1px 0 rgba(255,255,255,.4); white-space: nowrap;
  }
  .sq {
    width: 40px; height: 40px; border-radius: 5px; border: 1px solid rgba(0,0,0,.5);
    cursor: pointer; padding: 0; touch-action: none; user-select: none; -webkit-user-select: none;
    box-shadow: 0 3px 5px rgba(0,0,0,.4), inset 0 2px 3px rgba(255,255,255,.35), inset 0 -3px 5px rgba(0,0,0,.3);
  }
  .sq.small { width: 34px; height: 34px; border-radius: 3px; }
  .sq.on {
    transform: translateY(2px); filter: brightness(.72);
    box-shadow: 0 1px 1px rgba(0,0,0,.4), inset 0 3px 7px rgba(0,0,0,.5);
  }
  .cluster { gap: 2px; }
  .pots { gap: 22px; }
  .knob {
    width: 62px; height: 62px; border-radius: 50%; border: 2px solid rgba(0,0,0,.45);
    position: relative; cursor: ns-resize; touch-action: none; user-select: none; -webkit-user-select: none;
    box-shadow: 0 5px 9px rgba(0,0,0,.45), inset 0 4px 7px rgba(255,255,255,.3), inset 0 -5px 8px rgba(0,0,0,.35);
    transition: transform .12s, box-shadow .12s;
  }
  .knob .ptr { position: absolute; inset: 0; border-radius: 50%; }
  .knob .ptr::before {
    content: ""; position: absolute; left: 50%; top: 5px; width: 4px; height: 16px;
    margin-left: -2px; border-radius: 2px; background: rgba(20, 14, 10, .68);
    box-shadow: 0 0 2px rgba(255,255,255,.35);
  }
  .knob.pulled {
    transform: scale(1.18);
    box-shadow: 0 12px 18px rgba(0,0,0,.55), 0 0 0 3px rgba(77, 201, 255, .5), inset 0 4px 7px rgba(255,255,255,.3), inset 0 -5px 8px rgba(0,0,0,.35);
  }
  .val { font-size: 10px; color: #494e56; text-shadow: 0 1px 0 rgba(255,255,255,.4); }
  .latch { font-size: 9px; color: #6a6f77; cursor: pointer; user-select: none; }
  .latch input { accent-color: #2b6cb0; vertical-align: middle; margin: 0 2px 0 0; width: 10px; height: 10px; }
  #status {
    font-size: 12px; color: #5d4930; display: flex; gap: 16px; align-items: center;
    text-shadow: 0 1px 0 rgba(255,255,255,.25);
  }
  #dot { width: 8px; height: 8px; border-radius: 50%; background: #8a7355; display: inline-block; }
  #dot.flash { background: #ff4b3e; }
  #hint { font-size: 11px; color: #7a6244; text-shadow: 0 1px 0 rgba(255,255,255,.2); }
</style>
</head>
<body>
<h1>CLICKY CONSOLE — VIRTUAL</h1>
<div id="plate"></div>
<div id="status">
  <span id="dot"></span>
  <span id="target"></span>
  <span id="seq"></span>
  <span id="sent"></span>
</div>
<div id="hint">knobs: drag ↕ to turn (shift = fine, wheel works) · click = pull/push · right switch is a toggle</div>
<script>
const NUM_POTS = 6, NUM_BTNS = 7;
const st = {
  pos: [5000, 5000, 5000, 5000, 5000, 10000],
  pull: Array(NUM_POTS).fill(false),
  btn: Array(NUM_BTNS).fill(false),
};
const latched = Array(NUM_BTNS).fill(false);
const renderers = [];

const BTN_COLORS = [
  'linear-gradient(160deg, #e8564a, #c22318)',
  'linear-gradient(160deg, #e07ad4, #b13ba0)',
  'linear-gradient(160deg, #6d7fdd, #3b4cb0)',
  'linear-gradient(160deg, #55519e, #2e2a6b)',
  'linear-gradient(160deg, #cf95a0, #a06470)',
  'linear-gradient(160deg, #dfe2e6, #a8adb5)',
  'linear-gradient(160deg, #e8564a, #b01f14)',
];
const KNOB_COLORS = [
  'radial-gradient(circle at 35% 30%, #7d90e2, #4353b5 70%)',
  'radial-gradient(circle at 35% 30%, #9678c9, #5f3f96 70%)',
  'radial-gradient(circle at 35% 30%, #ea5f4d, #bd2c1c 70%)',
  'radial-gradient(circle at 35% 30%, #f2b5a0, #d68a72 70%)',
  'radial-gradient(circle at 35% 30%, #f2ead6, #cfc4a6 70%)',
  'radial-gradient(circle at 35% 30%, #de74c8, #b23f9c 70%)',
];

let postTimer = null, postPending = false;
function post() {
  if (postTimer) { postPending = true; return; }
  postTimer = setTimeout(() => { postTimer = null; if (postPending) { postPending = false; post(); } }, 20);
  fetch('/state', { method: 'POST', body: JSON.stringify(st) }).catch(() => {});
}

function makeButton(i, small) {
  const cell = document.createElement('div');
  cell.className = 'cell';
  const b = document.createElement('button');
  b.className = 'sq' + (small ? ' small' : '');
  b.style.background = BTN_COLORS[i];
  b.title = 'button ' + (i + 1) + ' (bit ' + i + ')';
  const setBtn = (on) => {
    st.btn[i] = on;
    b.classList.toggle('on', on);
    post();
  };
  b.onpointerdown = (e) => { e.preventDefault(); if (!latched[i]) setBtn(true); };
  const release = () => { if (!latched[i] && st.btn[i]) setBtn(false); };
  b.onpointerup = release;
  b.onpointerleave = release;
  renderers.push(() => b.classList.toggle('on', st.btn[i]));
  const latch = document.createElement('label');
  latch.className = 'latch';
  const cb = document.createElement('input');
  cb.type = 'checkbox';
  cb.onchange = () => {
    latched[i] = cb.checked;
    if (cb.checked) setBtn(true); else if (st.btn[i]) setBtn(false);
  };
  latch.append(cb, 'hold');
  cell.append(b, latch);
  return cell;
}

function makeToggle(i) {
  const cell = document.createElement('div');
  cell.className = 'cell';
  const b = document.createElement('button');
  b.className = 'sq';
  b.style.background = BTN_COLORS[i];
  b.title = 'toggle switch (bit ' + i + ')';
  b.onclick = () => {
    st.btn[i] = !st.btn[i];
    b.classList.toggle('on', st.btn[i]);
    post();
  };
  renderers.push(() => b.classList.toggle('on', st.btn[i]));
  const cap = document.createElement('div');
  cap.className = 'cap';
  cap.textContent = 'TOGGLE';
  cell.append(b, cap);
  return cell;
}

function makeKnob(i) {
  const cell = document.createElement('div');
  cell.className = 'cell';
  const k = document.createElement('div');
  k.className = 'knob';
  k.style.background = KNOB_COLORS[i];
  k.title = 'pot ' + (i + 1) + ' — drag to turn, click to pull';
  const ptr = document.createElement('div');
  ptr.className = 'ptr';
  k.append(ptr);
  const val = document.createElement('div');
  val.className = 'val';
  const render = () => {
    ptr.style.transform = 'rotate(' + (-135 + st.pos[i] / 10000 * 270) + 'deg)';
    k.classList.toggle('pulled', st.pull[i]);
    val.textContent = (st.pos[i] / 100).toFixed(1) + '%' + (st.pull[i] ? ' ↑' : '');
  };
  let startY = 0, startVal = 0, moved = false;
  k.onpointerdown = (e) => {
    e.preventDefault();
    k.setPointerCapture(e.pointerId);
    startY = e.clientY; startVal = st.pos[i]; moved = false;
  };
  k.onpointermove = (e) => {
    if (!k.hasPointerCapture(e.pointerId)) return;
    const dy = startY - e.clientY;
    if (Math.abs(dy) > 4) moved = true;
    if (moved) {
      const scale = e.shiftKey ? 10 : 66;
      st.pos[i] = Math.max(0, Math.min(10000, Math.round(startVal + dy * scale)));
      render(); post();
    }
  };
  k.onpointerup = (e) => {
    k.releasePointerCapture(e.pointerId);
    if (!moved) { st.pull[i] = !st.pull[i]; render(); post(); }
  };
  k.onwheel = (e) => {
    e.preventDefault();
    const scale = e.shiftKey ? 1 : 8;
    st.pos[i] = Math.max(0, Math.min(10000, Math.round(st.pos[i] - e.deltaY * scale)));
    render(); post();
  };
  render();
  renderers.push(render);
  cell.append(k, val);
  return cell;
}

const plate = document.getElementById('plate');
const left = document.createElement('div');
left.className = 'grp';
left.style.gap = '14px';
left.append(makeButton(0, false), makeButton(1, false));
const pots = document.createElement('div');
pots.className = 'grp pots';
for (let i = 0; i < NUM_POTS; i++) pots.append(makeKnob(i));
const cluster = document.createElement('div');
cluster.className = 'grp cluster';
for (let i = 2; i <= 5; i++) cluster.append(makeButton(i, true));
plate.append(left, pots, cluster, makeToggle(6));

const dot = document.getElementById('dot');
fetch('/status').then(r => r.json()).then(s => {
  st.pos = s.state.pos; st.pull = s.state.pull; st.btn = s.state.btn;
  renderers.forEach(fn => fn());
}).catch(() => {});
let lastSent = 0;
setInterval(async () => {
  try {
    const s = await (await fetch('/status')).json();
    document.getElementById('target').textContent = 'UDP → ' + s.target;
    document.getElementById('seq').textContent = 'seq ' + s.seq;
    document.getElementById('sent').textContent = s.sent + ' pkts (' + s.last_reason + ')';
    if (s.sent !== lastSent) {
      lastSent = s.sent;
      dot.classList.add('flash');
      setTimeout(() => dot.classList.remove('flash'), 120);
    }
  } catch (e) {}
}, 500);
</script>
</body>
</html>
"""


def main():
    global target
    ap = argparse.ArgumentParser(description="Virtual clicky console -> ClickyPacketV1 over UDP")
    ap.add_argument("--target", default="127.0.0.1", help="UDP destination IP (default 127.0.0.1)")
    ap.add_argument("--port", type=int, default=49436, help="UDP destination port (default 49436 = 0xC11C)")
    ap.add_argument("--ui-port", type=int, default=8090, help="browser UI port (default 8090)")
    args = ap.parse_args()
    target = (args.target, args.port)

    threading.Thread(target=heartbeat_loop, daemon=True).start()
    httpd = ThreadingHTTPServer(("127.0.0.1", args.ui_port), Handler)
    print(f"clicky mock: UI http://localhost:{args.ui_port}  UDP -> {target[0]}:{target[1]}")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
