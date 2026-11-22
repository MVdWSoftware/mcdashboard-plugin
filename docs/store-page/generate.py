"""Generate the SpigotMC store artwork for the MCDashboard listing.

Run from anywhere: `python3 docs/store-page/generate.py` (needs Pillow). It reads the fonts and the app
screenshots from the marketing site in the mcdashboard-server repository, next to this one, and writes:

- `assets/logo.png` (the transparent SpigotMC banner, copied from `docs/spigotmc/logo.png`)
- `assets/sec-*.png` section headers (`[IMG]` above a block of text)
- `assets/ic-*.png` 40px feature tiles, inline before a feature line
- `assets/plan-*.png` the Free / App unlock / Server licence cards
- `assets/btn-*.png` linked buttons (the FeatherBoard style: one flat brand-coloured image per link)
- `assets/app.png`, `widgets.png`, `map.png`, `add-server.png` phone collages of the site's marketing images

Pass `--publish` to also copy everything to the site (`docs/website/src/assets/store/`), which serves it at
https://mcdashboard.org/assets/store/. That is what `listing.bbcode` points at.
"""
import argparse
import shutil
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = Path(__file__).resolve().parent
SERVER = HERE.parents[2] / "mcdashboard-server"
FONTS = SERVER / "docs/website/node_modules/@fontsource"
MEDIA = SERVER / "docs/website/src/assets/media"
OUT = HERE / "assets"
PUBLISH = SERVER / "docs/website/src/assets/store"

SS = 4  # draw at 4x and downsample, so text and corners are antialiased

BRAND, BRAND_DARK = "#284cb5", "#1f3c8e"
BRAND_LIGHT, GOLD, NAVY = "#3b62d6", "#fcc419", "#0f1014"


def hexc(value):
    value = value.lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4))


def font(package, weight, size):
    path = FONTS / package / "files" / f"{package}-latin-{weight}-normal.woff"
    return ImageFont.truetype(str(path), round(size * SS))


def canvas(w, h, bg=None):
    return Image.new("RGBA", (w * SS, h * SS), hexc(bg) + (255,) if bg else (0, 0, 0, 0))


def finish(img, w, h):
    return img.resize((w, h), Image.Resampling.LANCZOS)


def vertical_gradient(w, h, top, bottom):
    mask = Image.linear_gradient("L").resize((w, h))
    return Image.composite(Image.new("RGBA", (w, h), hexc(bottom) + (255,)),
                           Image.new("RGBA", (w, h), hexc(top) + (255,)), mask)


# --- Section header: a word in Tektur with a brand rule ------------------------------------------------

def section(name, label):
    """A 560x86 transparent header: the title in dark blue with a gold-to-blue rule underneath."""
    w, h = 560, 86
    img = canvas(w, h)
    d = ImageDraw.Draw(img)
    f = font("tektur", 700, 44)
    baseline = 58 * SS
    d.text((4 * SS, baseline), label, font=f, fill=hexc(BRAND_LIGHT), anchor="ls")

    y = 68 * SS
    bar = 3 * SS
    d.rounded_rectangle([2 * SS, y, 2 * SS + 36 * SS, y + bar], radius=bar / 2, fill=hexc(GOLD))
    # gradient tail: brand blue fading out over the rest of the header
    start = 44 * SS
    end = w * SS - 2 * SS
    span = max(end - start, 1)
    for x in range(start, end):
        fade = 1 - (x - start) / span
        alpha = round(225 * fade ** 0.55)
        d.line([(x, y), (x, y + bar)], fill=hexc(BRAND) + (alpha,))
    finish(img, w, h).save(OUT / f"sec-{name}.png")


# --- Buttons ------------------------------------------------------------------------------------------

def button(name, label):
    """A 210x59 rounded brand button with a white label, one per store-page link."""
    w, h = 210, 59
    img = canvas(w, h)
    radius = 10 * SS
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w * SS - 1, h * SS - 1], radius=radius, fill=255)
    img.paste(vertical_gradient(w * SS, h * SS, BRAND_LIGHT, BRAND_DARK), (0, 0), mask)

    d = ImageDraw.Draw(img)
    d.rounded_rectangle([SS, SS, w * SS - SS, h * SS - SS], radius=radius, outline=hexc(BRAND_DARK) + (255,),
                        width=2 * SS)
    d.line([(radius, 2 * SS), (w * SS - radius, 2 * SS)], fill=(255, 255, 255, 70), width=2 * SS)

    size = 23
    while size > 14:
        f = font("inter", 700, size)
        if d.textlength(label, font=f) <= (w - 26) * SS:
            break
        size -= 1
    d.text((w * SS / 2, (h / 2 + 1) * SS), label, font=f, fill=(255, 255, 255, 255), anchor="mm")
    finish(img, w, h).save(OUT / f"btn-{name}.png")


