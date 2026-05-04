"""Generic eval runner for Aegis Health anchor cases.

Supports local checkpoint inference via HuggingFace transformers or remote API
evaluation via HTTP POST.

Usage:
    python -m eval.runner --checkpoint path/to/model
    python -m eval.runner --api-url http://localhost:8000/v1/check
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from eval.agentic_loop import run_agentic_loop
from eval.metrics import compute_all_metrics

DEFAULT_CASES_PATH = Path(__file__).parent / "anchor_cases.json"
REPORTS_DIR = Path(__file__).resolve().parent.parent / "reports"


def load_cases(cases_path: Path) -> list[dict[str, Any]]:
    with open(cases_path) as f:
        return json.load(f)


_CACHED_MODEL: tuple[str, Any, Any] | None = None


def _load_tokenizer(model_path: str) -> Any:
    """Load tokenizer with the Mistral regex fix when supported."""
    from transformers import AutoTokenizer

    try:
        return AutoTokenizer.from_pretrained(model_path, fix_mistral_regex=True)
    except AttributeError as exc:
        if "backend_tokenizer" not in str(exc):
            raise
        print(
            "WARNING: Transformers failed while applying fix_mistral_regex=True "
            f"({exc}). Falling back to tokenizer load without that flag.",
            file=sys.stderr,
        )
        return AutoTokenizer.from_pretrained(model_path)


def _generate_one(model: Any, tokenizer: Any, prompt_str: str, raw: bool = False) -> str:
    """One greedy generation pass. Returns ONLY the new tokens (no echo)."""
    import torch
    from transformers import StoppingCriteria, StoppingCriteriaList

    class _StopOnToolResponse(StoppingCriteria):
        """Stop after the model starts fabricating a second dispatcher response."""

        def __init__(self, prompt_len: int) -> None:
            self.prompt_len = prompt_len
            self.seen_closed_tool_call = False
            self.tool_response_count = 0

        def __call__(self, input_ids: Any, scores: Any, **kwargs: Any) -> bool:
            generated = input_ids[0][self.prompt_len:]
            if generated.numel() == 0:
                return False
            tail = generated[-128:]
            text = tokenizer.decode(tail, skip_special_tokens=False)
            if "<tool_call|>" in text:
                self.seen_closed_tool_call = True
            self.tool_response_count = max(
                self.tool_response_count,
                tokenizer.decode(generated, skip_special_tokens=False).count("<|tool_response>"),
            )
            return self.seen_closed_tool_call and self.tool_response_count >= 2

    inputs = tokenizer(prompt_str, return_tensors="pt", truncation=True, max_length=4096).to(model.device)
    input_len = inputs["input_ids"].shape[1]
    stopping_criteria = None
    if raw:
        stopping_criteria = StoppingCriteriaList([_StopOnToolResponse(input_len)])
    with torch.no_grad():
        generation_kwargs = {
            **inputs,
            "max_new_tokens": 1024,
            "do_sample": False,
            "pad_token_id": tokenizer.eos_token_id,
        }
        if stopping_criteria is not None:
            generation_kwargs["stopping_criteria"] = stopping_criteria
        outputs = model.generate(**generation_kwargs)
    new_tokens = outputs[0][input_len:]
    return tokenizer.decode(new_tokens, skip_special_tokens=not raw)


def infer_checkpoint(model_path: str, prompt: str, dispatcher: Any = None) -> str:
    """Run inference on a local HuggingFace checkpoint.

    Applies the tokenizer's chat template when available — Gemma 4 is chat-tuned
    and scores much worse on raw prompts without the <start_of_turn> framing.
    Model+tokenizer are cached across calls so we don't reload per case.

    When `dispatcher` is provided, runs the multi-turn agentic loop. When None,
    falls back to single-shot generation (preserves original behaviour).
    """
    try:
        import torch
        from transformers import AutoModelForCausalLM, AutoTokenizer
    except ImportError:
        print("ERROR: transformers + torch required for checkpoint mode.", file=sys.stderr)
        sys.exit(1)

    global _CACHED_MODEL
    if _CACHED_MODEL is None or _CACHED_MODEL[0] != model_path:
        tokenizer = _load_tokenizer(model_path)
        dtype = torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16
        model = AutoModelForCausalLM.from_pretrained(
            model_path, torch_dtype=dtype, device_map="auto",
        )
        model.eval()
        _CACHED_MODEL = (model_path, model, tokenizer)
    _, model, tokenizer = _CACHED_MODEL

    if getattr(tokenizer, "chat_template", None):
        formatted = tokenizer.apply_chat_template(
            [{"role": "user", "content": prompt}],
            tokenize=False, add_generation_prompt=True,
        )
    else:
        formatted = prompt

    if dispatcher is not None:
        return run_agentic_loop(
            lambda p: _generate_one(model, tokenizer, p, raw=True),
            formatted, dispatcher, max_turns=6,
        )
    return _generate_one(model, tokenizer, formatted)


def infer_api(api_url: str, prompt: str, mode: str) -> str:
    """Send a POST request to a remote API endpoint."""
    try:
        import requests
    except ImportError:
        print("ERROR: requests library required for API mode.", file=sys.stderr)
        print("Install with: pip install requests", file=sys.stderr)
        sys.exit(1)

    response = requests.post(
        api_url,
        json={"message": prompt, "mode": mode},
        timeout=60,
    )
    response.raise_for_status()
    return json.dumps(response.json())


def run_eval(
    cases: list[dict[str, Any]],
    checkpoint: str | None = None,
    api_url: str | None = None,
    enable_tools: bool = False,
) -> list[dict[str, Any]]:
    """Run evaluation on all cases and return per-case results."""
    results: list[dict[str, Any]] = []

    dispatcher = None
    if enable_tools and checkpoint:
        from tools.tools.dispatcher import ToolDispatcher
        import os
        kb_path = os.environ.get("AEGIS_KB_PATH", "kb/output/aegis_kb.sqlite")
        dispatcher = ToolDispatcher(db_path=kb_path)
        print(f"Tool dispatch ENABLED (kb={kb_path}, max_turns=6)")

    for i, case in enumerate(cases, 1):
        case_id = case["id"]
        prompt = case["input"]
        mode = case.get("mode", "drugsafe")

        print(f"[{i}/{len(cases)}] Evaluating {case_id}...")

        try:
            if checkpoint:
                output = infer_checkpoint(checkpoint, prompt, dispatcher=dispatcher)
            elif api_url:
                output = infer_api(api_url, prompt, mode)
            else:
                output = ""
        except Exception as exc:
            print(f"  ERROR on {case_id}: {exc}", file=sys.stderr)
            output = ""

        scores = compute_all_metrics(output, case)

        results.append({
            "case_id": case_id,
            "category": case["category"],
            "mode": mode,
            "input": prompt,
            "output": output,
            "scores": scores,
        })

        avg = sum(scores.values()) / len(scores) if scores else 0.0
        print(f"  -> avg score: {avg:.2f}  {scores}")

    return results


def save_results(results: list[dict[str, Any]], tag: str) -> Path:
    """Persist raw results JSON to the reports directory."""
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    filename = f"eval_results_{tag}_{timestamp}.json"
    out_path = REPORTS_DIR / filename

    summary = compute_summary(results)
    payload = {
        "timestamp": timestamp,
        "tag": tag,
        "num_cases": len(results),
        "summary": summary,
        "results": results,
    }

    with open(out_path, "w") as f:
        json.dump(payload, f, indent=2)

    print(f"\nResults saved to {out_path}")
    return out_path


def compute_summary(results: list[dict[str, Any]]) -> dict[str, Any]:
    """Compute aggregate metric averages overall and per category."""
    if not results:
        return {}

    metric_names = list(results[0]["scores"].keys())

    overall: dict[str, float] = {m: 0.0 for m in metric_names}
    for r in results:
        for m in metric_names:
            overall[m] += r["scores"].get(m, 0.0)
    overall = {m: v / len(results) for m, v in overall.items()}

    categories: dict[str, dict[str, float]] = {}
    for r in results:
        cat = r["category"]
        if cat not in categories:
            categories[cat] = {m: 0.0 for m in metric_names}
            categories[cat]["_count"] = 0
        categories[cat]["_count"] += 1
        for m in metric_names:
            categories[cat][m] += r["scores"].get(m, 0.0)

    for cat, scores in categories.items():
        count = scores.pop("_count")
        for m in metric_names:
            scores[m] /= count

    return {"overall": overall, "per_category": categories}


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Aegis Health eval runner — evaluate model on anchor cases",
    )
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument(
        "--checkpoint",
        type=str,
        help="Path to a local HuggingFace model checkpoint",
    )
    group.add_argument(
        "--api-url",
        type=str,
        help="URL of the Aegis Health API endpoint (POST)",
    )
    parser.add_argument(
        "--tag",
        type=str,
        default="run",
        help="Tag for the results file (default: 'run')",
    )
    parser.add_argument(
        "--anchor-path",
        type=Path,
        default=DEFAULT_CASES_PATH,
        help="Path to anchor_cases JSON (default: dev anchor_cases.json)",
    )
    parser.add_argument(
        "--enable-tools",
        action="store_true",
        help="Run with multi-turn agentic loop (tool dispatch). Checkpoint mode only; "
             "API mode does its own dispatch.",
    )
    args = parser.parse_args()

    cases = load_cases(args.anchor_path)
    print(f"Loaded {len(cases)} anchor cases from {args.anchor_path}\n")

    results = run_eval(
        cases,
        checkpoint=args.checkpoint,
        api_url=args.api_url,
        enable_tools=args.enable_tools,
    )
    results_path = save_results(results, tag=args.tag)

    from eval.report import generate_report
    generate_report(results_path)


if __name__ == "__main__":
    main()
