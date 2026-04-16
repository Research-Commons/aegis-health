import { useState } from "react";

/* ------------------------------------------------------------------ */
/*  Shared types                                                       */
/* ------------------------------------------------------------------ */

export interface AegisFlag {
  severity: number;
  description: string;
  citation?: string;
}

export interface AegisCitation {
  source: string;
  text: string;
}

export interface AegisResponse {
  flags: AegisFlag[];
  explanation: string;
  citations?: AegisCitation[];
  defer_to_professional?: boolean;
  raw?: string;
}

/* ------------------------------------------------------------------ */
/*  Severity helpers                                                   */
/* ------------------------------------------------------------------ */

const SEVERITY_CONFIG: Record<
  number,
  { bg: string; border: string; text: string; badge: string; label: string }
> = {
  5: {
    bg: "bg-red-50",
    border: "border-red-300",
    text: "text-red-800",
    badge: "bg-red-600",
    label: "Critical",
  },
  4: {
    bg: "bg-red-50",
    border: "border-red-200",
    text: "text-red-700",
    badge: "bg-red-500",
    label: "High",
  },
  3: {
    bg: "bg-amber-50",
    border: "border-amber-200",
    text: "text-amber-800",
    badge: "bg-amber-500",
    label: "Moderate",
  },
  2: {
    bg: "bg-yellow-50",
    border: "border-yellow-200",
    text: "text-yellow-800",
    badge: "bg-yellow-500",
    label: "Low",
  },
  1: {
    bg: "bg-green-50",
    border: "border-green-200",
    text: "text-green-800",
    badge: "bg-green-600",
    label: "Info",
  },
};

function severityCfg(level: number) {
  return SEVERITY_CONFIG[Math.min(5, Math.max(1, level))] ?? SEVERITY_CONFIG[3];
}

/* ------------------------------------------------------------------ */
/*  Sub-components                                                     */
/* ------------------------------------------------------------------ */

export function WarningCard({ flag }: { flag: AegisFlag }) {
  const cfg = severityCfg(flag.severity);

  return (
    <div
      className={`rounded-xl border ${cfg.border} ${cfg.bg} p-4 transition-shadow hover:shadow-md`}
    >
      <div className="flex items-start gap-3">
        <span
          className={`${cfg.badge} text-white text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full shrink-0 mt-0.5`}
        >
          {cfg.label}
        </span>
        <p className={`text-sm leading-relaxed ${cfg.text}`}>
          {flag.description}
        </p>
      </div>
      {flag.citation && (
        <CitationBadge source={flag.citation} />
      )}
    </div>
  );
}

export function CitationBadge({ source, text }: { source: string; text?: string }) {
  const [open, setOpen] = useState(false);

  return (
    <div className="mt-2">
      <button
        onClick={() => setOpen(!open)}
        className="inline-flex items-center gap-1 text-[11px] font-medium text-gray-500 hover:text-aegis-700 transition-colors"
      >
        <svg
          className="w-3.5 h-3.5"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101"
          />
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M10.172 13.828a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.102 1.101"
          />
        </svg>
        {source}
        <svg
          className={`w-3 h-3 transition-transform ${open ? "rotate-180" : ""}`}
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M19 9l-7 7-7-7"
          />
        </svg>
      </button>
      {open && text && (
        <p className="mt-1 text-xs text-gray-500 bg-white/60 rounded-lg p-2 border border-gray-100">
          {text}
        </p>
      )}
    </div>
  );
}

export function DeferralCard() {
  return (
    <div className="rounded-xl border border-blue-200 bg-blue-50 p-4 flex items-start gap-3">
      <div className="w-8 h-8 rounded-full bg-blue-100 flex items-center justify-center shrink-0">
        <svg
          className="w-4 h-4 text-blue-600"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M13 16h-1v-4h-1m1-4h.01M12 2a10 10 0 100 20 10 10 0 000-20z"
          />
        </svg>
      </div>
      <div>
        <p className="text-sm font-semibold text-blue-800">
          Consult a healthcare professional
        </p>
        <p className="text-xs text-blue-600 mt-0.5">
          This query involves clinical complexity beyond what Aegis can safely
          assess. Please consult your doctor or pharmacist.
        </p>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Loading spinner                                                    */
/* ------------------------------------------------------------------ */

export function Spinner() {
  return (
    <div className="flex items-center justify-center py-12">
      <div className="relative">
        <div className="w-10 h-10 rounded-full border-4 border-aegis-100" />
        <div className="absolute top-0 left-0 w-10 h-10 rounded-full border-4 border-transparent border-t-aegis-600 animate-spin" />
      </div>
      <span className="ml-3 text-sm text-gray-500 font-medium">
        Running inference…
      </span>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Streaming text display                                             */
/* ------------------------------------------------------------------ */

export function StreamingText({ text }: { text: string }) {
  if (!text) return null;
  return (
    <div className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
      <p className="text-sm leading-relaxed text-gray-700 whitespace-pre-wrap">
        {text}
        <span className="inline-block w-2 h-4 bg-aegis-500 ml-0.5 animate-pulse rounded-sm" />
      </p>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Full response renderer                                             */
/* ------------------------------------------------------------------ */

export default function ResponseCard({
  response,
  streaming,
  streamText,
}: {
  response: AegisResponse | null;
  streaming?: boolean;
  streamText?: string;
}) {
  if (streaming && streamText) {
    return <StreamingText text={streamText} />;
  }

  if (!response) return null;

  return (
    <div className="space-y-4 mt-6">
      {response.defer_to_professional && <DeferralCard />}

      {response.flags.length > 0 && (
        <div className="space-y-3">
          <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wider">
            Findings
          </h3>
          {response.flags.map((f, i) => (
            <WarningCard key={i} flag={f} />
          ))}
        </div>
      )}

      {response.explanation && (
        <div className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
          <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wider mb-2">
            Analysis
          </h3>
          <p className="text-sm leading-relaxed text-gray-600 whitespace-pre-wrap">
            {response.explanation}
          </p>
        </div>
      )}

      {response.citations && response.citations.length > 0 && (
        <div className="rounded-xl border border-gray-100 bg-gray-50/50 p-4">
          <h4 className="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">
            Sources
          </h4>
          <div className="space-y-1">
            {response.citations.map((c, i) => (
              <CitationBadge key={i} source={c.source} text={c.text} />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
