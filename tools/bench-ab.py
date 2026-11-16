#!/usr/bin/env python3
"""Interleaved A/B of two tortty-java jars, with the conditions pinned.

The protocol exists because every assumption in it was broken once and cost a
wrong verdict (`docs/BENCHMARKS.md`, `L23`):

* **a display of its own** — a shared screen is resized under you, and a paint
  bound loop counts cells, so the grid size is part of the measurement;
* **the grid pinned** with `--cols/--rows`, so two runs cannot differ in size;
* **paired and interleaved** A,B,A,B,..., because the machine drifts (governor,
  boost, background load) and an A-then-B series silently compares two machines;
* **the condition recorded** — governor and clock at start and end, printed with
  the result, since 1.4 GHz and 4.3 GHz give numbers that must never be pooled;
* **every child killed by its own pid**, never by name: a `pgrep` cleanup once
  killed the owner's live session.

    tools/bench-ab.py --a /tmp/old.jar --b target/tortty-java.jar --frames 800

Prints, per side: every run, the median, and — when the jars report them —
`feeds`/`feed_bytes`/`feeds_per_mb` from the debug socket.
"""

import argparse
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time

FIRE_DEFAULT = "/home/user/dados/desenv/gh/tortty/tools/doomfire.py"


def sh(cmd):
    try:
        return subprocess.run(cmd, shell=True, capture_output=True, text=True).stdout.strip()
    except Exception:
        return ""