# --- Feature icons and plan cards -----------------------------------------------------------------------

def icon(name):
    """A 40x40 brand tile with a white glyph, used inline at the start of a feature line."""
    s = 52
    img = canvas(s, s)
    radius = 12 * SS
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, s * SS - 1, s * SS - 1], radius=radius, fill=255)
    img.paste(vertical_gradient(s * SS, s * SS, BRAND_LIGHT, BRAND), (0, 0), mask)

    d = ImageDraw.Draw(img)
    white, w4 = (255, 255, 255, 240), 4 * SS
    d.rounded_rectangle([SS, SS, s * SS - SS, s * SS - SS], radius=radius, outline=hexc(BRAND_DARK) + (255,),
                        width=2 * SS)
    u = SS  # one design unit
    if name == "live":
        d.polygon([(30 * u, 8 * u), (14 * u, 30 * u), (24 * u, 30 * u), (21 * u, 44 * u), (38 * u, 22 * u),
                   (28 * u, 22 * u)], fill=white)
    elif name == "shield":
        d.polygon([(26 * u, 7 * u), (43 * u, 14 * u), (42 * u, 29 * u), (26 * u, 45 * u), (10 * u, 29 * u),
                   (9 * u, 14 * u)], fill=white)
        d.line([(19 * u, 26 * u), (24 * u, 32 * u), (35 * u, 19 * u)], fill=hexc(BRAND) + (255,), width=w4,
               joint="curve")
    elif name == "lock":
        d.arc([18 * u, 11 * u, 34 * u, 35 * u], 180, 360, fill=white, width=w4)
        d.rounded_rectangle([15 * u, 23 * u, 37 * u, 44 * u], radius=3 * u, fill=white)
        d.ellipse([23 * u, 29 * u, 29 * u, 35 * u], fill=hexc(BRAND) + (255,))
        d.rectangle([25 * u, 33 * u, 27 * u, 40 * u], fill=hexc(BRAND) + (255,))
    elif name == "chart":
        for i, (x0, y0, x1, y1) in enumerate(((11, 32, 18, 43), (23, 20, 30, 43), (35, 26, 42, 43))):
            d.rounded_rectangle([x0 * u, y0 * u, x1 * u, y1 * u], radius=2 * u,
                                fill=hexc(GOLD) + (255,) if i == 1 else white)
    elif name == "widgets":
        for i, (x0, y0) in enumerate(((9, 9), (30, 9), (9, 30), (30, 30))):
            d.rounded_rectangle([(x0) * u, (y0) * u, (x0 + 13) * u, (y0 + 13) * u], radius=3 * u,
                                fill=hexc(GOLD) + (255,) if i == 1 else white)
    elif name == "map":
        d.rounded_rectangle([8 * u, 14 * u, 44 * u, 40 * u], radius=3 * u, outline=white, width=3 * SS)
        d.line([(20 * u, 15 * u), (20 * u, 39 * u)], fill=white, width=2 * SS)
        d.line([(32 * u, 15 * u), (32 * u, 39 * u)], fill=white, width=2 * SS)
        d.ellipse([16 * u, 21 * u, 24 * u, 29 * u], fill=hexc(GOLD) + (255,))
    elif name == "layout":
        d.rounded_rectangle([8 * u, 12 * u, 44 * u, 40 * u], radius=3 * u, outline=white, width=3 * SS)
        d.line([(26 * u, 13 * u), (26 * u, 39 * u)], fill=white, width=2 * SS)
        d.line([(9 * u, 26 * u), (25 * u, 26 * u)], fill=white, width=2 * SS)
        d.rounded_rectangle([30 * u, 29 * u, 41 * u, 37 * u], radius=2 * u, fill=hexc(GOLD) + (255,))
    elif name == "network":
        for x, y in ((26, 12), (12, 38), (40, 38)):
            d.ellipse([(x - 4) * u, (y - 4) * u, (x + 4) * u, (y + 4) * u], fill=white)
        for a, b in (((26, 16), (14, 34)), ((30, 16), (38, 34)), ((16, 38), (36, 38))):
            d.line([a[0] * u, a[1] * u, b[0] * u, b[1] * u], fill=white, width=3 * SS)
    else:
        raise ValueError(name)
    finish(img, 40, 40).save(OUT / f"ic-{name}.png")


