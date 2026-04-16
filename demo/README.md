# Aegis Health – Web Demo

Browser-based fallback for judges who cannot install the Android APK.
Wraps the same Gemma 4 model and tool layer in a React + FastAPI interface.

## Quick start (local dev)

### Backend

```bash
cd demo/backend
pip install -r requirements.txt

# Point to the built knowledge base (defaults to kb/output/aegis_kb.sqlite)
export AEGIS_KB_PATH=../../kb/output/aegis_kb.sqlite

uvicorn main:app --reload --port 8000
```

### Frontend

```bash
cd demo/frontend
npm install
npm run dev          # starts on http://localhost:5173
```

Vite proxies `/api/*` and `/ws/*` to the backend automatically.

## Docker Compose (production)

```bash
cd demo
docker compose up --build
```

| Service  | URL                    |
|----------|------------------------|
| Frontend | http://localhost:3000   |
| Backend  | http://localhost:8000   |
| Health   | http://localhost:8000/health |

> **GPU note:** The compose file requests an NVIDIA GPU for the backend.
> Remove the `deploy.resources` block if running CPU-only (inference will be slower).

## Environment variables

| Variable          | Default                            | Description                     |
|-------------------|------------------------------------|---------------------------------|
| `AEGIS_MODEL_ID`  | `google/gemma-3-4b-it`             | HuggingFace model identifier    |
| `AEGIS_KB_PATH`   | `kb/output/aegis_kb.sqlite`        | Path to the knowledge base      |

## Architecture

```
┌──────────────┐        ┌──────────────────┐
│   React SPA  │◄──────►│  FastAPI + Gemma  │
│  (Vite/TS)   │  REST  │  Tool Dispatcher  │
│              │   +    │  aegis_kb.sqlite  │
│  Tailwind UI │  WS    │                  │
└──────────────┘        └──────────────────┘
```

Three feature tabs mirror the Android app:

1. **DrugSafe** – drug interaction checker
2. **Consent Reader** – medical document simplifier
3. **Health Partner** – USPSTF prevention checklist

All inference runs through the same agentic tool loop used on-device.
