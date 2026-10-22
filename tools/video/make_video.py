"""KMP Translations Hub explainer video: the plugin's real panels as 3D cards in motion, narrated, ~90 s, 1920x1080.

    ./gradlew :idea-plugin:test --tests '*ListingRender*'      # panel renders → idea-plugin/build/listing
    python3 tools/video/make_video.py idea-plugin/build/listing docs dist/video [--voice "Ava (Premium)"] [--draft]

Writes dist/video/kmp-translations-hub.mp4, .srt subtitles and a 1280x720 thumbnail. Narration is macOS `say`, one
line per scene; each scene lasts as long as its line, so a different voice just re-times the video. --draft renders
at 960x540 / 15 fps to check timing quickly. Needs Pillow, ffmpeg and macOS.
"""
import argparse
import math
import os
import random
import shutil
import subprocess
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

# ---- Script ------------------------------------------------------------------------------------------------
# (id, words for the voice, words for the subtitles). The voice text spells out what TTS mispronounces.
SCRIPT = [
    ("hook",
     "Your Compose Multiplatform app speaks eight languages. That's eight strings dot X M L files in every module, and one broken placeholder away from a bug in production.",
     "Your Compose Multiplatform app speaks eight languages. That's eight strings.xml files in every module, and one broken placeholder away from a bug in production."),
    ("intro",
     "Meet K M P Translations Hub. Every key, and every locale, in one table.",
     "Meet KMP Translations Hub. Every key, and every locale, in one table."),
    ("table",
     "Missing translations are marked right where they are. Edit any cell, and only that line of X M L changes. Your comments and formatting stay exactly as they were.",
     "Missing translations are marked right where they are. Edit any cell, and only that line of XML changes. Your comments and formatting stay exactly as they were."),
    ("plurals",
     "Plurals follow each language's rules. Polish needs four forms, Arabic six, Japanese just one. The side panel gives you exactly the fields each language needs.",
     "Plurals follow each language's rules. Polish needs four forms, Arabic six, Japanese just one. The side panel gives you exactly the fields each language needs."),
    ("checks",
     "The same checks run in your X M L files: placeholders that don't match, missing plural forms, untranslated text, and keys no code uses. With quick fixes.",
     "The same checks run in your XML files: placeholders that don't match, missing plural forms, untranslated text, and keys no code uses. With quick fixes."),
    ("translate",
     "When you're ready, Translate Changes sends only what's missing or outdated, to every locale at once. Placeholders are protected, and anything a person wrote is never overwritten.",
     "When you're ready, Translate Changes sends only what's missing or outdated, to every locale at once. Placeholders are protected, and anything a person wrote is never overwritten."),
    ("services",
     "Use Apple's on-device translation, or My Memory, with no key at all. Or bring your own Deep L, Google, Claude or Open A I key.",
     "Use Apple's on-device translation, or MyMemory, with no key at all. Or bring your own DeepL, Google, Claude or OpenAI key."),
    ("extract",
     "Found hard-coded text? Press Alt Enter, and it moves into strings dot X M L, with the call and the imports written for you.",
     "Found hardcoded text? Press Alt+Enter, and it moves into strings.xml, with the call and the imports written for you."),
    ("locales",
     "Add ten languages in one go. And with K M P Device Hub, run your app in any of them, on an Android device or an iOS simulator.",
     "Add ten languages in one go. And with KMP Device Hub, run your app in any of them, on an Android device or an iOS simulator."),
    ("outro",
     "K M P Translations Hub. Free to start, with a free Pro trial, on JetBrains Marketplace.",
     "KMP Translations Hub. Free to start, with a free Pro trial, on JetBrains Marketplace."),
]
LEAD_IN, TAIL = 0.45, 0.75  # silence before and after each line, in seconds

# ---- Settings filled in by main() and passed to workers ----------------------------------------------------------
CFG = {}


def font(size, bold=False):
    f = ImageFont.truetype("/System/Library/Fonts/SFNS.ttf", int(size))
    try:
        f.set_variation_by_name("Bold" if bold else "Regular")
    except Exception:
        pass
    return f


def ease(t):
    """Smooth in-out, 0..1."""
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def ease_out(t):
    t = max(0.0, min(1.0, t))
    return 1 - (1 - t) ** 3


def lerp(a, b, t):
    return a + (b - a) * t


# ---- 3D: cards in front of a pinhole camera at the origin, looking down +z ------------------------------------------

