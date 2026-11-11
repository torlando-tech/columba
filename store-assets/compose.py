#!/usr/bin/env python3
"""Compose Google Play store screenshots from a manifest.

    python3 compose.py                 # build every screen in listing.yaml
    python3 compose.py --only 01_contacts

Design notes (vs the 2026-09 prototype in ~/playstore-assets):
- The device frame is generated from geometry (FRAMES below), not a
  third-party raster template. Frame layer and screen mask come from the
  same source of truth, so the punch hole is exactly where we say it is
  and the status-bar overlay height falls out of the geometry.
- One manifest (listing.yaml) pins canvas, frame, gradient, caption copy,
  and the capture for every final. Rebuilding the whole set is one command.
- Captures should be made clean at capture time (Android demo mode, see
  README). `status_bar: true` is the fallback for captures that still
  carry a real notification bar: it paints a minimal bar (clock + wifi +
  battery) exactly tall enough to cover the punch hole.
"""
import argparse
import math
import os
import sys

import numpy as np
import yaml
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))

# ---------------------------------------------------------------------------
# Device geometry, in "device units" (screen width = 1280).
# Pixel 9 Pro: 1280x2856 panel, near-uniform bezel, centered punch hole.
# ---------------------------------------------------------------------------
FRAMES = {
    "pixel-9-pro": {
        "screen": [1280, 2856],
        "bezel": 26,
        "body_radius": 190,
        "screen_radius": 120,
        "hole": {"r": 30, "cy": 62},          # radius, y from screen top
        "buttons": [                            # right edge: y fractions of body height
            {"y0": 0.215, "y1": 0.300},        # volume rocker
            {"y0": 0.335, "y1": 0.400},        # power
        ],
        "button_w": 10,
        "body": "#17171B",
        "rim": "#43434C",
        "hole_color": "#050507",
    },
}


