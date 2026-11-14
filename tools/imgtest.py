#!/usr/bin/env python3
"""Sends a kitty graphics image to the terminal on stdout and shows the reply.

Run it inside the terminal (it puts the tty in raw mode, and restores it):

  tools/imgtest.py            PNG, transmit+display, 40x20 cells
  tools/imgtest.py rgba       raw RGBA (f=32), no PNG decoder involved
  tools/imgtest.py file       t=f: the terminal reads the file itself
  tools/imgtest.py chunked    the payload split in 3 chunks with m=1
  tools/imgtest.py scroll     place the image, then push three screens of text
  tools/imgtest.py query      a=q, expects an OK reply
  tools/imgtest.py delete     a=d,d=a
  tools/imgtest.py crop       x,y,w,h: only the red quadrant is shown
  tools/imgtest.py named      p=: one placement, then the same pair again moved
  tools/imgtest.py under      z=-1: the picture behind the words written over it
  tools/imgtest.py placeholder U=1: the picture drawn *through* the text grid,
                              one U+10EEEE cell at a time (L16)

The image is a 4-color checker so the placement, the scaling and the clipping
are visible at a glance. Replies are printed as `reply: ...`; a silent terminal
means the escape went nowhere.
"""

import base64
import os
import select
import struct
import sys
import time
import tty
import zlib

W, H = 160, 80          # image pixels; 8x16 cells in the terminal
CELL = (8, 16)
PATH = "/tmp/imgtest.png"


def png(width, height):
    """Minimal RGBA PNG: red, green, blue and yellow quadrants."""
    raw = bytearray()
    for y in range(height):
        raw.append(0)                                   # filter: none
        for x in range(width):
            left, top = x < width // 2, y < height // 2
            if left and top:
                px = (255, 0, 0, 255)
            elif not left and top:
                px = (0, 255, 0, 255)
            elif left:
                px = (0, 0, 255, 255)
            else:
                px = (255, 255, 0, 255)
            raw += bytes(px)

    def chunk(kind, data):
        body = kind + data
        return (struct.pack(">I", len(data)) + body
                + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF))

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
            + chunk(b"IEND", b""))


