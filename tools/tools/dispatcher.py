"""Route tool_call JSON payloads to the correct tool function."""

from __future__ import annotations

import json
import logging
from typing import Any

from tools.tools.check_warnings import check_warnings
from tools.tools.decompose_product import decompose_product
from tools.tools.get_drug_info import get_drug_info
from tools.tools.get_guideline import get_guideline
from tools.tools.lookup_term import lookup_term
from tools.tools.normalize_drug import normalize_drug

logger = logging.getLogger(__name__)

_TOOL_REGISTRY: dict[str, Any] = {
    "normalize_drug": normalize_drug,
    "decompose_product": decompose_product,
    "get_drug_info": get_drug_info,
    "check_warnings": check_warnings,
    "lookup_term": lookup_term,
    "get_guideline": get_guideline,
}


class ToolDispatcher:
    """Dispatch a tool_call dict to the matching tool function.

    Expected input format::

        {"name": "normalize_drug", "arguments": {"name": "Tylenol"}}

    Returns the tool result serialised as a JSON string.
    """

    def __init__(self, db_path: str | None = None) -> None:
        self.db_path = db_path

    def dispatch(self, tool_call: dict) -> str:
        name = tool_call.get("name", "")
        arguments: dict = tool_call.get("arguments", {})

        logger.info("Dispatching tool_call: name=%s args=%s", name, arguments)

        fn = _TOOL_REGISTRY.get(name)
        if fn is None:
            result = {"error": f"Unknown tool: '{name}'"}
            logger.warning("Unknown tool requested: %s", name)
            return json.dumps(result)

        # Inject db_path override when configured
        if self.db_path is not None and "db_path" not in arguments:
            arguments["db_path"] = self.db_path

        try:
            result = fn(**arguments)
        except TypeError as e:
            result = {"error": f"Invalid arguments for '{name}': {e}"}
            logger.error("Argument error for %s: %s", name, e)
        except Exception as e:
            result = {"error": f"Tool '{name}' failed: {e}"}
            logger.exception("Unexpected error in tool %s", name)

        return json.dumps(result, default=str)
