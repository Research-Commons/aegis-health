"""Tests for datagen.datagen.validators."""

from __future__ import annotations

import json
from pathlib import Path
from unittest import mock

import pytest

from datagen.datagen.validators import (
    REJECTED_LOG,
    reject_and_log,
    validate_aegis_response,
    validate_chat_format,
    validate_tool_calls,
)


# ---------------------------------------------------------------------------
# validate_aegis_response
# ---------------------------------------------------------------------------

class TestValidateAegisResponse:
    def _valid(self) -> dict:
        return {
            "flags": [
                {"severity": 3, "description": "Interaction found", "citation": "FDA label"}
            ],
            "citations": [{"source": "DailyMed", "text": "ibuprofen monograph"}],
            "confidence": 0.85,
            "defer_to_professional": False,
            "explanation": "Moderate interaction detected.",
        }

    def test_valid(self):
        assert validate_aegis_response(self._valid()) is True

    def test_missing_key(self):
        data = self._valid()
        del data["confidence"]
        assert validate_aegis_response(data) is False

    def test_confidence_out_of_range(self):
        data = self._valid()
        data["confidence"] = 1.5
        assert validate_aegis_response(data) is False

    def test_confidence_negative(self):
        data = self._valid()
        data["confidence"] = -0.1
        assert validate_aegis_response(data) is False

    def test_bad_severity(self):
        data = self._valid()
        data["flags"][0]["severity"] = 6
        assert validate_aegis_response(data) is False

    def test_severity_zero(self):
        data = self._valid()
        data["flags"][0]["severity"] = 0
        assert validate_aegis_response(data) is False

    def test_flag_missing_field(self):
        data = self._valid()
        del data["flags"][0]["citation"]
        assert validate_aegis_response(data) is False

    def test_citation_missing_field(self):
        data = self._valid()
        data["citations"] = [{"source": "FDA"}]
        assert validate_aegis_response(data) is False

    def test_not_a_dict(self):
        assert validate_aegis_response("string") is False
        assert validate_aegis_response([]) is False

    def test_empty_flags_and_citations(self):
        data = self._valid()
        data["flags"] = []
        data["citations"] = []
        assert validate_aegis_response(data) is True

    def test_defer_not_bool(self):
        data = self._valid()
        data["defer_to_professional"] = "yes"
        assert validate_aegis_response(data) is False


# ---------------------------------------------------------------------------
# validate_chat_format
# ---------------------------------------------------------------------------

class TestValidateChatFormat:
    def _valid_conversation(self) -> list[dict]:
        return [
            {"role": "system", "content": "You are Aegis Health..."},
            {"role": "user", "content": "Can I take ibuprofen with warfarin?"},
            {"role": "model", "content": "Let me check that for you."},
        ]

    def test_valid(self):
        assert validate_chat_format(self._valid_conversation()) is True

    def test_empty(self):
        assert validate_chat_format([]) is False

    def test_not_list(self):
        assert validate_chat_format("hello") is False

    def test_no_system_first(self):
        conv = [
            {"role": "user", "content": "hello"},
            {"role": "model", "content": "hi"},
        ]
        assert validate_chat_format(conv) is False

    def test_missing_content(self):
        conv = [
            {"role": "system", "content": "sys"},
            {"role": "user"},
        ]
        assert validate_chat_format(conv) is False

    def test_invalid_role(self):
        conv = [
            {"role": "system", "content": "sys"},
            {"role": "assistant", "content": "oops"},
        ]
        assert validate_chat_format(conv) is False

    def test_multi_turn(self):
        conv = [
            {"role": "system", "content": "sys"},
            {"role": "user", "content": "q1"},
            {"role": "model", "content": "a1"},
            {"role": "user", "content": "q2"},
            {"role": "model", "content": "a2"},
        ]
        assert validate_chat_format(conv) is True


# ---------------------------------------------------------------------------
# validate_tool_calls
# ---------------------------------------------------------------------------

class TestValidateToolCalls:
    def test_valid_tool_call(self):
        conv = [
            {"role": "model", "content": '<tool_call>{"name": "normalize_drug", "arguments": {"name": "advil"}}</tool_call>'},
        ]
        assert validate_tool_calls(conv) is True

    def test_no_tool_calls(self):
        conv = [{"role": "model", "content": "No tools needed."}]
        assert validate_tool_calls(conv) is True

    def test_invalid_json(self):
        conv = [{"role": "model", "content": "<tool_call>{bad json}</tool_call>"}]
        assert validate_tool_calls(conv) is False

    def test_missing_name(self):
        conv = [
            {"role": "model", "content": '<tool_call>{"arguments": {"x": 1}}</tool_call>'},
        ]
        assert validate_tool_calls(conv) is False

    def test_missing_arguments(self):
        conv = [
            {"role": "model", "content": '<tool_call>{"name": "foo"}</tool_call>'},
        ]
        assert validate_tool_calls(conv) is False

    def test_arguments_not_dict(self):
        conv = [
            {"role": "model", "content": '<tool_call>{"name": "foo", "arguments": "bar"}</tool_call>'},
        ]
        assert validate_tool_calls(conv) is False

    def test_multiple_tool_calls(self):
        content = (
            '<tool_call>{"name": "normalize_drug", "arguments": {"name": "aspirin"}}</tool_call>\n'
            '<tool_call>{"name": "check_warnings", "arguments": {"drug_list": ["aspirin"]}}</tool_call>'
        )
        conv = [{"role": "model", "content": content}]
        assert validate_tool_calls(conv) is True


# ---------------------------------------------------------------------------
# reject_and_log
# ---------------------------------------------------------------------------

class TestRejectAndLog:
    def test_writes_to_file(self, tmp_path: Path):
        log_path = tmp_path / "rejected.jsonl"
        with mock.patch("datagen.datagen.validators.REJECTED_LOG", log_path):
            reject_and_log({"id": 1}, "bad format")
            reject_and_log({"id": 2}, "invalid schema")

        lines = log_path.read_text().strip().split("\n")
        assert len(lines) == 2
        first = json.loads(lines[0])
        assert first["reason"] == "bad format"
        assert first["example"] == {"id": 1}
