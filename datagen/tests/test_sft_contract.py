from __future__ import annotations

import json

import pytest

try:
    from datagen.datagen.sft_contract import (
        convert_record_to_messages,
        render_record_for_sft,
        should_use_tools,
        validate_rendered_text,
    )
except ModuleNotFoundError:
    from datagen.sft_contract import (
        convert_record_to_messages,
        render_record_for_sft,
        should_use_tools,
        validate_rendered_text,
    )


class FakeGemmaTokenizer:
    def apply_chat_template(self, messages, tools=None, tokenize=False, add_generation_prompt=False):
        parts = ["<bos>"]
        for message in messages:
            role = message["role"]
            if role == "tool":
                content = message["content"]
                if isinstance(content, dict):
                    body = ",".join(f"{k}:{self._fmt(v)}" for k, v in sorted(content.items()))
                else:
                    body = f"value:{self._fmt(content)}"
                parts.append(
                    f"<|tool_response>response:{message['name']}{{{body}}}<tool_response|>"
                )
                continue
            gemma_role = "model" if role == "assistant" else role
            parts.append(f"<|turn>{gemma_role}\n")
            for call in message.get("tool_calls", []):
                fn = call["function"]
                args = ",".join(
                    f"{k}:{self._fmt(v)}" for k, v in sorted(fn["arguments"].items())
                )
                parts.append(f"<|tool_call>call:{fn['name']}{{{args}}}<tool_call|>")
            parts.append(message.get("content", ""))
            parts.append("<turn|>\n")
        return "".join(parts)

    def _fmt(self, value):
        if isinstance(value, str):
            return f'<|"|>{value}<|"|>'
        if isinstance(value, bool):
            return "true" if value else "false"
        if isinstance(value, list):
            return "[" + ",".join(self._fmt(v) for v in value) + "]"
        if isinstance(value, dict):
            return "{" + ",".join(f"{k}:{self._fmt(v)}" for k, v in sorted(value.items())) + "}"
        return str(value)


class DropsToolRoleTokenizer(FakeGemmaTokenizer):
    def apply_chat_template(self, messages, tools=None, tokenize=False, add_generation_prompt=False):
        return super().apply_chat_template(
            [message for message in messages if message["role"] != "tool"],
            tools=tools,
            tokenize=tokenize,
            add_generation_prompt=add_generation_prompt,
        )


def _aegis(defer=False):
    return {
        "flags": [
            {"severity": 3, "description": "Needs review.", "citation": "FDA label"}
        ],
        "citations": [{"source": "FDA", "text": "FDA label"}],
        "confidence": 0.9,
        "defer_to_professional": defer,
        "explanation": "Use the final JSON only.",
    }


def _record(mode="drugsafe", category="interaction", user="Can I take aspirin?"):
    final = json.dumps(_aegis(defer=True))
    return {
        "mode": mode,
        "category": category,
        "conversation": [
            {"role": "system", "content": "sys\n\nAvailable tools: []"},
            {"role": "user", "content": user},
            {
                "role": "model",
                "content": '<|tool_call>{"name":"normalize_drug","arguments":{"name":"aspirin"}}<tool_call|>',
            },
            {
                "role": "model",
                "content": '<|tool_result>{"name":"normalize_drug","result":{"generic_name":"aspirin","rxcui":"1191"}}<tool_result|>\n'
                '<|tool_call>{"name":"check_warnings","arguments":{"drug_list":["aspirin"],"age":70}}<tool_call|>',
            },
            {
                "role": "model",
                "content": '<|tool_result>{"name":"check_warnings","result":{"flags":[],"defer_to_professional":true}}<tool_result|>\n'
                + final,
            },
        ],
    }


def test_drugsafe_uses_tool_role_and_native_rendering():
    record = _record()
    messages = convert_record_to_messages(record)
    assert any(m["role"] == "tool" for m in messages)
    assert not any("tool_responses" in m for m in messages)

    rendered = render_record_for_sft(record, FakeGemmaTokenizer(), tool_defs=[])
    assert "<|tool_call>call:normalize_drug" in rendered
    assert "<|tool_response>response:normalize_drug" in rendered
    assert "<|tool_call>{" not in rendered
    assert "<|tool_result>" not in rendered


def test_tool_response_fallback_when_template_drops_tool_role():
    record = _record()
    rendered = render_record_for_sft(record, DropsToolRoleTokenizer(), tool_defs=[])
    assert "<|tool_call>call:normalize_drug" in rendered
    assert "<|tool_response>response:normalize_drug" in rendered
    assert "<|tool_response>response:check_warnings" in rendered


def test_stale_adjacent_tool_call_is_dropped():
    record = _record()
    record["conversation"][2]["content"] = (
        "<|tool_call>{\"name\":\"normalize_drug\","
        "\"arguments\":{\"name\":\"prednisone\"}}<tool_call|>"
    )
    record["conversation"][3]["content"] = (
        "<|tool_call>{\"name\":\"check_warnings\","
        "\"arguments\":{\"drug_list\":[\"prednisone\"],\"age\":6}}<tool_call|>"
    )
    record["conversation"][4]["content"] = (
        "<|tool_result>{\"name\":\"check_warnings\","
        "\"result\":{\"flags\":[],\"defer_to_professional\":true}}<tool_result|>\n"
        + json.dumps(_aegis(defer=True))
    )

    messages = convert_record_to_messages(record)
    assistant_calls = [
        call["function"]["name"]
        for message in messages
        for call in message.get("tool_calls", [])
    ]
    tool_turns = [message for message in messages if message["role"] == "tool"]

    assert assistant_calls == ["check_warnings"]
    assert tool_turns[0]["name"] == "check_warnings"


def test_consentreader_strips_tools_and_keeps_final_json_only():
    record = _record(mode="consent", category="consent_simplification")
    assert should_use_tools(record) is False
    messages = convert_record_to_messages(record)
    assert [m["role"] for m in messages] == ["system", "user", "assistant"]
    assert not any(m.get("tool_calls") for m in messages)
    assert json.loads(messages[-1]["content"])["defer_to_professional"] is True

    rendered = render_record_for_sft(record, FakeGemmaTokenizer(), tool_defs=[])
    assert "<|tool_call>" not in rendered
    assert "<|tool_response>" not in rendered


def test_healthpartner_prevention_keeps_guideline_tools():
    record = _record(
        mode="healthpartner",
        category="prevention",
        user="I am a 55-year-old woman. What screenings should I get?",
    )
    assert should_use_tools(record) is True


def test_healthpartner_symptom_question_is_no_tool():
    record = _record(
        mode="healthpartner",
        category="safety_diagnosis",
        user="Do I have celiac disease if I get bloating after bread?",
    )
    assert should_use_tools(record) is False
    messages = convert_record_to_messages(record)
    assert [m["role"] for m in messages] == ["system", "user", "assistant"]


def test_render_validation_rejects_legacy_markers():
    with pytest.raises(ValueError):
        validate_rendered_text(_record(), '<|tool_call>{"name":"x"}</tool_call>')
    with pytest.raises(ValueError):
        validate_rendered_text(_record(), "<|tool_result>{}</tool_result>")
