# Export / Quantization

Converts a fine-tuned Gemma 4 checkpoint into a quantized `.task` file for
on-device inference via LiteRT-LM, then benchmarks and validates the result.

## Prerequisites

```bash
# From the project root — installs litert-lm and other export deps
pip install -e ".[export]"
```

> **Note:** `litert-lm` is only needed on the export machine.  Training and
> evaluation machines do not require it.  All scripts detect its absence and
> print clear install instructions if missing.

## 1. Quantize

Convert a merged HuggingFace checkpoint to INT4 (default) or INT8:

```bash
python -m export quantize \
    --checkpoint path/to/merged_model \
    --output export/output/aegis_health_e4b.task \
    --quantization int4
```

The script tries the LiteRT-LM Python API first; if that fails it falls back
to the `litert-lm convert` CLI.  After conversion it validates the output file
exists and reports input size, output size, and compression ratio.

### Options

| Flag | Default | Description |
|------|---------|-------------|
| `--checkpoint` | *(required)* | Path to the merged HF model directory |
| `--output` | `export/output/aegis_health.task` | Output `.task` file path |
| `--quantization` | `int4` | `int4` or `int8` |
| `-v` / `--verbose` | off | Debug-level logging |

## 2. Benchmark

Measure latency, throughput, and memory for a quantized model:

```bash
python -m export benchmark \
    --model export/output/aegis_health_e4b.task \
    --device cpu \
    --num-runs 20
```

### Comparison mode

Benchmark a quantized model against the original checkpoint:

```bash
python -m export benchmark \
    --model export/output/aegis_health_e4b.task \
    --baseline path/to/merged_model \
    --output export/output/benchmark_results.json
```

### Metrics collected

| Metric | Description |
|--------|-------------|
| Model Load Time | Time to deserialize and initialize the model |
| Time to First Token (TTFT) | Latency before the first output token |
| Tokens per Second (TPS) | Steady-state decode throughput |
| Peak Memory | Maximum RSS during inference |

## 3. Validate

Run the full 50-case anchor suite on the quantized model and compare against
a pre-quantization eval report:

```bash
python -m export validate \
    --model export/output/aegis_health_e4b.task \
    --anchor-cases eval/eval/anchor_cases.json \
    --baseline-report eval/reports/eval_results_run_*.json \
    --output export/output/validation_results.json
```

The script computes all five evaluation metrics (`json_validity`,
`deferral_accuracy`, `citation_presence`, `safety_boundary`,
`severity_accuracy`) and reports any drop >2% as a warning.  The exit code
is non-zero if any metric regresses beyond the threshold.

## Expected Model Sizes

| Variant | FP16 | INT8 | INT4 |
|---------|------|------|------|
| Gemma 4 E4B | ~8 GB | ~4 GB | **~1.5 GB** |
| Gemma 4 E2B | ~4 GB | ~2 GB | **~800 MB** |

## Expected Latency Targets (Pixel 8 Pro, INT4)

| Metric | E4B Target | E2B Target |
|--------|-----------|-----------|
| Model Load | < 3 s | < 2 s |
| TTFT | < 500 ms | < 300 ms |
| Tokens/sec | > 15 | > 25 |
| Peak Memory | < 2 GB | < 1 GB |

## Output Directory

Generated artifacts go to `export/output/`:

```
export/output/
├── aegis_health_e4b.task      # INT4 quantized model
├── benchmark_results.json     # Benchmark measurements
└── validation_results.json    # Post-quant validation scores
```