def rotate(p, yaw, pitch, roll):
    """Rotate point p (x, y, z) by yaw (around y), pitch (around x), roll (around z), degrees."""
    x, y, z = p
    for axis, deg in (("z", roll), ("x", pitch), ("y", yaw)):
        a = math.radians(deg)
        c, s = math.cos(a), math.sin(a)
        if axis == "y":
            x, z = x * c + z * s, -x * s + z * c
        elif axis == "x":
            y, z = y * c - z * s, y * s + z * c
        else:
            x, y = x * c - y * s, x * s + y * c
    return x, y, z


def project(p, W, H, focal):
    x, y, z = p
    z = max(z, 1.0)
    return (W / 2 + focal * x / z, H / 2 - focal * y / z)


def solve(a, b):
    """Gaussian elimination for the 8x8 perspective system (no numpy needed)."""
    n = len(b)
    m = [row[:] + [b[i]] for i, row in enumerate(a)]
    for col in range(n):
        piv = max(range(col, n), key=lambda r: abs(m[r][col]))
        m[col], m[piv] = m[piv], m[col]
        pv = m[col][col]
        if abs(pv) < 1e-12:
            return None
        for r in range(n):
            if r != col:
                f = m[r][col] / pv
                if f:
                    for k in range(col, n + 1):
                        m[r][k] -= f * m[col][k]
    return [m[i][n] / m[i][i] for i in range(n)]


def perspective_coeffs(dst, src):
    """PIL PERSPECTIVE coefficients mapping output points dst[i] back to input points src[i]."""
    a, b = [], []
    for (x, y), (u, v) in zip(dst, src):
        a.append([x, y, 1, 0, 0, 0, -u * x, -u * y]); b.append(u)
        a.append([0, 0, 0, x, y, 1, -v * x, -v * y]); b.append(v)
    return solve(a, b)


def draw_card(canvas, img, center, width, yaw=0, pitch=0, roll=0, alpha=1.0, shadow=True, glow=None, blur=0.0):
    """Draws [img] as a flat card of [width] world units at [center] (x, y, z), rotated, with a soft shadow.
    [blur] (px at 1080p) softens cards behind the focus, like a camera's depth of field."""
    if alpha <= 0.01:
        return
    W, H = canvas.size
    focal = CFG["focal"] * W / 1920
    w = width
    h = width * img.size[1] / img.size[0]
    corners = [(-w / 2, h / 2, 0), (w / 2, h / 2, 0), (w / 2, -h / 2, 0), (-w / 2, -h / 2, 0)]
    pts = []
    for c in corners:
        r = rotate(c, yaw, pitch, roll)
        pts.append(project((r[0] + center[0], r[1] + center[1], r[2] + center[2]), W, H, focal))
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    x0, y0 = int(math.floor(min(xs))) - 2, int(math.floor(min(ys))) - 2
    x1, y1 = int(math.ceil(max(xs))) + 2, int(math.ceil(max(ys))) + 2
    if x1 <= 0 or y1 <= 0 or x0 >= W or y0 >= H or x1 - x0 < 4 or y1 - y0 < 4:
        return
    # Downscale the source to roughly its on-screen size first: sharper and much faster than warping a 3200 px image.
    target_w = max(8, int(max(math.dist(pts[0], pts[1]), math.dist(pts[3], pts[2])) * 1.25))
    src = img if img.size[0] <= target_w else img.resize((target_w, max(2, int(target_w * img.size[1] / img.size[0]))), Image.LANCZOS)
    pad = 3
    padded = Image.new("RGBA", (src.size[0] + pad * 2, src.size[1] + pad * 2), (0, 0, 0, 0))
    padded.paste(src, (pad, pad))
    sw, sh = src.size
    src_pts = [(pad, pad), (pad + sw, pad), (pad + sw, pad + sh), (pad, pad + sh)]
    local = [(p[0] - x0, p[1] - y0) for p in pts]
    coeffs = perspective_coeffs(local, src_pts)
    if coeffs is None:
        return
    size = (x1 - x0, y1 - y0)
    warped = padded.transform(size, Image.PERSPECTIVE, coeffs, Image.BICUBIC)
    if blur > 0:
        warped = warped.filter(ImageFilter.GaussianBlur(blur * W / 1920))
    if alpha < 1:
        a = warped.getchannel("A").point(lambda v: int(v * alpha))
        warped.putalpha(a)
    if shadow:
        blur = max(6, int(size[0] * 0.03))
        sh_layer = Image.new("RGBA", (size[0] + blur * 4, size[1] + blur * 4), (0, 0, 0, 0))
        color = glow if glow else (0, 0, 0, 150)
        color = color[:3] + (int(color[3] * alpha),)
        ImageDraw.Draw(sh_layer).polygon([(x + blur * 2, y + blur * 2) for x, y in local], fill=color)
        sh_layer = sh_layer.filter(ImageFilter.GaussianBlur(blur))
        canvas.alpha_composite(sh_layer, (x0 - blur * 2, y0 - blur * 2 + int(blur * 0.8)))
    canvas.alpha_composite(warped, (x0, y0))


