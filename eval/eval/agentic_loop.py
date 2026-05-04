"""Multi-turn agentic loop for eval inference.

Gemma 4 native tool-call wire format:
    <|tool_call>call: func_name{key:val,...}<tool_call|>
    <|tool_response>response: func_name{key:val,...}<tool_response|>

A model that never emits a <|tool_call> match exits on turn 1, so
enabling the loop is non-harmful for models that don't know the syntax
(e.g. base Gemma) — they just get scored on their single-shot output.
"""

from __future__ import annotations

import json
import logging
import re
from typing import Any, Callable

logger = logging.getLogger(__name__)

_NATIVE_TOOL_CALL_RE = re.compile(
    r"<\|tool_call>\s*call:\s*(\w+)\s*\{(.*?)\}\s*<tool_call\|>", re.DOTALL
)
_TRAILING_MARKERS_RE = re.compile(r"(?:\s*(?:<turn\|>|<eos>))+\s*$")
_LEADING_TURN_RE = re.compile(r"^\s*<\|turn>model\s*\n?")


def _parse_native_args(args_str: str) -> dict[str, Any]:
    """Convert Gemma 4 native key:val args to a Python dict."""
    s = args_str.replace("<|\"|>", '"')
    s = re.sub(r'(?<!["\w])(\w+)\s*:', r'"\1":', s)
    try:
        return json.loads("{" + s + "}")
    except json.JSONDecodeError:
        logger.warning("Failed to parse native args: %s", args_str[:120])
        return {}


def extract_tool_calls(text: str) -> list[dict[str, Any]]:
    """Parse all <|tool_call>call:name{args}<tool_call|> blocks."""
    calls: list[dict[str, Any]] = []
    for m in _NATIVE_TOOL_CALL_RE.finditer(text):
        calls.append({"name": m.group(1), "arguments": _parse_native_args(m.group(2))})
    return calls


def _format_native_value(value: Any) -> str:
    """Format a Python value to Gemma 4 native format (inverse of format_argument)."""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, str):
        return f'<|"|>{value}<|"|>'
    if isinstance(value, dict):
        parts = [f"{k}:{_format_native_value(v)}" for k, v in sorted(value.items())]
        return "{" + ",".join(parts) + "}"
    if isinstance(value, (list, tuple)):
        return "[" + ",".join(_format_native_value(v) for v in value) + "]"
    if isinstance(value, (int, float)):
        return str(value)
    if value is None:
        return "null"
    return f'<|"|>{value!s}<|"|>'


def format_tool_result(name: str, result_json: str) -> str:
    """Wrap a dispatcher result in Gemma 4 native tool_response format."""
    try:
        result = json.loads(result_json) if isinstance(result_json, str) else result_json
    except json.JSONDecodeError:
        result = {"raw": result_json}
    if isinstance(result, dict):
        parts = [f"{k}:{_format_native_value(v)}" for k, v in sorted(result.items())]
        inner = ",".join(parts)
    else:
        inner = f"value:{_format_native_value(result)}"
    return f"<|tool_response>response:{name}{{{inner}}}<tool_response|>"


# Gemma 4 chat-template turn delimiters — must match what apply_chat_template emits.
# Other Gemma versions use <start_of_turn>/<end_of_turn>; do NOT change without verifying.
_TURN_OPEN_MODEL = "<|turn>model\n"
_TURN_CLOSE      = "<turn|>\n"


def _clean_final(response: str) -> str:
    """Strip Gemma 4 turn delimiters from the final model response."""
    result = _TRAILING_MARKERS_RE.sub("", response)
    result = _LEADING_TURN_RE.sub("", result)
    return result.strip()


def run_agentic_loop(
    generate_fn: Callable[[str], str],
    initial_prompt: str,
    dispatcher: Any,
    max_turns: int = 6,
) -> str:
    """Run the generate -> tool_call -> tool_response loop.

    Gemma 4 native format keeps tool_call and tool_response inline within a
    single model turn, then closes the turn with `<turn|>` and opens a fresh
    `<|turn>model` for the next iteration (more tool_calls or the final JSON).
    Match this exactly: the SFT was trained on apply_chat_template(tools=...)
    output, which uses turn-segmented iterations, not one continuous model turn.

    Parameters
    ----------
    generate_fn : (prompt_str) -> response_str
        Called once per turn. Must return ONLY the new tokens (not echo input).
    initial_prompt : str
        Fully-formatted prompt (chat template applied, ends with `<|turn>model\\n`
        from `add_generation_prompt=True`).
    dispatcher : object with .dispatch(tool_call_dict) -> json_str
        Typically tools.tools.dispatcher.ToolDispatcher.
    max_turns : int
        Cap on agentic iterations. Matches Android ToolDispatcher.

    Returns the final response (the turn that did not emit a tool_call). If
    the model never emits one, this is the turn-1 single-shot output.
    """
    conversation = initial_prompt
    last_response = ""
    for turn in range(max_turns):
        response = generate_fn(conversation)
        last_response = response

        tool_calls = extract_tool_calls(response)
        if not tool_calls:
            return _clean_final(response)

        # The SFT model generates hallucinated tool_responses after its
        # tool_calls (it learned the full call→response sequence). Truncate
        # after the last <tool_call|> so only real dispatcher results appear.
        tc_matches = list(_NATIVE_TOOL_CALL_RE.finditer(response))
        conversation = conversation + response[:tc_matches[-1].end()]
        for tc in tool_calls:
            result_json = dispatcher.dispatch(tc)
            conversation += format_tool_result(tc.get("name", ""), result_json)
        # Training format closes the current model turn after each tool_call/
        # tool_response batch and opens a fresh <|turn>model for the next
        # iteration (whether that's more tool_calls or the final JSON). Without
        # this, the model is OOD and loops emitting more tool_calls.
        conversation += _TURN_CLOSE + _TURN_OPEN_MODEL

        logger.debug("Turn %d: dispatched %d tool_call(s)", turn + 1, len(tool_calls))

    logger.warning("Hit max_turns=%d without final answer; returning last response", max_turns)
    return _clean_final(last_response)
