#!/usr/bin/env python3
"""E2E in Xvfb :99 — a real Super+a pressed into the window with `xdotool`.

Needs a window manager in the display: without one the frame is never activated
and AWT drops the key events (measured: `xdotool key` reaches nothing, while the
socket's `write` does). `metacity --replace &` on the same `DISPLAY` is enough.

The child sets the kitty flags itself (`CSI > 8 u`), appends every byte it
receives to a file, and the file is read back. Expected, and the same as the
Rust core's `flag_8_sends_all_including_super`:

    flag 8 on   z, a -> CSI 122u CSI 97u    Super+a -> CSI 97;9u
    flag 8 off  z, a -> "za"                Super+a -> nothing (the WM's chord)

Super+N opens a workspace in both cases, so the child never sees that chord
either; the marker file is appended, so a new tab does not truncate it.
"""
import os
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DISPLAY = ":99"
OUT = "/tmp/superbytes.txt"
ENV = dict(os.environ, DISPLAY=DISPLAY)


def send(w, *keys):
    """Focus the frame once, then press the keys with short gaps."""
    subprocess.run(["xdotool", "windowactivate", "--sync", w], env=ENV)
    time.sleep(0.5)
    for k in keys:
        subprocess.run(["xdotool", "key", "--clearmodifiers", k], env=ENV)
        time.sleep(0.7)


def find_frame(pid):
    for _ in range(120):
        ids = subprocess.run(["xdotool", "search", "--pid", str(pid)],
                             env=ENV, capture_output=True, text=True).stdout.split()
        for cand in ids:
            name = subprocess.run(["xdotool", "getwindowname", cand], env=ENV,
                                  capture_output=True, text=True).stdout.strip()
            if name == "tortty-java":
                return cand
        time.sleep(0.1)
    return None


def run_case(sock, command):
    for f in (OUT, sock):
        if os.path.exists(f):
            os.remove(f)
    p = subprocess.Popen(
        ["java", "-jar", ROOT + "/target/tortty-java.jar", "--debug-socket", sock,
         "--no-state", "--config", "/tmp/tortty-e2e.conf", "--cmd", command],
        env=ENV, stdout=open("/tmp/tortty-super.log", "w"), stderr=subprocess.STDOUT, cwd=ROOT)
    try:
        for _ in range(150):
            if os.path.exists(sock):
                break
            time.sleep(0.1)
        w = find_frame(p.pid)
        if not w:
            return None
        time.sleep(5.5)  # the pty child comes up after the socket and the first paint
        send(w, "z", "a", "super+a")
        with open(OUT, "rb") as f:
            before = f.read()
        send(w, "super+n")  # ours: a workspace, not the child
        time.sleep(1.0)
        with open(OUT, "rb") as f:
            after = f.read()
        return before, after
    finally:
        p.terminate()
        try:
            p.wait(timeout=5)
        except subprocess.TimeoutExpired:
            p.kill()


def main():
    failures = []

    def check(what, cond, detail=""):
        print(("ok   " if cond else "FAIL ") + what + ((" — " + str(detail)) if detail else ""))
        if not cond:
            failures.append(what)

    got = run_case("/tmp/tortty-super-flags.sock",
                   "printf '\\033[>8u'; stty raw -echo; cat >> " + OUT)
    check("flag 8: a plain key and the Super chord arrive as CSI u",
          got and got[0] == b"\x1b[122u\x1b[97u\x1b[97;9u", got and got[0])
    check("flag 8: Super+N is ours and never reaches the child",
          got and got[1] == got[0], got and got[1])

    got = run_case("/tmp/tortty-super-plain.sock", "stty raw -echo; cat >> " + OUT)
    check("no flags: the plain keys are the text itself", got and got[0] == b"za", got and got[0])
    check("no flags: the Super chord is swallowed, Super+N too",
          got and got[1] == got[0], got and got[1])

    print()
    if failures:
        print("super e2e: FAILED", failures)
        sys.exit(1)
    print("super e2e: all passed")


main()
