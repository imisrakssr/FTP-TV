"""Draws the launcher icons and the Android TV banner (so the repo holds no binaries)."""
import os, sys
from PIL import Image, ImageDraw, ImageFont

RES = sys.argv[1]
FONTS = ["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
         "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"]

def font(size):
    for f in FONTS:
        if os.path.exists(f):
            return ImageFont.truetype(f, size)
    return ImageFont.load_default(size=size)

def grad(w, h, top=(28, 10, 14), bottom=(8, 8, 12)):
    img = Image.new("RGBA", (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        d.line([(0, y), (w, y)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,))
    return img

def tv(d, cx, cy, s):
    """A small red TV with a play triangle."""
    w, h = s, s * 0.72
    x0, y0 = cx - w / 2, cy - h / 2
    d.rounded_rectangle([x0, y0, x0 + w, y0 + h], radius=s * 0.14, fill=(229, 9, 20, 255))
    d.rounded_rectangle([x0 + s * .07, y0 + s * .07, x0 + w - s * .07, y0 + h - s * .07], radius=s * .09, fill=(20, 6, 8, 255))
    tri = [(cx - s * .12, cy - s * .15), (cx - s * .12, cy + s * .15), (cx + s * .17, cy)]
    d.polygon(tri, fill=(255, 255, 255, 255))
    d.line([(cx - s * .18, y0 - s * .16), (cx - s * .03, y0)], fill=(229, 9, 20, 255), width=max(2, int(s * .05)))
    d.line([(cx + s * .18, y0 - s * .16), (cx + s * .03, y0)], fill=(229, 9, 20, 255), width=max(2, int(s * .05)))

def icon(px, round_):
    big = px * 4
    img = grad(big, big)
    d = ImageDraw.Draw(img)
    tv(d, big / 2, big * .42, big * .56)
    f = font(int(big * .17))
    t = "FTP TV"
    tw = d.textlength(t, font=f)
    d.text(((big - tw) / 2, big * .70), t, font=f, fill=(255, 255, 255, 255))
    mask = Image.new("L", (big, big), 0)
    md = ImageDraw.Draw(mask)
    if round_:
        md.ellipse([0, 0, big, big], fill=255)
    else:
        md.rounded_rectangle([0, 0, big, big], radius=big * .22, fill=255)
    img.putalpha(mask)
    return img.resize((px, px), Image.LANCZOS)

for dens, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    out = os.path.join(RES, "mipmap-" + dens)
    os.makedirs(out, exist_ok=True)
    icon(px, False).save(os.path.join(out, "ic_launcher.png"))
    icon(px, True).save(os.path.join(out, "ic_launcher_round.png"))

# TV banner 320x180 (xhdpi)
W, H = 320 * 3, 180 * 3
b = grad(W, H, top=(30, 8, 12), bottom=(6, 6, 10))
d = ImageDraw.Draw(b)
tv(d, W * .22, H * .52, H * .55)
f = font(int(H * .26))
d.text((W * .40, H * .26), "FTP TV", font=f, fill=(255, 255, 255, 255))
d.text((W * .405, H * .62), "Live TV", font=font(int(H * .11)), fill=(229, 9, 20, 255))
out = os.path.join(RES, "drawable-xhdpi")
os.makedirs(out, exist_ok=True)
b.convert("RGB").resize((320, 180), Image.LANCZOS).save(os.path.join(out, "tv_banner.png"))
print("icons + banner done")
