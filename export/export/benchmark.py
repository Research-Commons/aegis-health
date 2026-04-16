"""Latency and memory benchmarks for quantized Aegis Health models.

Measures time-to-first-token, tokens/sec, peak memory, and model load time
across a set of standard prompts derived from the eval anchor cases.

Usage:
    python -m export.benchmark --model export/output/aegis_health.task
    python -m export.benchmark --model export/output/aegis_health.task \
        --baseline path/to/checkpoint --device cpu --num-runs 20
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import resource
import sys
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

log = logging.getLogger(__name__)

STANDARD_PROMPTS = [
    "I take warfarin and just bought ibuprofen for a headache. Is that okay?",
    "Can I take metformin and drink alcohol?",
    "I'm on lisinopril — is it safe to use a potassium supplement?",
    "Summarize this consent form for a knee arthroscopy procedure.",
    "I'm a 55-year-old male with no prior screenings. What should I do?",
]


@dataclass
class RunResult:
    prompt_idx: int
    prompt_length_chars: int
    model_load_time_ms: float
    time_to_first_token_ms: float
    tokens_per_second: float
    total_tokens: int
    total_time_ms: float
    peak_memory_mb: float


@dataclass
class BenchmarkSummary:
    model_path: str
    device: str
    num_runs: int
    avg_ttft_ms: float = 0.0
    avg_tokens_per_sec: float = 0.0
    avg_model_load_time_ms: float = 0.0
    avg_peak_memory_mb: float = 0.0
    min_ttft_ms: float = 0.0
    max_ttft_ms: float = 0.0
    runs: list[dict[str, Any]] = field(default_factory=list)


def _peak_memory_mb() -> float:
    """Return peak RSS in MB for the current process (platform-dependent)."""
    usage = resource.getrusage(resource.RUSAGE_SELF)
    if sys.platform == "darwin":
        return usage.ru_maxrss / (1 << 20)
    return usage.ru_maxrss / (1 << 10)


def _load_litert_model(model_path: str, device: str) -> Any:
    try:
        import litert_lm  # type: ignore[import-untyped]
    except ImportError:
        log.error(
            "litert_lm is required for benchmarking .task files. "
            "Install with: pip install litert-lm"
        )
        sys.exit(1)

    t0 = time.perf_counter()
    model = litert_lm.load(model_path, device=device)
    load_ms = (time.perf_counter() - t0) * 1000
    return model, load_ms


def _load_hf_model(model_path: str, device: str) -> Any:
    try:
        from transformers import AutoModelForCausalLM, AutoTokenizer
    except ImportError:
        log.error(
            "transformers is required for benchmarking HF checkpoints. "
            "Install with: pip install transformers torch"
        )
        sys.exit(1)

    import torch

    device_map = "auto" if device == "gpu" else "cpu"

    t0 = time.perf_counter()
    tokenizer = AutoTokenizer.from_pretrained(model_path)
    model = AutoModelForCausalLM.from_pretrained(
        model_path,
        device_map=device_map,
        torch_dtype=torch.float16 if device == "gpu" else torch.float32,
    )
    load_ms = (time.perf_counter() - t0) * 1000

    return (model, tokenizer), load_ms


def _infer_litert(model: Any, prompt: str) -> tuple[float, float, int]:
    """Run inference on a LiteRT model.

    Returns (ttft_ms, total_ms, total_tokens).
    """
    first_token_time = None
    total_tokens = 0
    t0 = time.perf_counter()

    for _token in model.generate(prompt, max_tokens=256):
        if first_token_time is None:
            first_token_time = time.perf_counter()
        total_tokens += 1

    total_ms = (time.perf_counter() - t0) * 1000
    ttft_ms = ((first_token_time or t0) - t0) * 1000
    return ttft_ms, total_ms, total_tokens


def _infer_hf(model_and_tok: Any, prompt: str) -> tuple[float, float, int]:
    """Run inference on a HuggingFace model.

    Returns (ttft_ms, total_ms, total_tokens).
    """
    import torch

    model, tokenizer = model_and_tok
    inputs = tokenizer(prompt, return_tensors="pt")
    input_len = inputs["input_ids"].shape[-1]

    if hasattr(model, "device"):
        inputs = {k: v.to(model.device) for k, v in inputs.items()}

    t0 = time.perf_counter()
    with torch.no_grad():
        outputs = model.generate(**inputs, max_new_tokens=256, do_sample=False)
    total_ms = (time.perf_counter() - t0) * 1000

    total_tokens = outputs.shape[-1] - input_len
    ttft_ms = total_ms / max(total_tokens, 1)
    return ttft_ms, total_ms, int(total_tokens)


def run_benchmark(
    model_path: str,
    device: str = "cpu",
    num_runs: int = 10,
) -> BenchmarkSummary:
    """Run benchmarks against the model and return a summary."""
    is_task_file = model_path.endswith(".task")

    if is_task_file:
        model, load_ms = _load_litert_model(model_path, device)
        infer_fn = _infer_litert
    else:
        model, load_ms = _load_hf_model(model_path, device)
        infer_fn = _infer_hf

    log.info("Model loaded in %.1f ms", load_ms)

    results: list[RunResult] = []

    for run_idx in range(num_runs):
        prompt = STANDARD_PROMPTS[run_idx % len(STANDARD_PROMPTS)]

        ttft_ms, total_ms, total_tokens = infer_fn(model, prompt)
        tps = (total_tokens / (total_ms / 1000)) if total_ms > 0 else 0.0
        peak_mem = _peak_memory_mb()

        result = RunResult(
            prompt_idx=run_idx % len(STANDARD_PROMPTS),
            prompt_length_chars=len(prompt),
            model_load_time_ms=load_ms,
            time_to_first_token_ms=ttft_ms,
            tokens_per_second=tps,
            total_tokens=total_tokens,
            total_time_ms=total_ms,
            peak_memory_mb=peak_mem,
        )
        results.append(result)

        log.info(
            "Run %d/%d: TTFT=%.1fms  TPS=%.1f  tokens=%d  mem=%.0fMB",
            run_idx + 1, num_runs, ttft_ms, tps, total_tokens, peak_mem,
        )

    ttfts = [r.time_to_first_token_ms for r in results]
    summary = BenchmarkSummary(
        model_path=model_path,
        device=device,
        num_runs=num_runs,
        avg_ttft_ms=sum(ttfts) / len(ttfts),
        avg_tokens_per_sec=sum(r.tokens_per_second for r in results) / len(results),
        avg_model_load_time_ms=load_ms,
        avg_peak_memory_mb=sum(r.peak_memory_mb for r in results) / len(results),
        min_ttft_ms=min(ttfts),
        max_ttft_ms=max(ttfts),
        runs=[asdict(r) for r in results],
    )
    return summary


def print_summary_table(summary: BenchmarkSummary) -> None:
    """Print a human-readable summary table to stdout."""
    print("\n" + "=" * 60)
    print(f"  Benchmark Summary: {summary.model_path}")
    print(f"  Device: {summary.device}  |  Runs: {summary.num_runs}")
    print("=" * 60)
    print(f"  {'Metric':<30} {'Value':>15}")
    print(f"  {'-' * 30} {'-' * 15}")
    print(f"  {'Model Load Time':<30} {summary.avg_model_load_time_ms:>12.1f} ms")
    print(f"  {'Avg TTFT':<30} {summary.avg_ttft_ms:>12.1f} ms")
    print(f"  {'Min TTFT':<30} {summary.min_ttft_ms:>12.1f} ms")
    print(f"  {'Max TTFT':<30} {summary.max_ttft_ms:>12.1f} ms")
    print(f"  {'Avg Tokens/sec':<30} {summary.avg_tokens_per_sec:>12.1f}")
    print(f"  {'Avg Peak Memory':<30} {summary.avg_peak_memory_mb:>11.1f} MB")
    print("=" * 60)


def print_comparison(current: BenchmarkSummary, baseline: BenchmarkSummary) -> None:
    """Print a side-by-side comparison of two benchmark runs."""
    print("\n" + "=" * 75)
    print("  Comparison: Baseline vs Current")
    print("=" * 75)
    print(f"  {'Metric':<25} {'Baseline':>15} {'Current':>15} {'Delta':>12}")
    print(f"  {'-' * 25} {'-' * 15} {'-' * 15} {'-' * 12}")

    rows = [
        ("Load Time (ms)", baseline.avg_model_load_time_ms, current.avg_model_load_time_ms),
        ("Avg TTFT (ms)", baseline.avg_ttft_ms, current.avg_ttft_ms),
        ("Avg TPS", baseline.avg_tokens_per_sec, current.avg_tokens_per_sec),
        ("Peak Memory (MB)", baseline.avg_peak_memory_mb, current.avg_peak_memory_mb),
    ]
    for label, base_val, curr_val in rows:
        delta = curr_val - base_val
        sign = "+" if delta >= 0 else ""
        print(f"  {label:<25} {base_val:>15.1f} {curr_val:>15.1f} {sign}{delta:>11.1f}")

    print("=" * 75)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Benchmark an Aegis Health model (.task or HF checkpoint)",
    )
    parser.add_argument(
        "--model",
        type=str,
        required=True,
        help="Path to the .task file or HuggingFace checkpoint directory",
    )
    parser.add_argument(
        "--device",
        type=str,
        choices=("cpu", "gpu"),
        default="cpu",
        help="Device to run inference on (default: cpu)",
    )
    parser.add_argument(
        "--num-runs",
        type=int,
        default=10,
        help="Number of inference runs (default: 10)",
    )
    parser.add_argument(
        "--baseline",
        type=str,
        default=None,
        help="Path to a baseline model for comparison benchmarking",
    )
    parser.add_argument(
        "--output",
        type=str,
        default=None,
        help="Path to save JSON results (default: stdout only)",
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

    log.info("Benchmarking model: %s", args.model)
    summary = run_benchmark(args.model, device=args.device, num_runs=args.num_runs)
    print_summary_table(summary)

    baseline_summary: BenchmarkSummary | None = None
    if args.baseline:
        log.info("Benchmarking baseline: %s", args.baseline)
        baseline_summary = run_benchmark(args.baseline, device=args.device, num_runs=args.num_runs)
        print_summary_table(baseline_summary)
        print_comparison(summary, baseline_summary)

    output_data: dict[str, Any] = {"current": asdict(summary)}
    if baseline_summary:
        output_data["baseline"] = asdict(baseline_summary)

    if args.output:
        out_path = Path(args.output)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        with open(out_path, "w") as f:
            json.dump(output_data, f, indent=2)
        print(f"\nResults saved to {out_path}")
    else:
        print("\nJSON results:")
        print(json.dumps(output_data, indent=2))


if __name__ == "__main__":
    main()
