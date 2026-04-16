import { useState, useCallback } from "react";
import ResponseCard, {
  Spinner,
  type AegisResponse,
} from "./ResponseCard";

const API_URL = "/api/health";

const COMMON_CONDITIONS = [
  "Hypertension",
  "Diabetes",
  "Asthma",
  "Heart Disease",
  "High Cholesterol",
  "Obesity",
  "Depression",
  "Anxiety",
  "Arthritis",
  "COPD",
];

export default function HealthPartner() {
  const [age, setAge] = useState("");
  const [sex, setSex] = useState("female");
  const [selectedConditions, setSelectedConditions] = useState<Set<string>>(
    new Set(),
  );
  const [medications, setMedications] = useState("");
  const [familyHistory, setFamilyHistory] = useState("");
  const [loading, setLoading] = useState(false);
  const [response, setResponse] = useState<AegisResponse | null>(null);

  const toggleCondition = (c: string) => {
    setSelectedConditions((prev) => {
      const next = new Set(prev);
      if (next.has(c)) next.delete(c);
      else next.add(c);
      return next;
    });
  };

  const handleSubmit = useCallback(async () => {
    if (!age) return;
    setLoading(true);
    setResponse(null);

    try {
      const res = await fetch(API_URL, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          age: parseInt(age, 10),
          sex,
          conditions: selectedConditions.size
            ? Array.from(selectedConditions)
            : undefined,
          medications: medications
            ? medications.split(",").map((m) => m.trim()).filter(Boolean)
            : undefined,
          family_history: familyHistory
            ? familyHistory.split(",").map((f) => f.trim()).filter(Boolean)
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
  }, [age, sex, selectedConditions, medications, familyHistory]);

  const missingFields: string[] = [];
  if (!age) missingFields.push("Age");
  if (!medications.trim()) missingFields.push("Current medications");
  if (!familyHistory.trim()) missingFields.push("Family history");

  return (
    <div className="space-y-6">
      {/* Hero */}
      <div className="text-center mb-2">
        <h2 className="text-2xl font-bold text-gray-900">
          🩺 Health Partner
        </h2>
        <p className="text-gray-500 mt-1">
          Get personalized prevention recommendations based on USPSTF guidelines
        </p>
      </div>

      {/* Input card */}
      <div className="rounded-2xl border border-gray-200 bg-white shadow-sm p-6 space-y-5">
        {/* Age + Sex */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">
              Age <span className="text-red-400">*</span>
            </label>
            <input
              type="number"
              value={age}
              onChange={(e) => setAge(e.target.value)}
              placeholder="e.g. 45"
              className="w-full rounded-xl border border-gray-300 bg-gray-50 px-4 py-2.5 text-sm placeholder:text-gray-400 focus:outline-none focus:ring-2 focus:ring-aegis-500 focus:border-transparent transition"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">
              Sex <span className="text-red-400">*</span>
            </label>
            <div className="flex gap-2">
              {["female", "male"].map((s) => (
                <button
                  key={s}
                  onClick={() => setSex(s)}
                  className={`flex-1 rounded-xl border py-2.5 text-sm font-medium transition-all ${
                    sex === s
                      ? "border-aegis-500 bg-aegis-50 text-aegis-700 shadow-sm"
                      : "border-gray-300 text-gray-500 hover:border-gray-400"
                  }`}
                >
                  {s === "female" ? "♀ Female" : "♂ Male"}
                </button>
              ))}
            </div>
          </div>
        </div>

        {/* Conditions checkboxes */}
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-2">
            Existing conditions
          </label>
          <div className="flex flex-wrap gap-2">
            {COMMON_CONDITIONS.map((c) => {
              const active = selectedConditions.has(c);
              return (
                <button
                  key={c}
                  onClick={() => toggleCondition(c)}
                  className={`px-3 py-1.5 rounded-full text-xs font-medium border transition-all ${
                    active
                      ? "bg-aegis-100 border-aegis-400 text-aegis-800"
                      : "bg-gray-50 border-gray-200 text-gray-600 hover:border-gray-300"
                  }`}
                >
                  {active && "✓ "}
                  {c}
                </button>
              );
            })}
          </div>
        </div>

        {/* Medications */}
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1.5">
            Current medications{" "}
            <span className="text-gray-400 font-normal">(optional)</span>
          </label>
          <input
            type="text"
            value={medications}
            onChange={(e) => setMedications(e.target.value)}
            placeholder="e.g. Lisinopril, Metformin, Atorvastatin"
            className="w-full rounded-xl border border-gray-300 bg-gray-50 px-4 py-2.5 text-sm placeholder:text-gray-400 focus:outline-none focus:ring-2 focus:ring-aegis-500 focus:border-transparent transition"
          />
        </div>

        {/* Family history */}
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1.5">
            Family history{" "}
            <span className="text-gray-400 font-normal">(optional)</span>
          </label>
          <input
            type="text"
            value={familyHistory}
            onChange={(e) => setFamilyHistory(e.target.value)}
            placeholder="e.g. breast cancer, heart disease, diabetes"
            className="w-full rounded-xl border border-gray-300 bg-gray-50 px-4 py-2.5 text-sm placeholder:text-gray-400 focus:outline-none focus:ring-2 focus:ring-aegis-500 focus:border-transparent transition"
          />
        </div>

        {/* Submit */}
        <button
          onClick={handleSubmit}
          disabled={loading || !age}
          className="w-full rounded-xl bg-gradient-to-r from-aegis-600 to-aegis-700 text-white font-semibold py-3 px-6 text-sm shadow-lg shadow-aegis-600/25 hover:from-aegis-700 hover:to-aegis-800 disabled:opacity-50 disabled:cursor-not-allowed transition-all"
        >
          {loading ? "Analyzing…" : "Get Recommendations"}
        </button>
      </div>

      {/* "What we don't know" callout */}
      {response && missingFields.length > 0 && (
        <div className="rounded-xl border border-amber-200 bg-amber-50 p-4">
          <h4 className="text-sm font-semibold text-amber-800 flex items-center gap-2">
            <svg
              className="w-4 h-4"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 9v2m0 4h.01M12 2a10 10 0 100 20 10 10 0 000-20z"
              />
            </svg>
            What we don't know
          </h4>
          <p className="text-xs text-amber-700 mt-1">
            The following information was not provided and may affect
            recommendations:{" "}
            <span className="font-medium">{missingFields.join(", ")}</span>.
            Providing more details improves accuracy.
          </p>
        </div>
      )}

      {/* Results */}
      {loading && <Spinner />}
      <ResponseCard response={response} />
    </div>
  );
}
