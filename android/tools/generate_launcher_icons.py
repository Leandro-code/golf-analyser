#!/usr/bin/env python3
"""Generate Android launcher resources from the approved transparent master."""

from pathlib import Path

from PIL import Image, ImageDraw


ANDROID_DIR = Path(__file__).resolve().parents[1]
SOURCE = ANDROID_DIR / "branding" / "ic_launcher_foreground.png"
RES_DIR = ANDROID_DIR / "app" / "src" / "main" / "res"
BACKGROUND = (184, 247, 196, 255)

DENSITIES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}


def fitted_foreground(source: Image.Image, size: int, coverage: float) -> Image.Image:
    alpha_box = source.getchannel("A").getbbox()
    if alpha_box is None:
        raise ValueError(f"No visible artwork found in {SOURCE}")

    artwork = source.crop(alpha_box)
    target = int(size * coverage)
    scale = min(target / artwork.width, target / artwork.height)
    resized = artwork.resize(
        (max(1, round(artwork.width * scale)), max(1, round(artwork.height * scale))),
        Image.Resampling.LANCZOS,
    )
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(
        resized,
        ((size - resized.width) // 2, (size - resized.height) // 2),
    )
    return canvas


def rounded_mask(size: int, radius: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size - 1, size - 1), radius=radius, fill=255)
    return mask


def main() -> None:
    source = Image.open(SOURCE).convert("RGBA")

    for density, (legacy_size, adaptive_size) in DENSITIES.items():
        output_dir = RES_DIR / f"mipmap-{density}"
        output_dir.mkdir(parents=True, exist_ok=True)

        adaptive = fitted_foreground(source, adaptive_size, coverage=0.66)
        adaptive.save(output_dir / "ic_launcher_foreground.png", optimize=True)

        foreground = fitted_foreground(source, legacy_size, coverage=0.78)
        legacy = Image.new("RGBA", (legacy_size, legacy_size), BACKGROUND)
        legacy.alpha_composite(foreground)
        legacy.putalpha(rounded_mask(legacy_size, round(legacy_size * 0.22)))
        legacy.save(output_dir / "ic_launcher.png", optimize=True)

        round_icon = Image.new("RGBA", (legacy_size, legacy_size), BACKGROUND)
        round_icon.alpha_composite(foreground)
        circle = Image.new("L", (legacy_size, legacy_size), 0)
        ImageDraw.Draw(circle).ellipse((0, 0, legacy_size - 1, legacy_size - 1), fill=255)
        round_icon.putalpha(circle)
        round_icon.save(output_dir / "ic_launcher_round.png", optimize=True)

    preview = Image.new("RGBA", (1024, 1024), BACKGROUND)
    preview.alpha_composite(fitted_foreground(source, 1024, coverage=0.78))
    preview.putalpha(rounded_mask(1024, 225))
    preview.save(ANDROID_DIR / "branding" / "ic_launcher_preview.png", optimize=True)


if __name__ == "__main__":
    main()
