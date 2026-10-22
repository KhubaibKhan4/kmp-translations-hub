"""Three YouTube thumbnails (1280x720) for Test & Compare, each with its own hook and a matching title, in the
KMP Device Hub thumbnail style (heavy outlined type, yellow accent) with this plugin's panels as 3D cards.

    python3 tools/listing/make_thumbnails.py idea-plugin/build/listing docs dist/thumbnails

Writes thumbnail-A/B/C-*.png and titles.md (the title to pair with each).
"""
import random
import sys
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "video"))
import make_video as mv  # noqa: E402  (the 3D card renderer)

RENDERS, DOCS, OUT = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
OUT.mkdir(parents=True, exist_ok=True)
W, H = 1280, 720
mv.CFG.update({"focal": 1500, "W": W, "H": H})
HN = "/System/Library/Fonts/HelveticaNeue.ttc"
BLACK, BOLD = 9, 1  # Condensed Black, Bold
YELLOW, GREEN, WHITE, RED = (255, 214, 10), (74, 222, 128), (255, 255, 255), (255, 92, 92)
INK = (10, 8, 26)


def font(size, index=BLACK):
    return ImageFont.truetype(HN, size, index=index)


def background(c1=(46, 32, 110), c2=(14, 11, 38), glow_xy=(380, 260)):
    img = Image.new("RGB", (W, H), c2)
    glow = Image.new("RGB", (W, H), (0, 0, 0))
    gx, gy = glow_xy
    ImageDraw.Draw(glow).ellipse((gx - 520, gy - 520, gx + 520, gy + 520), fill=c1)
    img = ImageChops.add(img, glow.filter(ImageFilter.GaussianBlur(170)))
    s = ImageDraw.Draw(img)
    rnd = random.Random(3)
    for _ in range(120):
        x, y = rnd.randrange(W), rnd.randrange(H)
        b = rnd.randrange(60, 170)
        s.point((x, y), fill=(b, b, min(255, b + 20)))
    return img.convert("RGBA")


def outlined(d, xy, text, fnt, fill, stroke=6, anchor="la"):
    """Heavy text with a dark outline so it reads at phone-thumbnail size."""
    d.text(xy, text, font=fnt, fill=fill, stroke_width=stroke, stroke_fill=INK, anchor=anchor)


def icon(canvas, xy, size):
    canvas.alpha_composite(Image.open(RENDERS / "plugin-icon.png").convert("RGBA").resize((size, size), Image.LANCZOS), xy)


