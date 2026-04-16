import { useState, useRef, useCallback } from "react";
import ResponseCard, {
  Spinner,
  type AegisResponse,
} from "./ResponseCard";

const API_TEXT = "/api/consent";
const API_UPLOAD = "/api/consent/upload";

export default function ConsentReader() {
  const [text, setText] = useState("");
  const [loading, setLoading] = useState(false);
  const [response, setResponse] = useState<AegisResponse | null>(null);
  const [fileName, setFileName] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const handleSubmitText = useCallback(async () => {
    if (!text.trim()) return;
    setLoading(true);
    setResponse(null);

    try {
      const res = await fetch(API_TEXT, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ text }),
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
  }, [text]);

  const handleFileUpload = useCallback(
    async (file: File) => {
      setFileName(file.name);
      setLoading(true);
      setResponse(null);

      const form = new FormData();
      form.append("file", file);

      try {
        const res = await fetch(API_UPLOAD, {
          method: "POST",
          body: form,
        });
        const data: AegisResponse = await res.json();
        setResponse(data);
      } catch {
        setResponse({
          flags: [],
          explanation: "Failed to upload file. Is the server running?",
          defer_to_professional: false,
        });
      } finally {
        setLoading(false);
      }
    },
    [],
  );

  const handleDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault();
      const file = e.dataTransfer.files[0];
      if (file) handleFileUpload(file);
    },
    [handleFileUpload],
  );

  return (
    <div className="space-y-6">
      {/* Hero */}
      <div className="text-center mb-2">
        <h2 className="text-2xl font-bold text-gray-900">
          📄 Consent Reader
        </h2>
        <p className="text-gray-500 mt-1">
          Paste or upload a medical document to get a plain-language summary
        </p>
      </div>

      {/* Input card */}
      <div className="rounded-2xl border border-gray-200 bg-white shadow-sm p-6 space-y-5">
        {/* Text input */}
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1.5">
            Paste document text
          </label>
          <textarea
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder="Paste the medical consent form, discharge summary, or clinical document here…"
            rows={8}
            className="w-full rounded-xl border border-gray-300 bg-gray-50 px-4 py-3 text-sm placeholder:text-gray-400 focus:outline-none focus:ring-2 focus:ring-aegis-500 focus:border-transparent transition font-mono leading-relaxed"
          />
        </div>

        {/* Divider */}
        <div className="flex items-center gap-4">
          <div className="flex-1 h-px bg-gray-200" />
          <span className="text-xs text-gray-400 font-medium">OR</span>
          <div className="flex-1 h-px bg-gray-200" />
        </div>

        {/* File upload zone */}
        <div
          onDragOver={(e) => e.preventDefault()}
          onDrop={handleDrop}
          onClick={() => fileRef.current?.click()}
          className="border-2 border-dashed border-gray-300 rounded-xl p-8 text-center cursor-pointer hover:border-aegis-400 hover:bg-aegis-50/30 transition-colors"
        >
          <input
            ref={fileRef}
            type="file"
            accept=".txt,.pdf,.doc,.docx"
            className="hidden"
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) handleFileUpload(file);
            }}
          />
          <div className="w-12 h-12 mx-auto rounded-xl bg-gray-100 flex items-center justify-center mb-3">
            <svg
              className="w-6 h-6 text-gray-400"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={1.5}
                d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"
              />
            </svg>
          </div>
          <p className="text-sm text-gray-600 font-medium">
            {fileName ? (
              <>
                Selected: <span className="text-aegis-700">{fileName}</span>
              </>
            ) : (
              "Drop a file here or click to upload"
            )}
          </p>
          <p className="text-xs text-gray-400 mt-1">
            Supports .txt, .pdf, .doc, .docx
          </p>
        </div>

        {/* Submit */}
        <button
          onClick={handleSubmitText}
          disabled={loading || !text.trim()}
          className="w-full rounded-xl bg-gradient-to-r from-aegis-600 to-aegis-700 text-white font-semibold py-3 px-6 text-sm shadow-lg shadow-aegis-600/25 hover:from-aegis-700 hover:to-aegis-800 disabled:opacity-50 disabled:cursor-not-allowed transition-all"
        >
          {loading ? "Analyzing…" : "Simplify Document"}
        </button>
      </div>

      {/* Results */}
      {loading && <Spinner />}
      <ResponseCard response={response} />
    </div>
  );
}