def apc(out, body):
    out.write(b"\x1b_G" + body + b"\x1b\\")
    out.flush()


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "png"
    cols, rows = W // CELL[0], H // CELL[1]

    data = png(W, H)
    with open(PATH, "wb") as f:
        f.write(data)
    b64 = base64.b64encode(data)
    out = sys.stdout.buffer

    raw = os.isatty(0)
    saved = None
    if raw:
        saved = tty.tcgetattr(0)
        tty.setraw(0)
    try:
        if mode == "png":
            apc(out, b"a=T,f=100,i=1,c=%d,r=%d;%s" % (cols, rows, b64))
        elif mode == "rgba":
            # 32-bit raw (4 bytes per pixel, as f=32 requires), chunked
            body = bytearray()
            for y in range(H):
                for x in range(W):
                    left, top = x < W // 2, y < H // 2
                    body += bytes(((255, 0, 0) if left and top else
                                   (0, 255, 0) if top else
                                   (0, 0, 255) if left else (255, 255, 0)) + (0,))
            enc = base64.b64encode(bytes(body))
            step = 4096
            parts = [enc[i:i + step] for i in range(0, len(enc), step)]
            for n, part in enumerate(parts):
                more = b",m=1" if n < len(parts) - 1 else b""
                head = (b"a=t,f=32,s=%d,v=%d,i=2,c=%d,r=%d" % (W, H, cols, rows)
                        + more + b";" if n == 0 else b"G" + more + b";")
                out.write(b"\x1b_G" + head + part + b"\x1b\\")
            out.flush()
            apc(out, b"a=p,i=2,c=%d,r=%d" % (cols, rows))
        elif mode == "file":
            apc(out, b"a=T,f=100,t=f,i=3,c=%d,r=%d;%s"
                % (cols, rows, base64.b64encode(PATH.encode())))
        elif mode == "chunked":
            step = max(1, len(b64) // 3)
            parts = [b64[i:i + step] for i in range(0, len(b64), step)]
            for n, part in enumerate(parts):
                more = b",m=1" if n < len(parts) - 1 else b""
                head = (b"a=T,f=100,i=4,c=%d,r=%d" % (cols, rows) + more + b";"
                        if n == 0 else b"G" + more + b";")
                out.write(b"\x1b_G" + head + part + b"\x1b\\")
            out.flush()
        elif mode == "scroll":
            apc(out, b"a=T,f=100,i=5,c=%d,r=%d;%s" % (cols, rows, b64))
            out.write(b"\r\n")
            for i in range(1, 3 * 24):
                out.write(b"line %d\r\n" % i)
            out.flush()
            print("image placed at the top; use Shift+PageUp to scroll back to it")
        elif mode == "query":
            apc(out, b"a=q,i=0,f=100,t=d;")
        elif mode == "delete":
            apc(out, b"a=d,d=a;")
        elif mode == "crop":
            # the source rectangle: the top-left quadrant, stretched over the
            # whole placement. Stretched because `c`/`r` still say 8x16 cells.
            apc(out, b"a=T,f=100,i=6,c=%d,r=%d;%s" % (cols, rows, b64))
            out.write(b"\r\n")          # past the first placement
            apc(out, b"a=T,f=100,i=7,c=%d,r=%d,x=0,y=0,w=%d,h=%d;%s"
                % (cols, rows, W // 2, H // 2, b64))
            print("top: the whole image. below: the red quadrant, stretched")
        elif mode == "named":
            # the same (image, placement id) twice: the second replaces the first
            apc(out, b"a=T,f=100,i=8,p=7,c=%d,r=%d;%s" % (cols, rows, b64))
            print("placed with p=7 — answering OK,p=7")
            time.sleep(1.0)
            out.write(b"\r\n\r\n")
            apc(out, b"a=p,i=8,p=7,c=%d,r=%d" % (cols // 2, rows // 2))
            print("moved the same p=7 below, at half the size: one placement, not two")
        elif mode == "under":
            # pin the row first: the image lands where the cursor is, and the
            # words have to land on the same cells, not wherever the prompt was
            out.write(b"\x1b[1;1H")
            out.flush()
            # C=1 keeps the cursor put, so the text goes on top of the picture
            apc(out, b"a=T,f=100,i=9,c=%d,r=%d,z=-1,C=1;%s" % (cols, rows, b64))
            time.sleep(0.3)
            out.write(b"TEXT OVER THE PICTURE\r\n")
            out.write(b"second line, also on top\r\n")
            out.flush()
            print("the words above the picture are readable: z=-1 is under the text")
            print("a cell with a background of its own covers it — try "
                  "printf '\\e[44m  blue  \\e[0m' over the picture")
        elif mode == "placeholder":
            # `U=1`: placed nowhere — the U+10EEEE cells *are* the placement, and
            # each shows its own slice of the picture cut into a 10x5 grid. The
            # image id rides in the foreground colour (true colour 0;0;9 = 9) and
            # the row and column in combining diacritics.
            pcols, prows = 10, 5
            apc(out, b"a=T,f=100,i=9,U=1,c=%d,r=%d;%s" % (pcols, prows, b64))
            out.write(b"\r\n")
            out.flush()
            # the protocol's own number -> diacritic list, first ten
            marks = "\u0305\u030D\u030E\u0310\u0312\u033D\u033E\u033F\u0346\u034A"
            ph = "\U0010EEEE"
            out.write(b"\x1b[38;2;0;0;9m")
            for r in range(prows):
                for c in range(pcols):
                    out.write((ph + marks[r] + marks[c]).encode())
                out.write(b"\r\n")
            out.write(b"\x1b[0m")
            out.flush()
            print("the picture comes through %d x %d placeholder cells" % (pcols, prows))
            print("the cells are text: they scroll, and writing over them takes "
                  "the picture away")
        else:
            sys.exit(__doc__)

        deadline = time.time() + 0.5
        while raw and time.time() < deadline:
            if not select.select([0], [], [], 0.1)[0]:
                continue
            chunk = os.read(0, 4096)
            if not chunk:
                break
            print("reply: %r" % chunk)
    finally:
        if saved is not None:
            tty.tcsetattr(0, tty.TCSADRAIN, saved)


if __name__ == "__main__":
    main()