def hex2rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def build_frame(name, ss=4):
    """Render the device frame at 1x, drawn at `ss`x for AA.

    Returns (frame RGBA, screen-hole mask L, geom). The frame layer has a
    transparent screen area and an opaque punch hole; the capture is
    clipped by the mask (the same rect/radius as the hole, so it can never
    reach the body's rounded corners), then the frame is pasted over it.
    """
    g = FRAMES[name]
    sw, sh = g["screen"]
    bz = g["bezel"]
    bw, bh = sw + 2 * bz, sh + 2 * bz
    margin = g["button_w"] + 2
    fw, fh = bw + 2 * margin, bh

    frame = Image.new("RGBA", (fw * ss, fh * ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(frame)

    # side buttons (drawn first; the body covers their inner half)
    for b in g["buttons"]:
        y0, y1 = int(b["y0"] * bh * ss), int(b["y1"] * bh * ss)
        x0, x1 = (margin + bw - 2) * ss, (margin + bw + g["button_w"]) * ss
        d.rounded_rectangle([x0, y0, x1, y1], radius=g["button_w"] * ss // 2,
                            fill=hex2rgb(g["body"]) + (255,))

    # body + rim
    body = [margin * ss, 0, (margin + bw) * ss, bh * ss]
    d.rounded_rectangle(body, radius=g["body_radius"] * ss,
                        fill=hex2rgb(g["body"]) + (255,))
    d.rounded_rectangle(body, radius=g["body_radius"] * ss,
                        outline=hex2rgb(g["rim"]) + (255,), width=3 * ss)

    # screen cutout (transparent) + thin black ring
    screen = [(margin + bz) * ss, bz * ss, (margin + bz + sw) * ss, (bz + sh) * ss]
    d.rounded_rectangle(screen, radius=g["screen_radius"] * ss, fill=(0, 0, 0, 0))
    d.rounded_rectangle(screen, radius=g["screen_radius"] * ss,
                        outline=(0, 0, 0, 255), width=2 * ss)

    # punch hole sits on top of whatever the screen shows
    hx = (margin + bz + sw / 2) * ss
    hy = (bz + g["hole"]["cy"]) * ss
    hr = g["hole"]["r"] * ss
    d.ellipse([hx - hr, hy - hr, hx + hr, hy + hr],
              fill=hex2rgb(g["hole_color"]) + (255,))

    mask = Image.new("L", (fw * ss, fh * ss), 0)
    ImageDraw.Draw(mask).rounded_rectangle(screen, radius=g["screen_radius"] * ss,
                                           fill=255)

    return (frame.resize((fw, fh), Image.LANCZOS),
            mask.resize((fw, fh), Image.LANCZOS), g)


def make_bg(w, h, top, bottom, angle="vertical"):
    cols = np.array([hex2rgb(top), hex2rgb(bottom)], dtype=np.float32)
    if angle == "diagonal":
        yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
        t = ((xx + yy) / (w + h - 2))[..., None]
    else:
        t = np.linspace(0, 1, h, dtype=np.float32)[:, None, None]
    img = np.broadcast_to(cols[0] * (1 - t) + cols[1] * t, (h, w, 3))
    return Image.fromarray(img.clip(0, 255).astype(np.uint8), "RGB")


def layout_caption(text, font_path, max_px, min_px, width_budget):
    """(lines, font): one line at max size, else two balanced lines, else shrink."""
    def fits(f, s):
        return f.getlength(s) <= width_budget

    fmax = ImageFont.truetype(font_path, max_px)
    if fits(fmax, text):
        return [text], fmax

    words = text.split()
    lines = [text]
    if len(words) > 1:  # balanced two-line split (widths scale linearly with size)
        ref = ImageFont.truetype(font_path, 100)
        best = min((abs(ref.getlength(" ".join(words[:i])) -
                         ref.getlength(" ".join(words[i:]))), i)
                   for i in range(1, len(words)))
        lines = [" ".join(words[:best[1]]), " ".join(words[best[1]:])]

    for size in range(max_px, min_px - 1, -2):
        f = ImageFont.truetype(font_path, size)
        if all(fits(f, ln) for ln in lines):
            return lines, f
    return [text], ImageFont.truetype(font_path, min_px)


def clean_status_bar(shot, bar_h, clock, font_path):
    """Minimal fallback bar for captures not made in demo mode: sampled bg
    color, Roboto clock, wifi + battery primitives."""
    a = np.asarray(shot.convert("RGB"))
    below = a[bar_h:bar_h + max(4, bar_h // 3)].reshape(-1, 3)
    bg = tuple(int(v) for v in np.median(below, axis=0))
    fg = (240, 240, 240) if sum(bg) / 3 < 128 else (40, 40, 42)

    shot = shot.convert("RGBA")
    d = ImageDraw.Draw(shot)
    w = shot.width
    d.rectangle([0, 0, w, bar_h], fill=bg + (255,))

    fs = int(bar_h * 0.42)
    font = ImageFont.truetype(font_path, fs)
    tb = d.textbbox((0, 0), clock, font=font)
    d.text((int(w * 0.062), (bar_h - (tb[3] - tb[1])) // 2 - tb[1]), clock,
           font=font, fill=fg)

    # battery: outlined pill, filled, nub
    bw2, bh2 = int(bar_h * 0.62), int(bar_h * 0.40)
    bx1 = w - int(bar_h * 0.72)          # ~24dp right margin, Pixel-style
    y0, y1 = (bar_h - bh2) // 2, (bar_h + bh2) // 2
    d.rounded_rectangle([bx1 - bw2, y0, bx1, y1], radius=bh2 // 4,
                        outline=fg, width=max(2, bar_h // 26))
    d.rounded_rectangle([bx1 - bw2 + 3, y0 + 3, bx1 - 3, y1 - 3],
                        radius=max(1, bh2 // 5), fill=fg)
    d.rectangle([bx1 + 1, bar_h // 2 - bh2 // 6, bx1 + max(2, bar_h // 16),
                 bar_h // 2 + bh2 // 6], fill=fg)

    # wifi: three arcs + dot, fully inside the bar
    cx, cy = bx1 - bw2 - int(bar_h * 0.55), int(bar_h * 0.62)
    for rr in (bar_h * 0.34, bar_h * 0.22, bar_h * 0.11):
        d.arc([cx - rr, cy - rr, cx + rr, cy + rr], 220, 320, fill=fg,
              width=max(2, bar_h // 22))
    r = max(2, bar_h * 0.05)
    d.ellipse([cx - r, cy - bar_h * 0.08 - r, cx + r, cy - bar_h * 0.08 + r], fill=fg)
    return shot


def compose_one(entry, cfg, out_dir):
    frame, mask, g = build_frame(entry.get("frame", cfg["frame"]))
    cw, ch = cfg["canvas"]
    bg_cfg = {**cfg["background"], **entry.get("background", {})}
    cap_cfg = {**cfg["caption"], **entry.get("caption", {})}
    font_path = os.path.join(HERE, "fonts", "Roboto-Medium.ttf")

    canvas = make_bg(cw, ch, bg_cfg["top"], bg_cfg["bottom"],
                     bg_cfg.get("angle", "vertical")).convert("RGBA")

    # caption block
    text = entry.get("caption_text", "")
    cap_bottom = 0
    if text:
        lines, font = layout_caption(text, font_path, cap_cfg["max_px"],
                                     cap_cfg["min_px"], int(cw * 0.88))
        d = ImageDraw.Draw(canvas)
        y = cap_cfg["top_margin"]
        color = hex2rgb(cap_cfg["color"]) + (255,)
        for ln in lines:
            d.text(((cw - d.textlength(ln, font=font)) / 2, y), ln,
                   font=font, fill=color)
            y += int(font.size * 1.18)
        cap_bottom = y

    # place the phone in the remaining zone
    zone_top = cap_bottom + (cap_cfg["gap"] if text else cap_cfg["top_margin"])
    zone_h = ch - zone_top - cfg["phone"]["bottom_margin"]
    scale = min(cw / frame.width, zone_h / frame.height) * cfg["phone"]["scale"]
    fw, fh = int(frame.width * scale), int(frame.height * scale)
    frame = frame.resize((fw, fh), Image.LANCZOS)
    mask = mask.resize((fw, fh), Image.LANCZOS)
    fx = (cw - fw) // 2
    fy = zone_top + (zone_h - fh) // 2

    # cover-fit the capture into the screen rect (inset from the frame's
    # left edge by the side-button margin too). The clip mask is the hole's
    # own rounded rect; round() keeps the crop window within half a pixel
    # of the resized hole so the frame's 2px ring covers the seam, and the
    # frame pasted last always wins over the capture.
    bz = g["bezel"]
    mg = g["button_w"] + 2
    sx, sy = round(fx + (mg + bz) * scale), round(fy + bz * scale)
    sw, sh = math.ceil(g["screen"][0] * scale), math.ceil(g["screen"][1] * scale)

    shot = Image.open(os.path.join(HERE, entry["capture"])).convert("RGBA")
    cover = max(sw / shot.width, sh / shot.height)
    if entry.get("status_bar", cfg.get("status_bar", False)):
        crop_top = (shot.height - sh / cover) / 2.0
        hole_bottom = crop_top + (g["hole"]["cy"] + g["hole"]["r"]) * scale / cover
        # cover the punch hole AND the capture's real status bar (~4.8% of
        # screen height on modern Android; taller than the hole alone)
        bar_h = max(math.ceil(hole_bottom), round(shot.height * 0.048))
        shot = clean_status_bar(shot, bar_h, cfg.get("clock", "9:41"), font_path)
    rs = shot.resize((math.ceil(shot.width * cover), math.ceil(shot.height * cover)),
                     Image.LANCZOS)
    cx0, cy0 = (rs.width - sw) // 2, (rs.height - sh) // 2
    shot_crop = rs.crop((cx0, cy0, cx0 + sw, cy0 + sh))
    mask_crop = mask.crop((sx - fx, sy - fy, sx - fx + sw, sy - fy + sh))
    canvas.paste(shot_crop, (sx, sy), mask_crop)

    canvas.paste(frame, (fx, fy), frame)

    base = os.path.join(out_dir, entry["id"])
    canvas.convert("RGB").save(base + ".png")
    canvas.convert("RGB").save(base + ".webp", "WEBP", quality=82, method=6)
    print(f"wrote {base}.png/.webp ({cw}x{ch})")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("manifest", nargs="?", default=os.path.join(HERE, "listing.yaml"))
    ap.add_argument("--only", action="append", help="build only this screen id (repeatable)")
    args = ap.parse_args()

    with open(args.manifest) as f:
        cfg = yaml.safe_load(f)
    out_dir = os.path.join(HERE, cfg.get("out_dir", "out"))
    os.makedirs(out_dir, exist_ok=True)

    built = 0
    for entry in cfg["screens"]:
        if args.only and entry["id"] not in args.only:
            continue
        compose_one(entry, cfg, out_dir)
        built += 1
    if not built:
        sys.exit("no screens matched")


if __name__ == "__main__":
    main()
