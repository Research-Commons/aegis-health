"""Thin wrapper around the project's ToolDispatcher.

Falls back to a minimal re-implementation when the ``tools`` package is not on
``sys.path`` (e.g. running from the demo directory without installing the root
package).
"""

from __future__ import annotations

import json
import logging
import re
from typing import Any

logger = logging.getLogger(__name__)

try:
    from tools.tools.dispatcher import ToolDispatcher  # noqa: F401
except ImportError:
    logger.warning(
        "Could not import tools.tools.dispatcher – using stub ToolDispatcher"
    )

    class ToolDispatcher:  # type: ignore[no-redef]
        """Minimal stand-in when the real tools package is unavailable."""

        def __init__(self, db_path: str | None = None) -> None:
            self.db_path = db_path

        def dispatch(self, tool_call: dict) -> str:
            return json.dumps(
                {"error": f"Tool '{tool_call.get('name')}' unavailable in demo mode"}
            )


_NATIVE_TOOL_CALL_RE = re.compile(
    r"<\|tool_call>\s*call:\s*(\w+)\s*\{(.*?)\}\s*<tool_call\|>", re.DOTALL
)


def _parse_native_args(args_str: str) -> dict[str, Any]:
    """Convert Gemma 4 native key:val args to a Python dict."""
    s = args_str.replace("<|\"|>", '"')
    s = re.sub(r'(?<!["\w])(\w+)\s*:', r'"\1":', s)
    try:
        return json.loads("{" + s + "}")
    except json.JSONDecodeError:
        return {}


def _format_native_value(value: Any) -> str:
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


def extract_tool_calls(text: str) -> list[dict[str, Any]]:
    """Parse ``<|tool_call>call:name{args}<tool_call|>`` blocks from model output."""
    calls: list[dict[str, Any]] = []
    for match in _NATIVE_TOOL_CALL_RE.finditer(text):
        calls.append({"name": match.group(1), "arguments": _parse_native_args(match.group(2))})
    return calls


def format_tool_response(name: str, result_json: str) -> str:
    """Format a tool result in Gemma 4 native tool_response format."""
    try:
        result = json.loads(result_json)
    except (json.JSONDecodeError, TypeError):
        result = {"raw": result_json}
    if isinstance(result, dict):
        parts = [f"{k}:{_format_native_value(v)}" for k, v in sorted(result.items())]
        inner = ",".join(parts)
    else:
        inner = f"value:{_format_native_value(result)}"
    return f"<|tool_response>response:{name}{{{inner}}}<tool_response|>"


def run_agentic_loop(
    generate_fn,
    prompt: str,
    dispatcher: ToolDispatcher,
    *,
    max_rounds: int = 6,
    stream_callback=None,
) -> str:
    """Run the generate → tool_call → tool_response loop (Gemma 4 native format).

    Parameters
    ----------
    generate_fn:
        ``(prompt: str) -> str`` – calls the model and returns full text.
    prompt:
        The initial user prompt (with system context already prepended).
    dispatcher:
        A ``ToolDispatcher`` instance.
    max_rounds:
        Safety cap on agentic iterations.
    stream_callback:
        Optional ``(chunk: str) -> None`` called with each model token for
        WebSocket streaming.
    """
    conversation = prompt
    for _ in range(max_rounds):
        response = generate_fn(conversation)
        if stream_callback:
            stream_callback(response)

        tool_calls = extract_tool_calls(response)
        if not tool_calls:
            return response

        conversation = conversation + response
        for tc in tool_calls:
            result = dispatcher.dispatch(tc)
            conversation += format_tool_response(tc.get("name", ""), result)

    return response
