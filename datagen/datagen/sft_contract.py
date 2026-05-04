"""SFT contract conversion and validation.

This module is the shared source of truth for turning raw synthetic
``combined_sft.jsonl`` conversations into Gemma 4 training messages.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

try:  # Colab eval-kit layout
    from datagen.datagen.validators import validate_aegis_response
except ModuleNotFoundError:  # Local editable package layout
    from datagen.validators import validate_aegis_response

TOOL_CALL_RE = re.compile(r"<\|tool_call>\s*(\{.*?\})\s*<tool_call\|>", re.DOTALL)
TOOL_RESULT_RE = re.compile(r"<\|tool_result>\s*(\{.*?\})\s*<tool_result\|>", re.DOTALL)

HEALTHPARTNER_SYMPTOM_RE = re.compile(
    r"\b("
    r"do i have|could this be|is this|am i|diagnos(?:e|is|ed)|"
    r"symptom|pain|ache|cramp|bloating|bleeding|rash|fever|swollen|"
    r"stiff|dizzy|shortness of breath|trouble breathing"
    r")\b",
    re.IGNORECASE,
)


def load_tool_defs(path: str | Path | None = None) -> list[dict[str, Any]]:
    """Load the tool schema used by Gemma's chat template."""
    candidates = [
        Path(path) if path else None,
        Path("tools/tools/tool_defs.json"),
        Path("/content/aegis-health/tools/tools/tool_defs.json"),
    ]
    for candidate in candidates:
        if candidate and candidate.exists():
            return json.loads(candidate.read_text(encoding="utf-8"))
    raise FileNotFoundError(f"tool_defs.json not found. Checked: {candidates}")


def normalize_mode(mode: str | None) -> str:
    value = (mode or "drugsafe").strip().lower()
    if value == "consentreader":
        return "consent"
    return value


def is_healthpartner_symptom_request(record: dict[str, Any]) -> bool:
    """Return True when HealthPartner should not use preventive-guideline tools."""
    mode = normalize_mode(record.get("mode"))
    if mode != "healthpartner":
        return False
    if record.get("category") == "safety_diagnosis":
        return True
    user_text = " ".join(
        str(m.get("content", ""))
        for m in record.get("conversation", [])
        if isinstance(m, dict) and m.get("role") == "user"
    )
    user_text = user_text or str(record.get("input", ""))
    return bool(HEALTHPARTNER_SYMPTOM_RE.search(user_text))


def should_use_tools(record: dict[str, Any]) -> bool:
    """Mode policy for training/eval prompts."""
    mode = normalize_mode(record.get("mode"))
    if mode == "consent":
        return False
    if mode == "healthpartner":
        return not is_healthpartner_symptom_request(record)
    return True


def strip_embedded_tool_defs(system_content: str) -> str:
    if "Available tools:" in system_content:
        return system_content.split("Available tools:", 1)[0].strip()
    return system_content.strip()


def _strip_tool_blocks(content: str) -> str:
    return TOOL_CALL_RE.sub("", TOOL_RESULT_RE.sub("", content)).strip()


def _extract_calls(content: str, next_id: int) -> tuple[list[dict[str, Any]], int]:
    calls: list[dict[str, Any]] = []
    for match in TOOL_CALL_RE.finditer(content):
        parsed = json.loads(match.group(1))
        call_id = f"call_{next_id}"
        next_id += 1
        calls.append(
            {
                "id": call_id,
                "type": "function",
                "function": {
                    "name": parsed["name"],
                    "arguments": parsed.get("arguments", {}) or {},
                },
            }
        )
    return calls, next_id


def _extract_tool_results(content: str) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    for match in TOOL_RESULT_RE.finditer(content):
        parsed = json.loads(match.group(1))
        results.append(
            {
                "name": parsed.get("name", "unknown"),
                "content": parsed.get("result", parsed),
            }
        )
    return results


def _format_native_value(value: Any) -> str:
    if isinstance(value, str):
        return f'<|"|>{value}<|"|>'
    if isinstance(value, bool):
        return "true" if value else "false"
    if value is None:
        return "null"
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, list):
        return "[" + ",".join(_format_native_value(item) for item in value) + "]"
    if isinstance(value, dict):
        return "{" + ",".join(
            f"{key}:{_format_native_value(item)}" for key, item in sorted(value.items())
        ) + "}"
    return f'<|"|>{str(value)}<|"|>'


