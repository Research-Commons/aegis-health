"""Entry point for ``python -m export``.

Dispatches to the quantize, benchmark, or validate subcommands.

Usage:
    python -m export quantize  --checkpoint ... --output ...
    python -m export benchmark --model ...
    python -m export validate  --model ... --anchor-cases ...
"""

from __future__ import annotations

import sys


def main() -> None:
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help"):
        print(
            "Usage: python -m export <command> [options]\n"
            "\n"
            "Commands:\n"
            "  quantize   Convert a checkpoint to INT4/INT8 via LiteRT-LM\n"
            "  benchmark  Run latency and memory benchmarks\n"
            "  validate   Post-quantization validation on anchor cases\n"
            "\n"
            "Run `python -m export <command> --help` for command-specific options."
        )
        raise SystemExit(0)

    command = sys.argv[1]
    sys.argv = [f"export {command}"] + sys.argv[2:]

    if command == "quantize":
        from export.quantize import main as cmd_main
    elif command == "benchmark":
        from export.benchmark import main as cmd_main
    elif command == "validate":
        from export.validate_on_device import main as cmd_main
    else:
        print(f"Unknown command: {command!r}")
        print("Available commands: quantize, benchmark, validate")
        raise SystemExit(1)

    cmd_main()


if __name__ == "__main__":
    main()
