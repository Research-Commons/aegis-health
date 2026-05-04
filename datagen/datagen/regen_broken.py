"""Regenerate the examples in ``regen_queue.jsonl`` through the teacher pipeline.

For each broken example, calls ``_sample_variables`` to get a fresh KB-grounded
variable set (the contamination guard prevents anchor-case overlap), then runs
``generate_example`` with the new strict ``validate_final_turn_aegis_response``
gate active. Successes append to ``combined_sft_regen.jsonl``; failures (after
``MAX_RETRIES`` retries each) go to ``regen_failures.jsonl``.

CLI:
    python -m datagen.datagen.regen_broken                   # full run
    python -m datagen.datagen.regen_broken --limit 5         # V3 dry-run
    python -m datagen.datagen.regen_broken --template drugsafe_otc --limit 10
    python -m datagen.datagen.regen_broken --resume          # skip already-done
"""

from __future__ import annotations

import argparse
import json
import logging
import time
from collections import Counter
from pathlib import Path

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
log = logging.getLogger(__name__)

OUT = Path(__file__).resolve().parent.parent / "output"
QUEUE = OUT / "regen_queue.jsonl"
REGEN_OUT = OUT / "combined_sft_regen.jsonl"
FAILURES = OUT / "regen_failures.jsonl"


def main() -> None:
    parser = argparse.ArgumentParser(description="Regenerate broken SFT examples.")
    parser.add_argument(
        "--limit", type=int, default=None,
        help="Only process the first N examples (for dry-runs).",
    )
    parser.add_argument(
        "--template", type=str, default=None,
        help="Only regenerate examples of this template (e.g. drugsafe_otc).",
    )
    parser.add_argument(
        "--mode", type=str, default=None,
        choices=["drugsafe", "consent", "healthpartner"],
        help="Only regenerate examples of this mode.",
    )
    parser.add_argument(
        "--delay", type=float, default=1.0,
        help="Seconds between API calls (default 1.0).",
    )
    parser.add_argument(
        "--resume", action="store_true",
        help="Append to existing combined_sft_regen.jsonl instead of overwriting.",
    )
    args = parser.parse_args()

    # Defer the heavy import so --help works without OPENROUTER_API_KEY set.
    from .teacher import _sample_variables, generate_example

    if not QUEUE.exists():
        raise FileNotFoundError(
            f"{QUEUE} not found. Run `python -m datagen.datagen.filter_format` first."
        )

    queue = []
    with open(QUEUE, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            queue.append(json.loads(line))

    if args.template:
        queue = [q for q in queue if q.get("template") == args.template]
    if args.mode:
        queue = [q for q in queue if q.get("mode") == args.mode]
    if args.limit is not None:
        queue = queue[: args.limit]

    log.info("Queue size: %d examples", len(queue))
    if not queue:
        log.warning("Empty queue; nothing to do.")
        return

    # File mode: 'a' to resume, 'w' to start fresh
    mode_flag = "a" if args.resume else "w"
    REGEN_OUT.parent.mkdir(parents=True, exist_ok=True)

    template_counts: Counter[str] = Counter()
    template_success: Counter[str] = Counter()
    total_cost = 0.0
    failures: list[dict] = []

    with open(REGEN_OUT, mode_flag, encoding="utf-8") as out_fh:
        for i, old in enumerate(queue, 1):
            template = old.get("template", "<no-template>")
            mode = old.get("mode", "drugsafe")
            template_counts[template] += 1

            try:
                variables = _sample_variables(template, mode)
            except Exception as exc:
                log.warning("[%d/%d] %s: _sample_variables failed: %s",
                            i, len(queue), template, exc)
                failures.append({
                    "template": template, "mode": mode,
                    "error": f"_sample_variables: {exc}",
                })
                continue

            new = generate_example(template, variables, mode=mode)
            if new:
                # Attach the seed for audit, like teacher.generate_batch does
                if isinstance(variables.get("seed"), dict):
                    new["seed"] = variables["seed"]
                out_fh.write(json.dumps(new, ensure_ascii=False) + "\n")
                out_fh.flush()
                template_success[template] += 1
                total_cost += new.get("cost", 0.0)
                log.info("[%d/%d] %s: OK  (template clean rate so far: %d/%d, $%.4f)",
                         i, len(queue), template,
                         template_success[template], template_counts[template], total_cost)
            else:
                failures.append({"template": template, "mode": mode, "variables": variables})
                log.warning("[%d/%d] %s: FAILED after MAX_RETRIES",
                            i, len(queue), template)

            if args.delay > 0:
                time.sleep(args.delay)

    # Write failures
    if failures:
        with open(FAILURES, "w", encoding="utf-8") as fh:
            for f in failures:
                fh.write(json.dumps(f, default=str) + "\n")

    print()
    print("=== Summary ===")
    print(f"Queue:        {len(queue)}")
    print(f"Regenerated:  {sum(template_success.values())}")
    print(f"Failed:       {len(failures)}")
    print(f"Total cost:   ${total_cost:.4f}")
    print()
    print("Per-template success rate:")
    for template in sorted(template_counts):
        s = template_success[template]
        c = template_counts[template]
        print(f"  {template:32s} {s:>3d}/{c:<3d}  ({100*s/c:>5.1f}%)")


if __name__ == "__main__":
    main()