def _format_native_tool_response(message: dict[str, Any]) -> str:
    content = message.get("content", {})
    if isinstance(content, dict):
        inner = ",".join(
            f"{key}:{_format_native_value(value)}" for key, value in sorted(content.items())
        )
    else:
        inner = f"value:{_format_native_value(content)}"
    return f"<|tool_response>response:{message.get('name', 'unknown')}{{{inner}}}<tool_response|>"


def _inline_tool_responses(messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Fallback for tokenizers/templates that silently drop role='tool' turns."""
    inlined: list[dict[str, Any]] = []
    for message in messages:
        if message.get("role") != "tool":
            cloned = dict(message)
            if "tool_calls" in cloned:
                cloned["tool_calls"] = list(cloned["tool_calls"])
            inlined.append(cloned)
            continue

        response = _format_native_tool_response(message)
        if inlined and inlined[-1].get("role") == "assistant":
            inlined[-1]["content"] = str(inlined[-1].get("content", "")) + response
        else:
            inlined.append({"role": "assistant", "content": response})
    return inlined


def _final_json_from_model_content(content: str) -> str:
    text = TOOL_CALL_RE.sub("", TOOL_RESULT_RE.sub("", content)).strip()
    parsed = json.loads(text)
    if not validate_aegis_response(parsed):
        raise ValueError("final assistant content is not a valid AegisResponse")
    return json.dumps(parsed, ensure_ascii=False, separators=(",", ":"))


def final_aegis_json(record: dict[str, Any]) -> str:
    """Extract the final AegisResponse JSON target from a raw SFT record."""
    for message in reversed(record.get("conversation", [])):
        if not isinstance(message, dict):
            continue
        if message.get("role") not in ("model", "assistant"):
            continue
        content = message.get("content", "")
        if not isinstance(content, str):
            continue
        try:
            return _final_json_from_model_content(content)
        except (json.JSONDecodeError, ValueError):
            continue
    raise ValueError("record has no valid final AegisResponse JSON")


def _append_tool_messages(
    messages: list[dict[str, Any]],
    pending_tool_calls: list[dict[str, Any]],
    tool_results: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    remaining = list(pending_tool_calls)
    for index, result in enumerate(tool_results):
        call = None
        if remaining:
            match_index = next(
                (
                    idx
                    for idx, pending in enumerate(remaining)
                    if pending.get("function", {}).get("name") == result["name"]
                ),
                0,
            )
            for stale in remaining[:match_index]:
                _remove_tool_call(messages, stale["id"])
            call = remaining[match_index]
            remaining = remaining[match_index + 1:]
        messages.append(
            {
                "role": "tool",
                "tool_call_id": call["id"] if call else f"unmatched_{index}",
                "name": result["name"],
                "content": result["content"],
            }
        )
    return remaining


def _remove_tool_call(messages: list[dict[str, Any]], call_id: str) -> None:
    """Remove a stale assistant tool call that never received a tool result."""
    for index in range(len(messages) - 1, -1, -1):
        message = messages[index]
        calls = message.get("tool_calls")
        if not calls:
            continue
        filtered = [call for call in calls if call.get("id") != call_id]
        if len(filtered) == len(calls):
            continue
        if filtered:
            message["tool_calls"] = filtered
        elif message.get("content"):
            message.pop("tool_calls", None)
        else:
            messages.pop(index)
        return


def convert_record_to_messages(record: dict[str, Any]) -> list[dict[str, Any]]:
    """Convert a raw JSONL record to structured Gemma chat-template messages."""
    raw = record.get("conversation") or []
    if not raw:
        raise ValueError("record has no conversation")

    system = raw[0].get("content", "") if isinstance(raw[0], dict) else ""
    messages: list[dict[str, Any]] = [
        {"role": "system", "content": strip_embedded_tool_defs(str(system))}
    ]

    if not should_use_tools(record):
        cleaned_user_turns = [
            _strip_tool_blocks(str(m.get("content", "")))
            for m in raw
            if isinstance(m, dict) and m.get("role") == "user"
        ]
        user_text = "\n\n".join(turn for turn in cleaned_user_turns if turn)
        messages.append({"role": "user", "content": user_text})
        messages.append({"role": "assistant", "content": final_aegis_json(record)})
        return messages

    pending_tool_calls: list[dict[str, Any]] = []
    next_call_id = 0
    for item in raw[1:]:
        if not isinstance(item, dict):
            continue
        role = item.get("role")
        content = item.get("content", "")
        if not isinstance(content, str):
            raise ValueError("conversation content must be a string")

        tool_results = _extract_tool_results(content)
        if tool_results:
            pending_tool_calls = _append_tool_messages(messages, pending_tool_calls, tool_results)

        calls, next_call_id = _extract_calls(content, next_call_id)
        if calls:
            if pending_tool_calls:
                last = messages[-1] if messages else {}
                if last.get("role") == "assistant" and "tool_calls" in last:
                    last["tool_calls"].extend(calls)
                    pending_tool_calls = last["tool_calls"]
                else:
                    messages.append({"role": "assistant", "content": "", "tool_calls": calls})
                    pending_tool_calls.extend(calls)
            else:
                messages.append({"role": "assistant", "content": "", "tool_calls": calls})
                pending_tool_calls = calls

        remaining = TOOL_CALL_RE.sub("", TOOL_RESULT_RE.sub("", content)).strip()
        if remaining:
            if role == "user":
                messages.append({"role": "user", "content": remaining})
                continue
            parsed = json.loads(remaining)
            if validate_aegis_response(parsed):
                if pending_tool_calls:
                    for stale in pending_tool_calls:
                        _remove_tool_call(messages, stale["id"])
                    pending_tool_calls = []
                messages.append(
                    {
                        "role": "assistant",
                        "content": json.dumps(parsed, ensure_ascii=False, separators=(",", ":")),
                    }
                )
            else:
                raise ValueError("non-JSON assistant prose is not allowed in SFT contract")

        if role == "user" and not tool_results and not calls and not remaining:
            messages.append({"role": "user", "content": content})

    if pending_tool_calls:
        for stale in pending_tool_calls:
            _remove_tool_call(messages, stale["id"])
    validate_structured_messages(record, messages)
    return messages


def render_record_for_sft(
    record: dict[str, Any],
    tokenizer: Any,
    tool_defs: list[dict[str, Any]] | None = None,
) -> str:
    """Render a raw SFT record through the tokenizer's chat template."""
    messages = convert_record_to_messages(record)
    kwargs: dict[str, Any] = {
        "tokenize": False,
        "add_generation_prompt": False,
    }
    if should_use_tools(record):
        kwargs["tools"] = tool_defs if tool_defs is not None else load_tool_defs()
    rendered = tokenizer.apply_chat_template(messages, **kwargs)
    if should_use_tools(record) and any(message.get("role") == "tool" for message in messages):
        if "<|tool_response>" not in rendered:
            rendered = tokenizer.apply_chat_template(_inline_tool_responses(messages), **kwargs)
    validate_rendered_text(record, rendered)
    return rendered


def validate_structured_messages(record: dict[str, Any], messages: list[dict[str, Any]]) -> None:
    """Raise if structured messages violate the agreed SFT contract."""
    if not messages or messages[0].get("role") != "system":
        raise ValueError("messages must start with a system turn")
    expect_tools = should_use_tools(record)
    final_assistant = None
    for message in messages:
        role = message.get("role")
        if role not in {"system", "user", "assistant", "tool"}:
            raise ValueError(f"unsupported role: {role}")
        if "tool_responses" in message:
            raise ValueError("assistant-owned tool_responses are forbidden")
        if role == "assistant" and message.get("content"):
            final_assistant = message
        if not expect_tools and (message.get("tool_calls") or role == "tool"):
            raise ValueError("no-tool modes must not contain tool calls or tool messages")
    if final_assistant is None:
        raise ValueError("missing final assistant response")
    parsed = json.loads(final_assistant["content"])
    if not validate_aegis_response(parsed):
        raise ValueError("final assistant response is not AegisResponse JSON")


def validate_rendered_text(record: dict[str, Any], rendered: str) -> None:
    """Raise if rendered training text leaks legacy markers or misses native tools."""
    if "<|tool_call>{" in rendered:
        raise ValueError("rendered text contains raw JSON-style tool_call marker")
    if "<|tool_result>" in rendered or "<tool_result|>" in rendered:
        raise ValueError("rendered text contains legacy tool_result marker")
    expect_tools = should_use_tools(record)
    if expect_tools and any(TOOL_CALL_RE.search(str(m.get("content", ""))) for m in record.get("conversation", [])):
        if "<|tool_call>call:" not in rendered:
            raise ValueError("tool example did not render native Gemma tool_call syntax")
    if expect_tools and any(TOOL_RESULT_RE.search(str(m.get("content", ""))) for m in record.get("conversation", [])):
        if "<|tool_response>" not in rendered:
            raise ValueError("tool example did not render native Gemma tool_response syntax")
    if not expect_tools and "<|tool_call>" in rendered:
        raise ValueError("no-tool mode rendered tool_call syntax")
