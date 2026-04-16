"""Image augmentation pipeline for synthetic pill-label images.

Applies realistic photographic augmentations (perspective warp, blur, noise,
lighting variation, JPEG compression) to simulate real-world camera captures.

Usage:
    python -m datagen.augment --input-dir datagen/output/pill_images
    python -m datagen.augment --input-dir datagen/output/pill_images --augments-per-image 5
"""
from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

import albumentations as A
import cv2
import numpy as np

log = logging.getLogger(__name__)

DEFAULT_INPUT = Path(__file__).parent.parent / "output" / "pill_images"
DEFAULT_OUTPUT = Path(__file__).parent.parent / "output" / "pill_augmented"


def _build_pipeline() -> A.Compose:
    """Construct the augmentation pipeline.

    Each transform fires with independent probability so every generated
    image gets a different realistic combination.
    """
    return A.Compose([
        A.Perspective(scale=(0.02, 0.08), p=0.6),
        A.Rotate(limit=8, border_mode=cv2.BORDER_REFLECT_101, p=0.5),
        A.RandomCrop(
            height=360, width=720, p=0.3,
        ),
        A.Resize(height=400, width=800, always_apply=True),
        A.GaussianBlur(blur_limit=(3, 7), p=0.4),
        A.GaussNoise(var_limit=(10.0, 40.0), p=0.4),
        A.RandomBrightnessContrast(
            brightness_limit=0.25, contrast_limit=0.25, p=0.6,
        ),
        A.RandomShadow(
            shadow_roi=(0, 0, 1, 1),
            num_shadows_limit=(1, 2),
            shadow_dimension=5,
            p=0.3,
        ),
        A.ImageCompression(quality_lower=40, quality_upper=85, p=0.5),
    ])


_pipeline = _build_pipeline()


def augment_image(
    image_path: Path,
    output_dir: Path,
    num_augments: int = 5,
) -> list[Path]:
    """Apply augmentations to a single image.

    Args:
        image_path: Source PNG/JPG to augment.
        output_dir: Where to write augmented copies.
        num_augments: How many augmented variants to produce.

    Returns:
        List of paths to the generated images.
    """
    img = cv2.imread(str(image_path))
    if img is None:
        log.warning("Could not read image: %s", image_path)
        return []

    output_dir.mkdir(parents=True, exist_ok=True)
    stem = image_path.stem
    generated: list[Path] = []

    for i in range(num_augments):
        augmented = _pipeline(image=img)["image"]
        out_name = f"{stem}_aug{i:03d}.png"
        out_path = output_dir / out_name
        cv2.imwrite(str(out_path), augmented)
        generated.append(out_path)

    log.debug("Augmented %s → %d variants", image_path.name, len(generated))
    return generated


def augment_all(
    input_dir: Path,
    output_dir: Path,
    augments_per_image: int = 3,
) -> int:
    """Augment every image in *input_dir*.

    Args:
        input_dir: Folder of source images.
        output_dir: Destination for augmented images.
        augments_per_image: Variants to generate per source image.

    Returns:
        Total number of augmented images produced.
    """
    input_dir = Path(input_dir)
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    sources = sorted(
        p for p in input_dir.iterdir()
        if p.suffix.lower() in {".png", ".jpg", ".jpeg"}
    )

    if not sources:
        log.warning("No images found in %s", input_dir)
        return 0

    log.info("Augmenting %d source images (×%d each) …", len(sources), augments_per_image)
    total = 0

    for idx, src in enumerate(sources):
        paths = augment_image(src, output_dir, augments_per_image)
        total += len(paths)
        if (idx + 1) % 25 == 0:
            log.info("  processed %d / %d sources", idx + 1, len(sources))

    log.info("Done – %d augmented images in %s", total, output_dir)
    return total


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Augment pill-label images with realistic transforms",
    )
    parser.add_argument(
        "--input-dir", type=Path, default=DEFAULT_INPUT,
        help="Directory of source label images",
    )
    parser.add_argument(
        "--output-dir", type=Path, default=DEFAULT_OUTPUT,
        help="Directory for augmented output images",
    )
    parser.add_argument(
        "--augments-per-image", type=int, default=3,
        help="Number of augmented variants per source image (default: 3)",
    )
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )

    total = augment_all(args.input_dir, args.output_dir, args.augments_per_image)
    log.info("Generated %d augmented images.", total)
    return 0


if __name__ == "__main__":
    sys.exit(main())
