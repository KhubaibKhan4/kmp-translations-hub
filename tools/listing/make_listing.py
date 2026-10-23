"""KMP Translations Hub: README / Marketplace screenshots (2560x1600) and the banner (1920x1080), in the KMP Device
Hub style, from the plugin's own panel renders.

    ./gradlew :idea-plugin:test --tests '*ListingRender*'
    python3 tools/listing/make_listing.py idea-plugin/build/listing docs

Writes docs/banner.png and docs/screenshots/*.png. Needs Pillow and macOS system fonts.
"""
import random
import sys
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

RENDERS, OUT = Path(sys.argv[1]), Path(sys.argv[2])
SHOTS = OUT / "screenshots"
SHOTS.mkdir(parents=True, exist_ok=True)
SF = "/System/Library/Fonts/SFNS.ttf"
HELVETICA = "/System/Library/Fonts/HelveticaNeue.ttc"


def sf(size, bold=False):
    f = ImageFont.truetype(SF, size)
    try:
        f.set_variation_by_name("Bold" if bold else "Regular")
    except Exception:
        pass
    return f


def helvetica(size, bold=False):
    return ImageFont.truetype(HELVETICA, size, index=1 if bold else 0)


def render(name, box=None):
    """A panel render; [box] crops it to (left, top, right, bottom) fractions, to show the part that matters."""
    img = Image.open(RENDERS / f"{name}.png").convert("RGB")
    if box:
        w, h = img.size
        img = img.crop((int(box[0] * w), int(box[1] * h), int(box[2] * w), int(box[3] * h)))
    return img


# The Translations tab is table (left 72%) + side panel (right 28%).
TABLE = (0, 0, 0.72, 1)
SIDE = (0.72, 0.085, 1, 0.62)


def rounded(img, r):
    m = Image.new("L", img.size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, img.size[0] - 1, img.size[1] - 1), r, fill=255)
    out = Image.new("RGBA", img.size)
    out.paste(img.convert("RGBA"), (0, 0), m)
    return out


