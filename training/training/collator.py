"""SFT data collators for Gemma 4 agentic training."""

from __future__ import annotations

from typing import Any


def resolve_text_tokenizer(processor_or_tokenizer: Any) -> Any:
    """Return the text tokenizer from a tokenizer or multimodal processor."""
    obj = processor_or_tokenizer
    if hasattr(obj, "encode"):
        return obj
    for attr in ("tokenizer", "text_tokenizer"):
        if hasattr(obj, attr):
            inner = getattr(obj, attr)
            if hasattr(inner, "encode"):
                return inner
    raise AttributeError(f"Could not find a text tokenizer on {type(obj).__name__}")


class AssistantOnlyCollator:
    """Mask loss to assistant-produced tokens only.

    Keeps model loss on Gemma native tool calls and final AegisResponse JSON,
    while masking system/user turns and rendered tool responses supplied by
    the runtime.
    """

    def __init__(self, text_tokenizer: Any) -> None:
        from transformers import DataCollatorForLanguageModeling

        self.text_tokenizer = text_tokenizer
        self.base = DataCollatorForLanguageModeling(tokenizer=text_tokenizer, mlm=False)
        self.start_ids = text_tokenizer.encode("<|turn>model\n", add_special_tokens=False)
        self.end_ids = text_tokenizer.encode("<turn|>", add_special_tokens=False)
        if not self.start_ids:
            raise ValueError("failed to encode Gemma model-turn start marker")
        if not self.end_ids:
            raise ValueError("failed to encode Gemma turn-close marker")

    @staticmethod
    def _find_seq(ids: list[int], pattern: list[int], start: int, stop: int) -> int:
        plen = len(pattern)
        for idx in range(start, stop - plen + 1):
            if ids[idx:idx + plen] == pattern:
                return idx
        return -1

    def _text_pos_to_tok(
        self,
        ids: list[int],
        span_start: int,
        span_end: int,
        target_text_len: int,
    ) -> int:
        lo, hi = span_start, span_end
        while lo < hi:
            mid = (lo + hi) // 2
            decoded_len = len(
                self.text_tokenizer.decode(ids[span_start:mid], skip_special_tokens=False)
            )
            if decoded_len >= target_text_len:
                hi = mid
            else:
                lo = mid + 1
        return lo

    def _mask_tool_responses_in_span(
        self,
        ids: list[int],
        labels_row: Any,
        span_start: int,
        span_end: int,
    ) -> None:
        span_text = self.text_tokenizer.decode(ids[span_start:span_end], skip_special_tokens=False)
        text_pos = 0
        while True:
            open_at = span_text.find("<|tool_response>", text_pos)
            if open_at == -1:
                break
            close_at = span_text.find("<tool_response|>", open_at + len("<|tool_response>"))
            end_text_pos = len(span_text) if close_at == -1 else close_at + len("<tool_response|>")
            open_tok = self._text_pos_to_tok(ids, span_start, span_end, open_at)
            end_tok = self._text_pos_to_tok(ids, span_start, span_end, end_text_pos)
            for idx in range(open_tok, min(end_tok, span_end)):
                labels_row[idx] = -100
            text_pos = end_text_pos

    def __call__(self, examples: list[dict[str, Any]]) -> dict[str, Any]:
        batch = self.base(examples)
        input_ids = batch["input_ids"]
        labels = batch["labels"].clone()
        labels[:] = -100

        for row in range(input_ids.size(0)):
            ids = input_ids[row].tolist()
            n_ids = len(ids)
            idx = 0
            while idx <= n_ids - len(self.start_ids):
                if ids[idx:idx + len(self.start_ids)] != self.start_ids:
                    idx += 1
                    continue
                span_start = idx + len(self.start_ids)
                end_at = self._find_seq(ids, self.end_ids, span_start, n_ids)
                span_end = end_at if end_at != -1 else n_ids
                for tok_idx in range(span_start, span_end):
                    labels[row, tok_idx] = ids[tok_idx]
                self._mask_tool_responses_in_span(ids, labels[row], span_start, span_end)
                idx = span_end + len(self.end_ids)

        batch["labels"] = labels
        return batch
