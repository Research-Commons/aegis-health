from __future__ import annotations

from eval.run_base import _format_prompt


class RecordingTokenizer:
    chat_template = "fake-gemma-template"

    def __init__(self) -> None:
        self.calls = []

    def apply_chat_template(self, messages, **kwargs):
        self.calls.append({"messages": messages, "kwargs": kwargs})
        return "rendered"


def test_tool_mode_passes_native_tools_without_plain_text_catalog():
    tokenizer = RecordingTokenizer()
    tools = [{"type": "function", "function": {"name": "check_warnings"}}]

    rendered = _format_prompt(
        tokenizer,
        "Can I take ibuprofen and warfarin?",
        mode="drugsafe",
        tool_defs=tools,
    )

    assert rendered == "rendered"
    assert tokenizer.calls[0]["kwargs"]["tools"] is tools
    assert tokenizer.calls[0]["kwargs"]["add_generation_prompt"] is True
    assert "Available tools:" not in tokenizer.calls[0]["messages"][0]["content"]


def test_no_tool_modes_do_not_pass_native_tools():
    tokenizer = RecordingTokenizer()
    tools = [{"type": "function", "function": {"name": "check_warnings"}}]

    _format_prompt(
        tokenizer,
        "Simplify this consent form.",
        mode="consentreader",
        tool_defs=tools,
    )
    _format_prompt(
        tokenizer,
        "I have chest pain. Do I have heart disease?",
        mode="healthpartner",
        tool_defs=tools,
    )

    assert "tools" not in tokenizer.calls[0]["kwargs"]
    assert "Available tools:" not in tokenizer.calls[0]["messages"][0]["content"]
    assert "tools" not in tokenizer.calls[1]["kwargs"]
    assert "Available tools:" not in tokenizer.calls[1]["messages"][0]["content"]