def chip(canvas, xy, text, fill, fg=WHITE, size=34, pad=22, outline=None):
    d = ImageDraw.Draw(canvas)
    f = font(size, BOLD)
    w = int(d.textlength(text, font=f)) + pad * 2
    h = int(size * 1.7)
    d.rounded_rectangle((xy[0], xy[1], xy[0] + w, xy[1] + h), h // 2, fill=fill, outline=outline, width=3 if outline else 0)
    d.text((xy[0] + w // 2, xy[1] + h // 2), text, font=f, fill=fg, anchor="mm")
    return w


def crop(img, box):
    return mv.crop(img, box)


render = lambda n: Image.open(RENDERS / f"{n}.png").convert("RGB")
TITLES = []

# ---- A: the product: every language, one table ------------------------------------------------------------
a = background(glow_xy=(330, 330))
table = mv.window(crop(render("table"), (0, 0, 0.72, 0.8)), "strings.xml · Translations", bar=56)
mv.draw_card(a, table, (1.2, -0.05, 3.1), 2.4, yaw=-24, pitch=6, glow=(127, 82, 255, 170))
d = ImageDraw.Draw(a)
icon(a, (64, 52), 92)
outlined(d, (60, 168), "EVERY", font(124), WHITE)
outlined(d, (60, 300), "LANGUAGE", font(112), YELLOW)
outlined(d, (62, 428), "ONE TABLE", font(104), WHITE)
w = chip(a, (64, 584), "Compose Multiplatform", (127, 82, 255))
a.convert("RGB").save(OUT / "thumbnail-A-one-table.png", optimize=True)
TITLES.append(("A", "thumbnail-A-one-table.png",
               "Manage All Your Compose Multiplatform Translations in One Table",
               "Product: what it is. Best for people searching for a tool."))

# ---- B: the problem: a translation bug, caught -------------------------------------------------------------
b = background(c1=(110, 28, 40), glow_xy=(930, 330))
# The two lines with the placeholder problem and its callout, from the checks screenshot.
# The offending line, drawn large enough to read on a phone: the German text uses %1$d where the default has %1$s,
# with the plugin's own warning under it.
MONO = "/System/Library/Fonts/Menlo.ttc"
line = Image.new("RGBA", (620, 210), (255, 255, 255, 255))
ld = ImageDraw.Draw(line)
ld.text((28, 24), '<string name="checkout_total">', font=ImageFont.truetype(MONO, 26), fill=(0, 0, 128))
ld.text((28, 66), "Gesamt: ", font=ImageFont.truetype(MONO, 46, index=1), fill=(30, 30, 30))
x = 28 + ld.textlength("Gesamt: ", font=ImageFont.truetype(MONO, 46, index=1))
ld.text((x, 66), "%1$d", font=ImageFont.truetype(MONO, 46, index=1), fill=(200, 40, 40))
end = x + ld.textlength("%1$d", font=ImageFont.truetype(MONO, 46, index=1))
for i in range(int(x), int(end), 10):  # red squiggle under the placeholder
    ld.line([(i, 124), (i + 5, 118), (i + 10, 124)], fill=(230, 40, 40), width=3)
ld.rounded_rectangle((20, 142, 600, 194), 12, fill=(255, 250, 232), outline=(230, 196, 92), width=3)
ld.polygon([(46, 150), (62, 184), (30, 184)], fill=(240, 160, 30))
ld.text((46, 180), "!", font=font(22, BOLD), fill=WHITE, anchor="ms")
ld.text((76, 168), "Placeholders %1$d don't match the default text's %1$s", font=ImageFont.truetype("/System/Library/Fonts/SFNS.ttf", 20), fill=(60, 50, 20), anchor="lm")
mv.draw_card(b, mv.rounded(line, 18), (0.98, 0.52, 3.0), 1.45, yaw=-14, pitch=4, glow=(255, 92, 92, 180))
d = ImageDraw.Draw(b)
# The bug itself, huge: %1$d where the default text has %1$s.
outlined(d, (960, 470), "%1$d ≠ %1$s", font(96), YELLOW, stroke=7, anchor="mm")
outlined(d, (960, 560), "caught before release", font(42, BOLD), WHITE, stroke=4, anchor="mm")
icon(b, (58, 52), 84)
outlined(d, (54, 160), "YOUR", font(132), WHITE)
outlined(d, (54, 296), "STRINGS", font(132), WHITE)
outlined(d, (54, 432), "HAVE BUGS", font(118), RED)
chip(b, (60, 594), "Android Studio plugin", (40, 30, 60), outline=(255, 255, 255))
b.convert("RGB").save(OUT / "thumbnail-B-bugs.png", optimize=True)
TITLES.append(("B", "thumbnail-B-bugs.png",
               "This Android Studio Plugin Finds Translation Bugs Before Your Users Do",
               "Problem: curiosity and pain. Best for browse and suggested videos."))

# ---- C: the outcome: translated, no API key ---------------------------------------------------------------
c = background(c1=(20, 70, 90), glow_xy=(950, 340))
side = mv.window(crop(render("plurals"), (0.72, 0.085, 1, 0.62)), "cart_items · pl", bar=48)
dialog = mv.window(crop(render("translate-dialog"), (0, 0, 1, 0.78)), "Translate Changes", bar=48)
mv.draw_card(c, dialog, (1.0, 0.05, 3.3), 1.75, yaw=-22, pitch=5, glow=(30, 136, 229, 160), blur=1.2)
mv.draw_card(c, side, (0.62, -0.42, 2.6), 1.15, yaw=-10, pitch=4, glow=(74, 222, 128, 150))
d = ImageDraw.Draw(c)
# A fan of locale chips: the app in many languages.
for i, (tag, x, y) in enumerate([("es", 690, 86), ("fr", 790, 64), ("de", 885, 84), ("ja", 985, 60), ("ar", 1085, 82), ("hi", 1178, 60)]):
    chip(c, (x, y), tag, (255, 255, 255), fg=INK, size=30, pad=18)
icon(c, (58, 52), 84)
outlined(d, (54, 158), "TRANSLATE", font(118), WHITE)
outlined(d, (54, 284), "YOUR APP", font(118), WHITE)
outlined(d, (56, 412), "NO API KEY", font(104), GREEN)
chip(c, (60, 572), "Kotlin Multiplatform", (127, 82, 255))
c.convert("RGB").save(OUT / "thumbnail-C-no-api-key.png", optimize=True)
TITLES.append(("C", "thumbnail-C-no-api-key.png",
               "Translate Your Kotlin Multiplatform App Without an API Key",
               "Outcome: the payoff. Best for people who already have an app to localize."))

with open(OUT / "titles.md", "w") as f:
    f.write("# YouTube Test & Compare: thumbnails and titles\n\n")
    f.write("| Variant | Thumbnail | Title | Angle |\n|---|---|---|---|\n")
    for v, file, title, why in TITLES:
        f.write(f"| {v} | `{file}` | {title} | {why} |\n")
for v, file, title, _ in TITLES:
    print(v, file, "·", title, f"({len(title)} chars)")
