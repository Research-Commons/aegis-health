"""Entry point for ``python -m training.training``.

Dispatches to sub-commands:

    python -m training.training sft --config training/configs/sft_e4b.yaml
    python -m training.training merge --checkpoint-dir ... --output-dir ...
"""

from __future__ import annotations

import sys


def main() -> None:
    if len(sys.argv) < 2:
        print(
            "Usage: python -m training.training <command> [options]\n"
            "\n"
            "Commands:\n"
            "  sft      Run supervised fine-tuning\n"
            "  merge    Merge LoRA adapters and export model\n",
            file=sys.stderr,
        )
        sys.exit(1)

    command = sys.argv.pop(1)

    if command == "sft":
        from training.training.sft import main as sft_main
        sft_main()
    elif command == "merge":
        from training.training.merge_export import main as merge_main
        merge_main()
    else:
        print(f"Unknown command: {command}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
