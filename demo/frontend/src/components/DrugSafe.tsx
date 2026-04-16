import { useState, useRef, useCallback } from "react";
import ResponseCard, {
  Spinner,
  type AegisResponse,
} from "./ResponseCard";

const API_URL = "/api/drugsafe";
const WS_URL = `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws/stream`;

export default function DrugSafe() {
  const [drugs, setDrugs] = useState("");
  const [age, setAge] = useState("");
  const [conditions, setConditions] = useState("");
  const [loading, setLoading] = useState(false);
  const [response, setResponse] = useState<AegisResponse | null>(null);
  const [streaming, setStreaming] = useState(false);
  const [streamText, setStreamText] = useState("");
  const wsRef = useRef<WebSocket | null>(null);

  const handleSubmit = useCallback(async () => {
    const drugList = drugs
      .split(/[,\n]+/)
      .map((d) => d.trim())
      .filter(Boolean);
    if (drugList.length === 0) return;

    setLoading(true);
    setResponse(null);
    setStreamText("");

    try {
      const res = await fetch(API_URL, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          drugs: drugList,
          age: age ? parseInt(age, 10) : undefined,
          conditions: conditions
            ? conditions.split(",").map((c) => c.trim()).filter(Boolean)
            : undefined,
        }),
      });
      const data: AegisResponse = await res.json();
      setResponse(data);
    } catch {
      setResponse({
        flags: [],
        explanation: "Failed to reach backend. Is the server running?",
        defer_to_professional: false,
      });
    } finally {
      setLoading(false);
    }
  }, [drugs, age, conditions]);

  const handleStream = useCallback(() => {
    const drugList = drugs
      .split(/[,\n]+/)
      .map((d) => d.trim())
      .filter(Boolean);
    if (drugList.length === 0) return;

    setStreaming(true);
    setResponse(null);
    setStreamText("");

    const ws = new WebSocket(WS_URL);
    wsRef.current = ws;

    ws.onopen = () => {
      let msg = `Check these drugs for interactions and warnings: ${drugList.join(", ")}.`;
      if (age) msg += ` Patient age: ${age}.`;
      if (conditions) msg += ` Conditions: ${conditions}.`;
      ws.send(JSON.stringify({ message: msg }));
    };

    ws.onmessage = (ev) => {
      const data = JSON.parse(ev.data);
      if (data.type === "token") {
        setStreamText((prev) => prev + data.text);
      } else if (data.type === "done") {
        setStreaming(false);
        setResponse({
          flags: [],
          explanation: data.text,
          defer_to_professional: false,
        });
        setStreamText("");
        ws.close();
      }
    };

    ws.onerror = () => {
      setStreaming(false);
      setResponse({
        flags: [],
        explanation: "WebSocket connection failed. Falling back to REST.",
        defer_to_professional: false,
      });
    };
  }, [drugs, age, conditions]);

  return (
    <div className="space-y-6">
      {/* Hero section */}
      <div className="text-center mb-2">
        <h2 className="text-2xl font-bold text-gray-900">
          💊 DrugSafe
        </h2>
        <p className="text-gray-500 mt-1">
          Enter medications to check for interactions, warnings, and
          contraindications
        </p>
      </div>

      {/* Input card */}
      <div className="rounded-2xl border border-gray-200 bg-white shadow-sm p-6 space-y-5">
        {/* Drug input */}
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1.5">
            Medications
          </label>
          <textarea
            value={drugs}
            onChange={(e) => setDrugs(e.target.value)}
            placeholder={"Lisinopril\nIbuprofen\nMetformin"}
            rows={4}
            className="w-full rounded-xl border border-gray-300 bg-gray-50 px-4 py-3 text-sm placeholder:text-gray-400 focus:outline-none focus:ring-2 focus:ring-aegis-500 focus:border-transparent transition"
          />
          <p className="text-xs text-gray-400 mt-1">
            One per line or comma-separated
          </p>
        </div>

        {/* Age + Conditions */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">
              Age{" "}
              <span className="text-gray-400 font-normal">(optional)</span>
            </label>
            <input
              type="number"
              value={age}
              onChange={(e) => setAge(e.target.value)}
              placeholder="e.g. 65"
              className="w-full rounded-xl border border-gray-300 bg-gray-50 px-4 py-2.5 text-sm placeholder:text-gray-400 focus:outline-none focus:ring-2 focus:ring-aegis-500 focus:border-transparent transition"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">
              Conditions{" "}
              <span className="text-gray-400 font-normal">(optional)</span>
            </label>
            <input
              type="text"
              value={conditions}
              onChange={(e) => setConditions(e.target.value)}
              placeholder="e.g. diabetes, hypertension"
              className="w-full rounded-xl border border-gray-300 bg-gray-50 px-4 py-2.5 text-sm placeholder:text-gray-400 focus:outline-none focus:ring-2 focus:ring-aegis-500 focus:border-transparent transition"
            />
          </div>
        </div>

        {/* Buttons */}
        <div className="flex gap-3 pt-1">
          <button
            onClick={handleSubmit}
            disabled={loading || !drugs.trim()}
            className="flex-1 rounded-xl bg-gradient-to-r from-aegis-600 to-aegis-700 text-white font-semibold py-3 px-6 text-sm shadow-lg shadow-aegis-600/25 hover:from-aegis-700 hover:to-aegis-800 disabled:opacity-50 disabled:cursor-not-allowed transition-all"
          >
            {loading ? "Checking…" : "Check Interactions"}
          </button>
          <button
            onClick={handleStream}
            disabled={streaming || !drugs.trim()}
            className="rounded-xl border border-aegis-300 text-aegis-700 font-medium py-3 px-5 text-sm hover:bg-aegis-50 disabled:opacity-50 disabled:cursor-not-allowed transition-all"
          >
            {streaming ? "Streaming…" : "Stream ⚡"}
          </button>
        </div>
      </div>

      {/* Results */}
      {loading && <Spinner />}
      <ResponseCard
        response={response}
        streaming={streaming}
        streamText={streamText}
      />
    </div>
  );
}
