"""Quantize a fine-tuned Gemma 4 checkpoint to INT4/INT8 via LiteRT-LM.

Tries the LiteRT-LM Python API first, then falls back to the CLI wrapper.
Validates output and reports compression statistics.

Usage:
    python -m export.quantize \
        --checkpoint path/to/merged_model \
        --output export/output/aegis_model.task \
        --quantization int4
"""

from __future__ import annotations

import argparse
import logging
import shutil
import subprocess
import sys
import time
from pathlib import Path

log = logging.getLogger(__name__)

SUPPORTED_QUANTS = ("int4", "int8")


def _dir_size_bytes(path: Path) -> int:
    """Total size of all files under *path* (recursive)."""
    if path.is_file():
        return path.stat().st_size
    return sum(f.stat().st_size for f in path.rglob("*") if f.is_file())


def _fmt_size(nbytes: int) -> str:
    if nbytes >= 1 << 30:
        return f"{nbytes / (1 << 30):.2f} GB"
    if nbytes >= 1 << 20:
        return f"{nbytes / (1 << 20):.1f} MB"
    return f"{nbytes / (1 << 10):.0f} KB"


def _quantize_programmatic(
    checkpoint: Path,
    output: Path,
    quantization: str,
) -> bool:
    """Attempt conversion using the litert_lm Python API.

    Returns True on success, False if the library is unavailable or errors out.
    """
    try:
        import litert_lm  # type: ignore[import-untyped]
    except ImportError:
        log.info("litert_lm Python package not importable — skipping programmatic path")
        return False

    try:
        converter = litert_lm.Converter(
            model_path=str(checkpoint),
            quantization=quantization,
        )
        converter.convert(output_path=str(output))
        return True
    except Exception:
        log.warning("Programmatic conversion failed", exc_info=True)
        return False


def _quantize_cli(
    checkpoint: Path,
    output: Path,
    quantization: str,
) -> bool:
    """Attempt conversion via the ``litert-lm convert`` CLI.

    Returns True on success, False if the binary is missing or the command fails.
    """
    binary = shutil.which("litert-lm")
    if binary is None:
        log.error(
            "litert-lm CLI not found on PATH. "
            "Install with: pip install litert-lm"
        )
        return False

    cmd = [
        binary,
        "convert",
        "--model_path", str(checkpoint),
        "--output_path", str(output),
        "--quantization", quantization,
    ]
    log.info("Running CLI: %s", " ".join(cmd))

    result = subprocess.run(cmd, capture_output=True, text=True)

    if result.stdout:
        log.info("litert-lm stdout:\n%s", result.stdout)
    if result.stderr:
        log.warning("litert-lm stderr:\n%s", result.stderr)

    if result.returncode != 0:
        log.error("litert-lm CLI exited with code %d", result.returncode)
        return False

    return True


def quantize(
    checkpoint: Path,
    output: Path,
    quantization: str = "int4",
) -> Path:
    """Convert *checkpoint* to a quantized ``.task`` file.

    Tries the Python API first, then the CLI.  Raises ``RuntimeError`` if both
    fail.
    """
    if quantization not in SUPPORTED_QUANTS:
        raise ValueError(f"Unsupported quantization: {quantization!r} (choose from {SUPPORTED_QUANTS})")

    if not checkpoint.exists():
        raise FileNotFoundError(f"Checkpoint not found: {checkpoint}")

    output.parent.mkdir(parents=True, exist_ok=True)

    input_size = _dir_size_bytes(checkpoint)
    log.info("Input model: %s (%s)", checkpoint, _fmt_size(input_size))
    log.info("Target quantization: %s", quantization)

    t0 = time.perf_counter()

    success = _quantize_programmatic(checkpoint, output, quantization)
    if not success:
        log.info("Falling back to CLI approach...")
        success = _quantize_cli(checkpoint, output, quantization)

    elapsed = time.perf_counter() - t0

    if not success:
        raise RuntimeError(
            "Quantization failed via both programmatic and CLI approaches. "
            "Ensure litert-lm is installed: pip install litert-lm"
        )

    if not output.exists():
        raise FileNotFoundError(f"Expected output file not found after conversion: {output}")

    output_size = output.stat().st_size
    ratio = input_size / output_size if output_size > 0 else float("inf")

    log.info("Output: %s (%s)", output, _fmt_size(output_size))
    log.info("Compression ratio: %.2fx", ratio)
    log.info("Conversion took %.1fs", elapsed)

    return output


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Quantize a Gemma 4 checkpoint to INT4/INT8 via LiteRT-LM",
    )
    parser.add_argument(
        "--checkpoint",
        type=str,
        required=True,
        help="Path to the merged HuggingFace model directory",
    )
    parser.add_argument(
        "--output",
        type=str,
        default="export/output/aegis_model.task",
        help="Output .task file path (default: export/output/aegis_model.task)",
    )
    parser.add_argument(
        "--quantization",
        type=str,
        choices=SUPPORTED_QUANTS,
        default="int4",
        help="Quantization level (default: int4)",
    )
    parser.add_argument(
        "--verbose", "-v",
        action="store_true",
        help="Enable debug-level logging",
    )
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s  %(levelname)-8s  %(message)s",
    )

    try:
        out = quantize(
            checkpoint=Path(args.checkpoint),
            output=Path(args.output),
            quantization=args.quantization,
        )
        print(f"\nQuantized model written to {out}")
    except Exception as exc:
        log.error("Quantization failed: %s", exc)
        sys.exit(1)


if __name__ == "__main__":
    main()
