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


TOOL_CALL_RE = re.compile(
    r"<tool_call>\s*(\{.*?\})\s*</tool_call>", re.DOTALL
)


def extract_tool_calls(text: str) -> list[dict[str, Any]]:
    """Parse ``<tool_call>…</tool_call>`` blocks from model output."""
    calls: list[dict[str, Any]] = []
    for match in TOOL_CALL_RE.finditer(text):
        try:
            calls.append(json.loads(match.group(1)))
        except json.JSONDecodeError:
            logger.warning("Malformed tool_call JSON: %s", match.group(1))
    return calls


def run_agentic_loop(
    generate_fn,
    prompt: str,
    dispatcher: ToolDispatcher,
    *,
    max_rounds: int = 6,
    stream_callback=None,
) -> str:
    """Run the generate → tool_call → result loop.

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

        results: list[str] = []
        for tc in tool_calls:
            result = dispatcher.dispatch(tc)
            results.append(
                f"<tool_result name=\"{tc.get('name')}\">{result}</tool_result>"
            )

        conversation = conversation + "\n" + response + "\n" + "\n".join(results)

    return response