def card(canvas, img, xy, r=24, shadow=40, glow=(0, 0, 0, 150)):
    c = rounded(img, r)
    sh = Image.new("RGBA", (c.size[0] + shadow * 2, c.size[1] + shadow * 2), (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle((shadow, shadow, shadow + c.size[0], shadow + c.size[1]), r, fill=glow)
    canvas.alpha_composite(sh.filter(ImageFilter.GaussianBlur(shadow / 2)), (xy[0] - shadow, xy[1] - shadow + 14))
    canvas.alpha_composite(c, xy)


def fit(img, mw, mh):
    s = min(mw / img.size[0], mh / img.size[1])
    return img.resize((int(img.size[0] * s), int(img.size[1] * s)), Image.LANCZOS)


def window(panel, title, bar=64, dark=False):
    """A minimal IDE window frame around a panel render."""
    w, h = panel.size
    frame = Image.new("RGB", (w, h + bar), (43, 45, 48) if dark else (247, 248, 250))
    d = ImageDraw.Draw(frame)
    d.rectangle((0, 0, w, bar), fill=(43, 45, 48) if dark else (235, 236, 240))
    for i, c in enumerate([(255, 95, 87), (254, 188, 46), (40, 200, 64)]):
        d.ellipse((bar * 0.37 + i * bar * 0.53, bar * 0.34, bar * 0.68 + i * bar * 0.53, bar * 0.65), fill=c)
    d.text((w // 2, bar // 2), title, fill=(200, 202, 210) if dark else (60, 63, 70), font=sf(int(bar * 0.44), True), anchor="mm")
    frame.paste(panel, (0, bar))
    return frame


# ---- Screenshots --------------------------------------------------------------------------------------------

W, H = 2560, 1600


def gradient_background(w, h):
    """Diagonal gradient in the plugin icon's colors, darkened for contrast (as KMP Device Hub's)."""
    top, mid, bottom = (40, 22, 92), (26, 30, 88), (10, 44, 86)
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(0, w, 4):
            t = x / w * 0.45 + y / h * 0.55
            a, b, u = (top, mid, t / 0.55) if t < 0.55 else (mid, bottom, (t - 0.55) / 0.45)
            c = tuple(int(a[i] + (b[i] - a[i]) * u) for i in range(3))
            for dx in range(4):
                if x + dx < w:
                    px[x + dx, y] = c
    return img


BG = gradient_background(W, H)
ICON = Image.open(RENDERS / "plugin-icon.png").convert("RGBA")


def screenshot(name, title, subtitle, place):
    canvas = BG.copy().convert("RGBA")
    d = ImageDraw.Draw(canvas)
    d.text((140, 120), title, fill=(255, 255, 255), font=sf(88, True))
    d.text((140, 240), subtitle, fill=(200, 205, 235), font=sf(44))
    icon = rounded(ICON.resize((88, 88), Image.LANCZOS), 20)
    canvas.alpha_composite(icon, (W - 140 - 88, 116))
    d.text((W - 140 - 88 - 24, 160), "KMP Translations Hub", fill=(225, 228, 245), font=sf(40, True), anchor="rm")
    place(canvas)
    canvas.convert("RGB").save(SHOTS / name, optimize=True)
    print("wrote", SHOTS / name)


def centered(img, top=380, bottom=110, side=140):
    def place(canvas):
        shot = fit(img, W - 2 * side, H - top - bottom)
        card(canvas, shot, ((W - shot.size[0]) // 2, top + (H - top - bottom - shot.size[1]) // 2))
    return place


def label(canvas, xy, text):
    ImageDraw.Draw(canvas).text(xy, text, fill=(200, 205, 235), font=sf(38, True))


screenshot("01-translations-table.png", "Every key, every locale, one table",
           "Missing translations and problems marked where they are. Edits keep your XML as it was.",
           centered(window(render("table"), "strings.xml · Translations")))

screenshot("02-plurals.png", "The plural forms each language needs",
           "Polish gets one, few, many and other; Japanese just other. Arrays and plurals edit side by side.",
           centered(window(render("plurals"), "strings.xml · Translations")))


def translate_place(canvas):
    table = fit(window(render("missing", TABLE), "Missing translations"), 1500, 1000)
    dialog = fit(window(render("translate-dialog"), "Translate Changes"), 1040, 900)
    card(canvas, table, (140, 400))
    card(canvas, dialog, (W - 140 - dialog.size[0], 400 + table.size[1] - dialog.size[1] // 2))


screenshot("03-translate-changes.png", "Translate only what changed",
           "Only missing and outdated strings are sent, every locale at once, placeholders protected.",
           translate_place)


def callout(canvas, x, y, text, width):
    """A hover-style popup with an inspection's message, pointing left at (x, y)."""
    f = sf(34)
    d = ImageDraw.Draw(canvas)
    pad, h = 26, 76
    w = int(d.textlength(text, font=f)) + pad * 2 + 52
    box = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    bd = ImageDraw.Draw(box)
    bd.rounded_rectangle((0, 0, w - 1, h - 1), 14, fill=(255, 250, 232), outline=(230, 196, 92), width=3)
    # Warning triangle, drawn (no emoji font in Pillow).
    bd.polygon([(pad + 16, 18), (pad + 34, 54), (pad - 2, 54)], fill=(240, 160, 30))
    bd.text((pad + 16, 47), "!", fill=(255, 255, 255), font=sf(24, True), anchor="ms")
    bd.text((pad + 52, h // 2), text, fill=(60, 50, 20), font=f, anchor="lm")
    sh = Image.new("RGBA", (w + 40, h + 40), (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle((20, 20, 20 + w, 20 + h), 14, fill=(0, 0, 0, 120))
    canvas.alpha_composite(sh.filter(ImageFilter.GaussianBlur(10)), (x - 20, y - h // 2 - 14))
    d.polygon([(x - 22, y), (x + 2, y - 16), (x + 2, y + 16)], fill=(230, 196, 92))
    canvas.alpha_composite(box, (x, y - h // 2))


def checks_place(canvas):
    raw = render("inspection")
    editor = fit(window(raw, "values-de/strings.xml"), 2280, 1000)
    top = 420
    card(canvas, editor, ((W - editor.size[0]) // 2, top))
    left = (W - editor.size[0]) // 2
    scale = editor.size[0] / raw.size[0]
    # The editor's lines are 44 px apart in the 2x render, the first centred at 25 px.
    def line_y(n):
        return int(top + (64 + 25 + (n - 1) * 44) * scale)
    callout(canvas, left + int(editor.size[0] * 0.47), line_y(11), "Placeholders %1$d don't match the default text's %1$s", 0)
    callout(canvas, left + int(editor.size[0] * 0.62), line_y(14), "Same as the default text: not translated?", 0)


screenshot("04-checks.png", "Problems where you write them",
           "Placeholders that don't match, plural forms, leftover keys and unused strings, with quick fixes.",
           checks_place)


def extract_place(canvas):
    before = fit(window(render("extract-before", (0, 0, 0.62, 1)), "Empty.kt · before"), 1080, 900)
    after = fit(window(render("extract-after", (0, 0, 0.68, 1)), "Empty.kt · after Alt+Enter"), 1080, 900)
    label(canvas, (140, 390), "Hardcoded text in Compose UI")
    card(canvas, before, (140, 450))
    label(canvas, (W - 140 - after.size[0], 390), "One Alt+Enter: strings.xml, call and imports")
    card(canvas, after, (W - 140 - after.size[0], 450))
    d = ImageDraw.Draw(canvas)
    x0, x1 = 140 + before.size[0] + 14, W - 140 - after.size[0] - 14
    cy = 450 + before.size[1] // 2
    d.line([(x0, cy), (x1 - 40, cy)], fill=(160, 140, 255), width=14)
    d.polygon([(x1 - 44, cy - 30), (x1, cy), (x1 - 44, cy + 30)], fill=(160, 140, 255))


screenshot("05-extract-strings.png", "Extract hardcoded strings",
           "\"Hi $name\" becomes Hi %1$s in strings.xml and stringResource(Res.string.hi, name) in code.",
           extract_place)

screenshot("06-services.png", "Bring your own key, or none",
           "Apple on-device and MyMemory need no key. DeepL, Google Cloud, Claude and OpenAI with yours.",
           centered(window(render("settings"), "Settings · Tools · KMP Translations Hub"), top=400))


def sync_place(canvas):
    settings = fit(window(render("sync-settings", (0, 0, 0.9, 1)), "Settings · Translation Platform"), 1300, 1080)
    dialog = fit(window(render("sync-dialog", (0, 0.18, 0.82, 0.8)), "Sync with Crowdin"), 2560 - 140 * 2 - settings.size[0] - 70, 600)
    card(canvas, settings, (140, 380))
    card(canvas, dialog, (W - 140 - dialog.size[0], 380 + (settings.size[1] - dialog.size[1]) // 2))


screenshot("07-sync.png", "Sync with Crowdin, Lokalise and POEditor",
           "Default strings up, translators' work down. Changed on both sides? Nothing is overwritten.",
           sync_place)


def platforms_place(canvas):
    android = fit(window(render("android-table"), "app/src/main/res · strings.xml · Translations"), 1640, 520)
    ios = fit(window(render("catalog-table"), "iosApp/Localizable.xcstrings · Translations"), 1640, 520)
    label(canvas, (140, 360), "Android: res/values…, with Android's quote rules")
    card(canvas, android, (140, 420))
    top = 420 + android.size[1] + 90
    label(canvas, (W - 140 - ios.size[0], top - 60), "iOS: Xcode String Catalogs, changed line by line")
    card(canvas, ios, (W - 140 - ios.size[0], top))


screenshot("08-android-ios.png", "Android and iOS strings too",
           "Android res folders and Xcode String Catalogs get the same table, checks and Translate Changes.",
           platforms_place)


# ---- Banner -------------------------------------------------------------------------------------------------

BW, BH = 1920, 1080


def banner_background():
    """Deep navy-violet with soft glows and a faint starfield, like the KMP Device Hub banner."""
    img = Image.new("RGB", (BW, BH), (14, 11, 38))
    glow = Image.new("RGB", (BW, BH), (0, 0, 0))
    d = ImageDraw.Draw(glow)
    for cx, cy, r, c in [(560, 380, 620, (46, 32, 110)), (1450, 520, 700, (30, 26, 92)), (960, 1120, 900, (6, 6, 16))]:
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=c)
    img = ImageChops.add(img, glow.filter(ImageFilter.GaussianBlur(220)))
    stars = ImageDraw.Draw(img)
    rnd = random.Random(7)
    for _ in range(260):
        x, y = rnd.randrange(BW), rnd.randrange(BH)
        b = rnd.randrange(70, 190)
        s = rnd.choice([1, 1, 1, 2])
        stars.ellipse((x, y, x + s, y + s), fill=(b, b, min(255, b + 30)))
    return img.convert("RGBA")


def gradient_text(canvas, xy, text, fnt, left=(198, 180, 255), right=(240, 236, 255)):
    mask = Image.new("L", canvas.size, 0)
    ImageDraw.Draw(mask).multiline_text(xy, text, font=fnt, fill=255, spacing=6)
    x0, _, x1, _ = mask.getbbox()
    grad = Image.new("RGBA", canvas.size)
    gd = ImageDraw.Draw(grad)
    for x in range(x0, x1 + 1):
        t = (x - x0) / max(1, x1 - x0)
        gd.line((x, 0, x, BH), fill=tuple(int(left[i] + (right[i] - left[i]) * t) for i in range(3)) + (255,))
    canvas.paste(grad, (0, 0), mask)


banner = banner_background()
banner.alpha_composite(ICON.resize((108, 108), Image.LANCZOS), (96, 112))
gradient_text(banner, (92, 262), "Every locale,\none table", helvetica(104, True))
d = ImageDraw.Draw(banner)
d.multiline_text((96, 510), "Edit, check and translate your Compose\nMultiplatform, Android and iOS strings\nwithout leaving Android Studio.",
                 font=helvetica(34), fill=(205, 200, 235), spacing=12)
for i, f in enumerate([
    "Strings, plurals and arrays in every locale",
    "Placeholder and plural checks per language",
    "Translate only what changed, keys optional",
    "Extract hardcoded strings with Alt+Enter",
    "Sync with Crowdin, Lokalise and POEditor",
    "Android res and iOS String Catalogs too",
]):
    y = 712 + i * 50
    # Drawn checkmark: Helvetica has no ✓ glyph.
    d.line([(100, y + 19), (110, y + 29), (128, y + 8)], fill=(74, 222, 128), width=5, joint="curve")
    d.text((146, y + 2), f, font=helvetica(30), fill=(232, 230, 245))

table = fit(window(render("plurals", (0, 0, 0.72, 1)), "strings.xml · Translations", bar=40, dark=True), 860, 520)
side = fit(window(render("plurals", SIDE), "Polish plurals", bar=40, dark=True), 400, 320)
sync = fit(window(render("sync-dialog", (0, 0.18, 0.82, 0.8)), "Sync with Crowdin", bar=40, dark=True), 640, 320)
card(banner, table, (980, 110), r=14, shadow=36, glow=(127, 82, 255, 110))
card(banner, sync, (980, 700), r=14, shadow=36, glow=(30, 136, 229, 90))
card(banner, side, (1460, 470), r=14, shadow=36, glow=(127, 82, 255, 130))
banner.convert("RGB").save(OUT / "banner.png", optimize=True)
print("wrote", OUT / "banner.png")
