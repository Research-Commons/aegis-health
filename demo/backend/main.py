"""Aegis Health – FastAPI web demo backend.

Wraps the same Gemma 4 model and tool layer used by the Android app in a
browser-friendly API for hackathon judges who cannot install the APK.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import sys
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

from fastapi import FastAPI, File, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

# ---------------------------------------------------------------------------
# Ensure root project is on sys.path so ``tools.*`` imports work.
# ---------------------------------------------------------------------------
_PROJECT_ROOT = Path(__file__).resolve().parents[2]
if str(_PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(_PROJECT_ROOT))

from demo.backend.tool_dispatcher import (  # noqa: E402
    ToolDispatcher,
    run_agentic_loop,
)

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Global state populated during startup
# ---------------------------------------------------------------------------
_model: Any = None
_tokenizer: Any = None
_dispatcher: ToolDispatcher | None = None

MODEL_ID = os.getenv("AEGIS_MODEL_ID", "google/gemma-3-4b-it")
KB_PATH = os.getenv("AEGIS_KB_PATH", str(_PROJECT_ROOT / "kb" / "output" / "aegis_kb.sqlite"))

SYSTEM_PROMPT = (
    "You are Aegis Health, an on-device medical-safety assistant. "
    "You have access to tools for drug lookup, interaction checking, "
    "medical-term simplification, and USPSTF guideline retrieval. "
    "When you need factual data, emit a <tool_call>{…}</tool_call> block. "
    "Always cite your sources. If unsure, defer to a medical professional."
)


# ---------------------------------------------------------------------------
# Lifespan – load model & dispatcher once
# ---------------------------------------------------------------------------
@asynccontextmanager
async def lifespan(app: FastAPI):
    global _model, _tokenizer, _dispatcher

    _dispatcher = ToolDispatcher(db_path=KB_PATH)

    try:
        from transformers import AutoModelForCausalLM, AutoTokenizer

        logger.info("Loading model %s …", MODEL_ID)
        _tokenizer = AutoTokenizer.from_pretrained(MODEL_ID)
        _model = AutoModelForCausalLM.from_pretrained(
            MODEL_ID,
            device_map="auto",
            torch_dtype="auto",
        )
        logger.info("Model loaded.")
    except Exception as exc:
        logger.warning("Model load failed (%s) – running in tool-only mode.", exc)

    yield


# ---------------------------------------------------------------------------
# App
# ---------------------------------------------------------------------------
app = FastAPI(title="Aegis Health Demo", version="0.1.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ---------------------------------------------------------------------------
# Request / Response schemas
# ---------------------------------------------------------------------------
class DrugSafeRequest(BaseModel):
    drugs: list[str]
    age: int | None = None
    conditions: list[str] | None = None


class ConsentRequest(BaseModel):
    text: str


class HealthRequest(BaseModel):
    age: int
    sex: str
    conditions: list[str] | None = None
    medications: list[str] | None = None
    family_history: list[str] | None = None


class AegisFlag(BaseModel):
    severity: int = Field(ge=1, le=5)
    description: str
    citation: str = ""


class AegisResponseBody(BaseModel):
    flags: list[AegisFlag] = Field(default_factory=list)
    explanation: str = ""
    citations: list[dict[str, str]] = Field(default_factory=list)
    defer_to_professional: bool = False
    raw: str = ""


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
def _generate(prompt: str) -> str:
    """Synchronous model generation (runs in thread pool)."""
    if _model is None or _tokenizer is None:
        return (
            "Model not loaded. Returning a demo response.\n\n"
            "⚠️ Aegis Health is running in **tool-only** mode. "
            "Install a GPU-enabled backend for full inference."
        )

    inputs = _tokenizer(prompt, return_tensors="pt").to(_model.device)
    outputs = _model.generate(
        **inputs,
        max_new_tokens=1024,
        temperature=0.3,
        do_sample=True,
    )
    return _tokenizer.decode(outputs[0], skip_special_tokens=True)


def _build_prompt(user_message: str) -> str:
    return f"<start_of_turn>system\n{SYSTEM_PROMPT}<end_of_turn>\n<start_of_turn>user\n{user_message}<end_of_turn>\n<start_of_turn>model\n"


async def _run_inference(user_message: str) -> AegisResponseBody:
    prompt = _build_prompt(user_message)
    loop = asyncio.get_running_loop()
    raw = await loop.run_in_executor(
        None,
        lambda: run_agentic_loop(_generate, prompt, _dispatcher),
    )

    flags: list[AegisFlag] = []
    try:
        parsed = json.loads(raw)
        if isinstance(parsed, dict):
            for f in parsed.get("flags", []):
                flags.append(AegisFlag(**f))
    except (json.JSONDecodeError, Exception):
        pass

    return AegisResponseBody(
        flags=flags,
        explanation=raw,
        raw=raw,
        defer_to_professional="defer" in raw.lower(),
    )


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------
@app.get("/health")
async def health_check():
    return {
        "status": "ok",
        "model_loaded": _model is not None,
        "kb_path": KB_PATH,
    }


@app.post("/api/drugsafe", response_model=AegisResponseBody)
async def drugsafe(req: DrugSafeRequest):
    drug_list = ", ".join(req.drugs)
    msg = f"Check these drugs for interactions and warnings: {drug_list}."
    if req.age:
        msg += f" Patient age: {req.age}."
    if req.conditions:
        msg += f" Conditions: {', '.join(req.conditions)}."
    return await _run_inference(msg)


@app.post("/api/consent", response_model=AegisResponseBody)
async def consent_text(req: ConsentRequest):
    msg = (
        "Simplify the following medical consent/document text. "
        "Highlight binding clauses, define medical terms in plain language, "
        f"and flag anything the patient should pay attention to:\n\n{req.text}"
    )
    return await _run_inference(msg)


@app.post("/api/consent/upload", response_model=AegisResponseBody)
async def consent_upload(file: UploadFile = File(...)):
    content = (await file.read()).decode("utf-8", errors="replace")
    msg = (
        "Simplify the following medical consent/document text. "
        "Highlight binding clauses, define medical terms in plain language, "
        f"and flag anything the patient should pay attention to:\n\n{content}"
    )
    return await _run_inference(msg)


@app.post("/api/health", response_model=AegisResponseBody)
async def health_partner(req: HealthRequest):
    msg = (
        f"Patient profile — Age: {req.age}, Sex: {req.sex}."
    )
    if req.conditions:
        msg += f" Conditions: {', '.join(req.conditions)}."
    if req.medications:
        msg += f" Current medications: {', '.join(req.medications)}."
    if req.family_history:
        msg += f" Family history: {', '.join(req.family_history)}."
    msg += (
        " Provide a personalized prevention checklist using USPSTF guidelines. "
        "Include grade A and B recommendations. Note what information is missing."
    )
    return await _run_inference(msg)


@app.websocket("/ws/stream")
async def websocket_stream(ws: WebSocket):
    await ws.accept()
    try:
        while True:
            data = await ws.receive_text()
            payload = json.loads(data)
            user_message = payload.get("message", "")
            prompt = _build_prompt(user_message)

            loop = asyncio.get_running_loop()

            async def _stream():
                tokens: list[str] = []

                def callback(chunk: str):
                    tokens.append(chunk)

                raw = await loop.run_in_executor(
                    None,
                    lambda: run_agentic_loop(
                        _generate, prompt, _dispatcher, stream_callback=callback
                    ),
                )
                return raw

            raw = await _stream()

            for i in range(0, len(raw), 80):
                await ws.send_text(json.dumps({"type": "token", "text": raw[i : i + 80]}))
                await asyncio.sleep(0.02)

            await ws.send_text(json.dumps({"type": "done", "text": raw}))

    except WebSocketDisconnect:
        logger.info("WebSocket client disconnected")
    except Exception as exc:
        logger.exception("WebSocket error: %s", exc)
        await ws.close(code=1011)