def plan(name, title, subtitle, price, unit, accent):
    """A 560x112 dark card with a plan name, audience, price and a coloured edge."""
    w, h = 560, 112
    img = canvas(w, h)
    img.paste(vertical_gradient(w * SS, h * SS, "#1b2547", NAVY), (0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 8 * SS, h * SS], fill=hexc(accent) + (255,))
    d.rounded_rectangle([SS, SS, w * SS - SS, h * SS - SS], radius=14 * SS, outline=(255, 255, 255, 34),
                        width=2 * SS)
    d.text((30 * SS, 55 * SS), title, font=font("tektur", 700, 30), fill=(255, 255, 255, 255), anchor="ls")
    d.text((30 * SS, 84 * SS), subtitle, font=font("inter", 500, 16), fill=hexc("#b8c2dc") + (255,), anchor="ls")
    d.text((528 * SS, 60 * SS), price, font=font("inter", 800, 30), fill=(255, 255, 255, 255), anchor="rs")
    d.text((528 * SS, 86 * SS), unit, font=font("inter", 500, 14), fill=hexc("#8b93ad") + (255,), anchor="rs")
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w * SS - 1, h * SS - 1], radius=14 * SS, fill=255)
    img.putalpha(mask)
    finish(img, w, h).save(OUT / f"plan-{name}.png")


# --- Phone collages ------------------------------------------------------------------------------------

def phone(img, width, radius=26):
    """A screenshot as a rounded card with a thin border and a soft shadow, at supersampled size."""
    height = round(img.height * width / img.width)
    shot = img.convert("RGB").resize((round(width * SS), round(height * SS)), Image.Resampling.LANCZOS)
    card = Image.new("RGBA", shot.size, (0, 0, 0, 0))
    mask = Image.new("L", shot.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, shot.width - 1, shot.height - 1], radius=radius * SS, fill=255)
    card.paste(shot, (0, 0), mask)
    ImageDraw.Draw(card).rounded_rectangle([SS, SS, shot.width - SS - 1, shot.height - SS - 1],
                                           radius=radius * SS, outline=(255, 255, 255, 55), width=2 * SS)
    return card, mask


def collage(name, files, width=300, gap=22, pad=26):
    """Screenshots side by side on transparent background, with the same rounding and shadow."""
    shots = [Image.open(MEDIA / f) for f in files]
    cards = [phone(im, width) for im in shots]
    card_w, card_h = cards[0][0].size
    w = (len(cards) * card_w + (len(cards) - 1) * gap * SS + 2 * pad * SS) // SS
    h = (card_h + 2 * pad * SS) // SS
    img = Image.new("RGBA", (w * SS, h * SS), (0, 0, 0, 0))

    shadow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    for i, (_, mask) in enumerate(cards):
        x = pad * SS + i * (card_w + gap * SS)
        y = pad * SS + 6 * SS
        sd.bitmap((x, y), mask, fill=(0, 0, 0, 105))
    shadow = shadow.filter(ImageFilter.GaussianBlur(10 * SS))
    img.alpha_composite(shadow)
    for i, (card, _) in enumerate(cards):
        img.alpha_composite(card, (pad * SS + i * (card_w + gap * SS), pad * SS))
    finish(img, w, h).save(OUT / f"{name}.png")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--publish", action="store_true",
                        help="copy the generated files to the marketing site's assets/store/")
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)

    shutil.copyfile(SERVER / "docs/spigotmc/logo.png", OUT / "logo.png")

    for name, label in (("features", "Features"), ("app", "The app"), ("pricing", "Free vs paid"),
                        ("install", "Getting started"), ("about", "About")):
        section(name, label)

    for name, label in (("install", "Installation"), ("configuration", "Configuration"),
                        ("placeholders", "Placeholders"), ("dashboards", "Dashboards"), ("maps", "Maps"),
                        ("security", "Security"), ("networks", "Networks"), ("app", "App guide"), ("docs", "Documentation"),
                        ("pricing", "Pricing"), ("website", "Website"), ("terms", "Terms of service"),
                        ("support", "Support")):
        button(name, label)

    for name in ("live", "shield", "lock", "chart", "widgets", "map", "layout", "network"):
        icon(name)

    plan("free", "Free", "Start here, forever", "€0", "forever", "#6b7280")
    plan("app", "App unlock", "For players · one-time", "€4.99", "local app store price", "#3b82f6")
    plan("server", "Server licence", "For owners · 14-day trial", "from €4.99", "per month · per server", GOLD)

    collage("app", ["dashboard-modern.webp", "dashboard-minecraft.webp", "edit-mode.webp"])
    collage("widgets", ["home-widgets-android.webp", "home-widgets-ios.webp"], width=320)
    collage("map", ["world-map.webp"], width=360)
    collage("add-server", ["add-server.webp"], width=360)

    if args.publish:
        PUBLISH.mkdir(parents=True, exist_ok=True)
        for path in sorted(OUT.glob("*.png")):
            shutil.copyfile(path, PUBLISH / path.name)
        print(f"published {len(list(OUT.glob('*.png')))} files to {PUBLISH}")
    print(f"wrote {len(list(OUT.glob('*.png')))} files to {OUT}")


if __name__ == "__main__":
    main()