def governor():
    g = sh("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
    f = sh("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq")
    return f"{g or '?'} @ {f or '?'} kHz"


def free_display():
    for n in range(101, 130):
        if not os.path.exists(f"/tmp/.X{n}-lock") and not os.path.exists(f"/tmp/.X11-unix/X{n}"):
            return n
    sys.exit("bench-ab: no free X display between :101 and :129")


def start_xvfb(n, width, height):
    p = subprocess.Popen(["setsid", "Xvfb", f":{n}", "-screen", "0", f"{width}x{height}x24"],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         start_new_session=True)
    for _ in range(50):
        if os.path.exists(f"/tmp/.X11-unix/X{n}"):
            return p
        time.sleep(0.1)
    p.kill()
    sys.exit(f"bench-ab: Xvfb :{n} did not come up")


def ask(sock, payload, timeout=20.0):
    """One request. The app answers on its own thread, so under a flood the
    reply can queue behind a drain slice: a 5 s timeout reads `None` and turns a
    working counter into a missing one (measured, 3 of 8 runs)."""
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        s.connect(sock)
        s.sendall((json.dumps(payload) + "\n").encode())
        buf = b""
        while not buf.endswith(b"\n"):
            d = s.recv(1 << 16)
            if not d:
                break
            buf += d
        return json.loads(buf.decode())
    finally:
        s.close()


def run(jar, tag, args, display, work):
    out = os.path.join(work, f"stats-{tag}.txt")
    sock = os.path.join(work, f"{tag}.sock")
    for f in (out, sock):
        try:
            os.unlink(f)
        except FileNotFoundError:
            pass
    cfg = os.path.join(work, "cfg")
    os.makedirs(os.path.join(cfg, "tortty-java"), exist_ok=True)
    with open(os.path.join(cfg, "tortty-java", "tortty.conf"), "w") as f:
        # font 17 is the protocol size; `restore_state none` keeps a previous run
        # out of the picture
        f.write(f"font_size {args.font}\nrestore_state none\n")
    env = dict(os.environ, DISPLAY=f":{display}",
               XDG_STATE_HOME=os.path.join(work, "state"), XDG_CONFIG_HOME=cfg)
    # `sleep` after the generator, because the app closes a tab whose child has
    # gone and then exits: without it the socket is answering a corpse by the time
    # the stats file appears, and the counters read `None` (measured: 3 of 8 runs)
    child = (f"python3 {args.fire} --fps 0 --frames {args.frames} --stats-to {out}; sleep 4")
    p = subprocess.Popen(
        ["java", "-jar", jar, "--dpi", str(args.dpi), "--no-vsync",
         "--cols", str(args.cols), "--rows", str(args.rows),
         "--debug-socket", sock, "--cmd", child],
        env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    fps, state, t0 = None, {}, time.time()
    budget = args.run_timeout
    while time.time() - t0 < budget:
        if p.poll() is not None:
            break
        if os.path.exists(out):
            m = re.search(r"fps\s+([\d.]+)", open(out).read())
            if m:
                fps = float(m.group(1))
                break
        time.sleep(0.1)
    # the counters are the point: let the grid catch up with the frames, then read
    # them before the way out throws nothing away
    time.sleep(0.5)
    for _ in range(20):
        try:
            state = ask(sock, {"cmd": "state"})
            break
        except Exception:
            time.sleep(0.15)
    # this exact child, by pid: the app can ignore SIGTERM, and a stray frame
    # generator must never be left behind
    if p.poll() is None:
        p.kill()
    try:
        p.wait(timeout=10)
    except Exception:
        pass
    dt = time.time() - t0
    print(f"  {tag}: {fps} fps | {state.get('feeds_per_mb')} feeds/MB | {dt:.1f}s", flush=True)
    return fps


def summary(name, values):
    v = [x for x in values if x]
    if not v:
        print(f"  {name}: no result")
        return None
    v.sort()
    med = v[len(v) // 2] if len(v) % 2 else (v[len(v) // 2 - 1] + v[len(v) // 2]) / 2
    print(f"  {name}: runs {values} median {med:g} min {v[0]:g} max {v[-1]:g}")
    return med


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--a", required=True, help="jar of side A (the baseline)")
    ap.add_argument("--b", required=True, help="jar of side B (the candidate)")
    ap.add_argument("--frames", type=int, default=800)
    ap.add_argument("--pairs", type=int, default=3)
    ap.add_argument("--cols", type=int, default=80)
    ap.add_argument("--rows", type=int, default=24)
    ap.add_argument("--font", type=int, default=17, help="protocol size, do not change")
    ap.add_argument("--dpi", type=int, default=130)
    ap.add_argument("--screen", default="1920x1080")
    ap.add_argument("--fire", default=FIRE_DEFAULT)
    ap.add_argument("--run-timeout", type=float, default=20.0, help="seconds per run")
    ap.add_argument("--keep", action="store_true", help="keep the work directory")
    args = ap.parse_args()

    for jar in (args.a, args.b):
        if not os.path.exists(jar):
            sys.exit(f"bench-ab: no such jar: {jar}")
    if not os.path.exists(args.fire):
        sys.exit(f"bench-ab: no such generator: {args.fire}")

    width, height = args.screen.split("x")
    display = free_display()
    work = f"/tmp/bench-ab-{os.getpid()}"
    os.makedirs(work, exist_ok=True)
    xvfb = start_xvfb(display, width, height)
    print(f"conditions: governor {governor()} | grid {args.cols}x{args.rows} "
          f"font {args.font} | {args.frames} frames x {args.pairs} pairs | display :{display}")
    a, b = [], []
    try:
        for i in range(args.pairs):
            a.append(run(args.a, f"{i}a", args, display, work))
            b.append(run(args.b, f"{i}b", args, display, work))
    finally:
        xvfb.kill()
        if not args.keep:
            shutil.rmtree(work, ignore_errors=True)
        else:
            print(f"kept: {work}")
    print(f"conditions at the end: governor {governor()}")
    ma = summary("A", a)
    mb = summary("B", b)
    if ma and mb:
        print(f"\n  B/A: {mb / ma:.3f}  ({'+' if mb >= ma else ''}{(mb / ma - 1) * 100:.1f}%)")
    stray = sh("ps -eo pid,cmd | grep -E 'tortty-java.jar|doomfire' | grep -v grep "
               "| grep -v bench-ab")
    print(f"  strays: {stray or 'none'}")


if __name__ == "__main__":
    main()