# ---- Assets -------------------------------------------------------------------------------------------------------

def rounded(img, r):
    m = Image.new("L", img.size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, img.size[0] - 1, img.size[1] - 1), r, fill=255)
    out = Image.new("RGBA", img.size)
    out.paste(img.convert("RGBA"), (0, 0), m)
    return out


def window(panel, title, bar=64):
    """Dark IDE-style title bar over a panel render, rounded."""
    w, h = panel.size
    frame = Image.new("RGB", (w, h + bar), (43, 45, 48))
    d = ImageDraw.Draw(frame)
    for i, c in enumerate([(255, 95, 87), (254, 188, 46), (40, 200, 64)]):
        d.ellipse((bar * 0.37 + i * bar * 0.53, bar * 0.34, bar * 0.68 + i * bar * 0.53, bar * 0.65), fill=c)
    d.text((w // 2, bar // 2), title, fill=(200, 202, 210), font=font(bar * 0.42, True), anchor="mm")
    frame.paste(panel.convert("RGB"), (0, bar))
    return rounded(frame, 22)


def crop(img, box):
    w, h = img.size
    return img.crop((int(box[0] * w), int(box[1] * h), int(box[2] * w), int(box[3] * h)))


def load_assets(renders: Path, docs: Path):
    r = lambda n: Image.open(renders / f"{n}.png").convert("RGB")
    a = {
        "icon": Image.open(renders / "plugin-icon.png").convert("RGBA"),
        "table": window(crop(r("table"), (0, 0, 0.72, 1)), "strings.xml · Translations"),
        "table_full": window(r("table"), "strings.xml · Translations"),
        "side": window(crop(r("plurals"), (0.72, 0.085, 1, 0.62)), "cart_items · pl"),
        "plurals": window(r("plurals"), "strings.xml · Translations"),
        "missing": window(crop(r("missing"), (0, 0, 0.72, 1)), "Missing translations"),
        "dialog": window(crop(r("translate-dialog"), (0, 0, 1, 0.82)), "Translate Changes"),
        "settings": window(crop(r("settings"), (0, 0, 1, 0.72)), "Settings · KMP Translations Hub"),
        "before": window(crop(r("extract-before"), (0, 0, 0.62, 1)), "Empty.kt"),
        "after": window(crop(r("extract-after"), (0, 0, 0.68, 1)), "Empty.kt · after Alt+Enter"),
        "inspection": window(r("inspection"), "values-de/strings.xml"),
        "locales": window(r("add-locales"), "Add Locales"),
    }
    # The checks screenshot already carries the inspection callouts: use its card area.
    checks = Image.open(docs / "screenshots" / "04-checks.png").convert("RGB")
    a["checks"] = rounded(crop(checks, (0.04, 0.255, 0.96, 0.79)), 22)
    return a


def background(W, H):
    """Deep navy-violet with soft glows and a faint starfield, as in the banner."""
    img = Image.new("RGB", (W, H), (14, 11, 38))
    glow = Image.new("RGB", (W, H), (0, 0, 0))
    d = ImageDraw.Draw(glow)
    s = W / 1920
    for cx, cy, rr, c in [(560, 380, 620, (46, 32, 110)), (1450, 520, 700, (30, 26, 92)), (960, 1120, 900, (6, 6, 16))]:
        d.ellipse(((cx - rr) * s, (cy - rr) * s, (cx + rr) * s, (cy + rr) * s), fill=c)
    return ImageChops.add(img, glow.filter(ImageFilter.GaussianBlur(220 * s))).convert("RGBA")


STARS = [(random.Random(7 + i).random(), random.Random(99 + i).random(), random.Random(500 + i).random()) for i in range(260)]


def stars(canvas, t):
    """Stars drifting at three depths: parallax that sells the camera moving through space."""
    W, H = canvas.size
    d = ImageDraw.Draw(canvas)
    for x, y, depth in STARS:
        speed = 6 + depth * 26
        px = (x * W - t * speed * W / 1920) % W
        py = y * H
        b = int(70 + depth * 120)
        size = 1 + (depth > 0.75) * (W / 1920)
        d.ellipse((px, py, px + size, py + size), fill=(b, b, min(255, b + 30), 255))


def text(canvas, xy, s, size, alpha=1.0, bold=False, color=(255, 255, 255), anchor="la"):
    if alpha <= 0.01:
        return
    W = canvas.size[0]
    k = W / 1920
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).text((xy[0] * k, xy[1] * k), s, font=font(size * k, bold), fill=color + (int(255 * alpha),), anchor=anchor)
    canvas.alpha_composite(layer)


def scrim(canvas, center, size, alpha):
    """A soft dark oval behind text that sits over busy cards."""
    if alpha <= 0.01:
        return
    k = canvas.size[0] / 1920
    w, h = int(size[0] * k), int(size[1] * k)
    layer = Image.new("RGBA", (w * 2, h * 2), (0, 0, 0, 0))
    ImageDraw.Draw(layer).ellipse((w // 2, h // 2, w // 2 + w, h // 2 + h), fill=(8, 6, 24, int(230 * alpha)))
    layer = layer.filter(ImageFilter.GaussianBlur(70 * k))
    canvas.alpha_composite(layer, (int(center[0] * k - w), int(center[1] * k - h)))


def headline(canvas, title, sub, t, dur):
    """Top-left title and subtitle that slide in and fade out with the scene."""
    a = ease_out(t / 0.6) * (1 - ease((t - (dur - 0.45)) / 0.45))
    dy = (1 - ease_out(t / 0.6)) * 30
    text(canvas, (120, 92 + dy), title, 64, a, True)
    if sub:
        text(canvas, (122, 176 + dy), sub, 32, a * 0.9, color=(200, 205, 235))


def fade(t, dur, inn=0.5, out=0.45):
    return ease(t / inn) * (1 - ease((t - (dur - out)) / out))


# ---- Scenes: each draws frame time t (seconds into the scene) of a scene lasting dur ------------------------------

def scene_hook(c, A, t, dur):
    # A swarm of strings.xml windows drifting toward the camera.
    rnd = random.Random(3)
    for i in range(9):
        x = rnd.uniform(-1.6, 1.6); y = rnd.uniform(-0.9, 0.9); z0 = rnd.uniform(3.2, 7.5)
        z = z0 - t * 0.35
        yaw = rnd.uniform(-35, 35) + t * rnd.uniform(-6, 6)
        draw_card(c, A["inspection"], (x, y, z), 1.25, yaw=yaw, pitch=rnd.uniform(-12, 12), roll=rnd.uniform(-6, 6),
                  alpha=fade(t, dur) * 0.6, glow=(127, 82, 255, 60), blur=lerp(1.0, 5.0, min(1.0, (z - 2.0) / 5.0)))
    a = fade(t, dur, 0.8)
    scrim(c, (960, 515), (1500, 330), a * 0.85)
    text(c, (960, 470), "8 languages. 8 strings.xml per module.", 66, a, True, anchor="mm")
    text(c, (960, 560), "One broken placeholder away from a bug.", 44, a * ease((t - 2.2) / 0.8), color=(255, 196, 120), anchor="mm")


def scene_intro(c, A, t, dur):
    a = fade(t, dur)
    k = c.size[0] / 1920
    icon_size = int(150 * k * (0.85 + 0.15 * ease_out(t / 0.8)))
    icon = A["icon"].resize((icon_size, icon_size), Image.LANCZOS)
    if a < 1:
        icon.putalpha(icon.getchannel("A").point(lambda v: int(v * a)))
    c.alpha_composite(icon, (int(960 * k - icon_size / 2), int(250 * k - icon_size / 2)))
    text(c, (960, 400), "KMP Translations Hub", 92, a, True, anchor="mm")
    text(c, (960, 480), "Every key, every locale, one table.", 40, a * ease((t - 0.8) / 0.6), color=(200, 205, 235), anchor="mm")
    # The table rises from below, tilted back.
    rise = ease_out((t - 1.0) / 1.6)
    draw_card(c, A["table"], (0, lerp(-2.4, -1.05, rise), 4.0), 3.3, pitch=lerp(-35, -24, rise), alpha=a * rise, glow=(127, 82, 255, 110))


def scene_table(c, A, t, dur):
    headline(c, "Every key, every locale", "Missing translations marked where they are. Only the edited line changes.", t, dur)
    p = ease(t / dur)
    draw_card(c, A["table_full"], (lerp(0.25, -0.1, p), -0.3, lerp(3.4, 2.9, p)), 3.7, yaw=lerp(-16, -7, p), pitch=lerp(6, 3, p),
              alpha=fade(t, dur), glow=(127, 82, 255, 110))


def pop_in(t, start=0.5, length=0.9):
    return ease_out((t - start) / length)


def scene_plurals(c, A, t, dur):
    headline(c, "Plural forms per language", "Polish 4 · Arabic 6 · Japanese 1. Exactly the fields each one needs.", t, dur)
    p = ease(t / dur)
    a = fade(t, dur)
    draw_card(c, A["plurals"], (-0.55, -0.3, 4.4), 3.3, yaw=22, pitch=4, alpha=a * 0.7, glow=(30, 136, 229, 70), blur=2.2 * pop_in(t))
    pop = ease_out((t - 0.5) / 0.9)
    draw_card(c, A["side"], (lerp(1.6, 0.75, pop), -0.2, lerp(3.2, 2.5, p)), 1.45, yaw=lerp(-30, -14, pop), pitch=3,
              alpha=a * pop, glow=(127, 82, 255, 150))


def scene_checks(c, A, t, dur):
    headline(c, "Problems where you write them", "Placeholders, plural forms, untranslated text, unused keys. With quick fixes.", t, dur)
    p = ease(t / dur)
    draw_card(c, A["checks"], (0.05, -0.3, lerp(3.3, 2.95, p)), 3.35, yaw=lerp(7, 2, p), pitch=lerp(10, 5, p), alpha=fade(t, dur), glow=(240, 160, 30, 90))


def scene_translate(c, A, t, dur):
    headline(c, "Translate only what changed", "Every locale at once. Placeholders protected. A person's text never overwritten.", t, dur)
    p = ease(t / dur)
    a = fade(t, dur)
    pop = ease_out((t - 1.2) / 1.0)
    draw_card(c, A["missing"], (-0.6, -0.3, 4.0), 3.0, yaw=18, pitch=4, alpha=a, glow=(127, 82, 255, 90), blur=1.8 * pop)
    draw_card(c, A["dialog"], (lerp(1.9, 0.95, pop), lerp(-0.6, -0.35, pop), lerp(3.3, 2.7, p)), 1.55, yaw=lerp(-28, -12, pop), pitch=4,
              alpha=a * pop, glow=(30, 136, 229, 140))


def scene_services(c, A, t, dur):
    headline(c, "Bring your own key, or none", "", t, dur)
    a = fade(t, dur)
    p = ease(t / dur)
    draw_card(c, A["settings"], (-0.75, -0.35, lerp(3.6, 3.2, p)), 2.2, yaw=20, pitch=4, alpha=a, glow=(127, 82, 255, 90))
    chips = [("Apple on-device", "no key", (61, 220, 132)), ("MyMemory", "no key", (61, 220, 132)), ("DeepL", "your key", (160, 140, 255)),
             ("Google Cloud", "your key", (160, 140, 255)), ("Claude", "your key", (160, 140, 255)), ("OpenAI", "your key", (160, 140, 255))]
    k = c.size[0] / 1920
    d = ImageDraw.Draw(c)
    for i, (name, kind, color) in enumerate(chips):
        ca = a * ease_out((t - 0.6 - i * 0.35) / 0.5)
        if ca <= 0.01:
            continue
        x, y = 1190 * k, (330 + i * 92) * k
        dx = (1 - ca) * 40 * k
        layer = Image.new("RGBA", c.size, (0, 0, 0, 0))
        ld = ImageDraw.Draw(layer)
        ld.rounded_rectangle((x + dx, y, x + dx + 560 * k, y + 70 * k), 35 * k, fill=(255, 255, 255, int(22 * ca)), outline=color + (int(200 * ca),), width=max(1, int(2 * k)))
        ld.text((x + dx + 32 * k, y + 35 * k), name, font=font(32 * k, True), fill=(255, 255, 255, int(255 * ca)), anchor="lm")
        ld.text((x + dx + 528 * k, y + 35 * k), kind, font=font(26 * k), fill=color + (int(255 * ca),), anchor="rm")
        c.alpha_composite(layer)


def scene_extract(c, A, t, dur):
    headline(c, "Extract hardcoded strings", "One Alt+Enter: strings.xml, the call and the imports.", t, dur)
    a = fade(t, dur)
    flip = ease((t - dur * 0.45) / 0.9)
    # The card turns around its vertical axis: "before" on the front, "after" on the back.
    yaw = lerp(-8, 172, flip)
    img = A["before"] if yaw < 90 else A["after"]
    shown_yaw = yaw if yaw < 90 else yaw - 180
    draw_card(c, img, (0, -0.25, 3.0), 2.7, yaw=shown_yaw, pitch=4, alpha=a, glow=(127, 82, 255, 110))


def scene_locales(c, A, t, dur):
    headline(c, "Ten languages in one go", "And run your app in any of them with KMP Device Hub.", t, dur)
    a = fade(t, dur)
    p = ease(t / dur)
    draw_card(c, A["locales"], (-0.55, -0.35, lerp(3.4, 3.0, p)), 1.6, yaw=16, pitch=4, alpha=a, glow=(127, 82, 255, 110))
    flags = ["es", "fr", "de", "pt-BR", "it", "ja", "ko", "zh-Hans", "ar", "hi"]
    k = c.size[0] / 1920
    for i, tag in enumerate(flags):
        ca = a * ease_out((t - 0.5 - i * 0.18) / 0.45)
        if ca <= 0.01:
            continue
        col, row = i % 2, i // 2
        x, y = (1120 + col * 300) * k, (300 + row * 104) * k
        layer = Image.new("RGBA", c.size, (0, 0, 0, 0))
        ld = ImageDraw.Draw(layer)
        ld.rounded_rectangle((x, y, x + 270 * k, y + 78 * k), 20 * k, fill=(127, 82, 255, int(70 * ca)), outline=(200, 190, 255, int(160 * ca)), width=max(1, int(2 * k)))
        ld.text((x + 135 * k, y + 39 * k), "values-" + tag, font=font(30 * k, True), fill=(255, 255, 255, int(255 * ca)), anchor="mm")
        c.alpha_composite(layer)


def scene_outro(c, A, t, dur):
    a = fade(t, dur, 0.6, 0.6)
    k = c.size[0] / 1920
    size = int(170 * k)
    icon = A["icon"].resize((size, size), Image.LANCZOS)
    if a < 1:
        icon.putalpha(icon.getchannel("A").point(lambda v: int(v * a)))
    c.alpha_composite(icon, (int(960 * k - size / 2), int(330 * k - size / 2)))
    text(c, (960, 500), "KMP Translations Hub", 92, a, True, anchor="mm")
    text(c, (960, 580), "Free to start · Pro free trial", 40, a * ease((t - 0.6) / 0.6), color=(200, 205, 235), anchor="mm")
    text(c, (960, 690), "On JetBrains Marketplace", 44, a * ease((t - 1.2) / 0.6), True, color=(160, 140, 255), anchor="mm")


SCENES = {
    "hook": scene_hook, "intro": scene_intro, "table": scene_table, "plurals": scene_plurals, "checks": scene_checks,
    "translate": scene_translate, "services": scene_services, "extract": scene_extract, "locales": scene_locales, "outro": scene_outro,
}

# ---- Rendering --------------------------------------------------------------------------------------------------

_A = None
_BG = None


def _init(cfg):
    global _A, _BG
    CFG.update(cfg)
    _A = load_assets(Path(cfg["renders"]), Path(cfg["docs"]))
    _BG = background(cfg["W"], cfg["H"])


def render_frame(job):
    index, scene, t, dur, global_t, out = job
    c = _BG.copy()
    stars(c, global_t)
    SCENES[scene](c, _A, t, dur)
    c.convert("RGB").save(out / f"f{index:05d}.png", compress_level=1)
    return index


def say(text, voice, rate, path: Path):
    cmd = ["say", "-r", str(rate), "-o", str(path)]
    if voice:
        cmd[1:1] = ["-v", voice]
    subprocess.run(cmd + [text], check=True)


def duration(path: Path) -> float:
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(path)],
                         capture_output=True, text=True, check=True).stdout
    return float(out.strip())


def srt_time(s):
    ms = int(round(s * 1000))
    return f"{ms // 3600000:02d}:{ms // 60000 % 60:02d}:{ms // 1000 % 60:02d},{ms % 1000:03d}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("renders"); ap.add_argument("docs"); ap.add_argument("out")
    ap.add_argument("--voice", default="Samantha")
    ap.add_argument("--rate", type=int, default=178)
    ap.add_argument("--draft", action="store_true")
    ap.add_argument("--only", help="render just this scene, for checking")
    args = ap.parse_args()

    out = Path(args.out); out.mkdir(parents=True, exist_ok=True)
    work = out / "work"
    if work.exists():
        shutil.rmtree(work)
    (work / "frames").mkdir(parents=True)
    W, H, fps = (960, 540, 15) if args.draft else (1920, 1080, 30)
    cfg = {"renders": args.renders, "docs": args.docs, "W": W, "H": H, "focal": 1500}

    # Narration first: each scene lasts as long as its line plus a little air.
    timeline, start = [], 0.0
    for sid, spoken, caption in SCRIPT:
        if args.only and sid != args.only:
            continue
        wav = work / f"{sid}.aiff"
        say(spoken, args.voice, args.rate, wav)
        dur = LEAD_IN + duration(wav) + TAIL
        timeline.append((sid, start, dur, wav, caption))
        start += dur
    total = start
    print(f"{len(timeline)} scenes, {total:.1f} s, voice {args.voice!r}")

    jobs, i = [], 0
    for sid, s0, dur, _, _ in timeline:
        for k in range(int(round(dur * fps))):
            jobs.append((i, sid, k / fps, dur, s0 + k / fps, work / "frames"))
            i += 1
    with ProcessPoolExecutor(max_workers=os.cpu_count(), initializer=_init, initargs=(cfg,)) as pool:
        for n, _ in enumerate(pool.map(render_frame, jobs, chunksize=8)):
            if n % (fps * 5) == 0:
                print(f"  frame {n}/{len(jobs)}", flush=True)

    # Audio: each line delayed to its scene start, mixed, gently normalized.
    inputs, filters = [], []
    for n, (sid, s0, dur, wav, _) in enumerate(timeline):
        inputs += ["-i", str(wav)]
        delay = int((s0 + LEAD_IN) * 1000)
        filters.append(f"[{n}:a]aresample=48000,adelay={delay}|{delay}[a{n}]")
    mix = "".join(f"[a{n}]" for n in range(len(timeline)))
    filters.append(f"{mix}amix=inputs={len(timeline)}:normalize=0,apad=whole_dur={total:.3f},loudnorm=I=-16:TP=-1.5:LRA=11[aout]")
    audio = work / "narration.m4a"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", *inputs, "-filter_complex", ";".join(filters), "-map", "[aout]",
                    "-c:a", "aac", "-b:a", "192k", "-t", f"{total:.3f}", str(audio)], check=True)

    name = "kmp-translations-hub" + ("-draft" if args.draft else "") + (f"-{args.only}" if args.only else "")
    video = out / f"{name}.mp4"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-framerate", str(fps), "-i", str(work / "frames" / "f%05d.png"),
                    "-i", str(audio), "-c:v", "libx264", "-preset", "slow", "-crf", "18", "-pix_fmt", "yuv420p",
                    "-movflags", "+faststart", "-c:a", "copy", "-shortest", str(video)], check=True)

    # Subtitles for YouTube.
    with open(out / f"{name}.srt", "w") as f:
        for n, (sid, s0, dur, wav, caption) in enumerate(timeline, 1):
            a = s0 + LEAD_IN
            f.write(f"{n}\n{srt_time(a)} --> {srt_time(a + duration(wav))}\n{caption}\n\n")

    if not args.draft and not args.only:
        # Thumbnail: the intro's last moment, with a bigger title.
        _init(cfg)
        thumb = _BG.copy()
        stars(thumb, 3)
        scene_intro(thumb, _A, 3.0, 99)
        thumb.convert("RGB").resize((1280, 720), Image.LANCZOS).save(out / "thumbnail.png")
    shutil.rmtree(work)
    print("wrote", video, f"({total:.1f} s)")


if __name__ == "__main__":
    main()
