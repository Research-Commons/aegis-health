import { useState } from "react";
import DrugSafe from "./components/DrugSafe";
import ConsentReader from "./components/ConsentReader";
import HealthPartner from "./components/HealthPartner";

const TABS = ["DrugSafe", "ConsentReader", "HealthPartner"] as const;
type Tab = (typeof TABS)[number];

const TAB_META: Record<Tab, { label: string; icon: string; desc: string }> = {
  DrugSafe: {
    label: "DrugSafe",
    icon: "💊",
    desc: "Check drug interactions & warnings",
  },
  ConsentReader: {
    label: "Consent Reader",
    icon: "📄",
    desc: "Simplify medical documents",
  },
  HealthPartner: {
    label: "Health Partner",
    icon: "🩺",
    desc: "Personalized prevention checklist",
  },
};

export default function App() {
  const [activeTab, setActiveTab] = useState<Tab>("DrugSafe");

  return (
    <div className="min-h-screen flex flex-col bg-gradient-to-br from-gray-50 via-white to-aegis-50/30">
      {/* Header */}
      <header className="border-b border-gray-200 bg-white/80 backdrop-blur-md sticky top-0 z-50">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 py-4 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-aegis-500 to-aegis-700 flex items-center justify-center shadow-lg shadow-aegis-500/25">
              <span className="text-white font-bold text-lg">A</span>
            </div>
            <div>
              <h1 className="text-xl font-bold text-gray-900 tracking-tight">
                Aegis Health
              </h1>
              <p className="text-xs text-gray-500 -mt-0.5">
                On-device medical safety assistant
              </p>
            </div>
          </div>

          <span className="hidden sm:inline-flex items-center gap-1.5 text-xs font-medium px-3 py-1.5 rounded-full bg-aegis-50 text-aegis-700 border border-aegis-200">
            <span className="w-1.5 h-1.5 rounded-full bg-aegis-500 animate-pulse" />
            Local · Offline · Web Demo
          </span>
        </div>

        {/* Tab navigation */}
        <div className="max-w-6xl mx-auto px-4 sm:px-6">
          <nav className="flex gap-1 -mb-px" aria-label="Tabs">
            {TABS.map((tab) => {
              const meta = TAB_META[tab];
              const active = tab === activeTab;
              return (
                <button
                  key={tab}
                  onClick={() => setActiveTab(tab)}
                  className={`group flex items-center gap-2 px-4 py-3 text-sm font-medium border-b-2 transition-colors ${
                    active
                      ? "border-aegis-600 text-aegis-700"
                      : "border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300"
                  }`}
                >
                  <span className="text-base">{meta.icon}</span>
                  <span>{meta.label}</span>
                </button>
              );
            })}
          </nav>
        </div>
      </header>

      {/* Content */}
      <main className="flex-1 max-w-6xl w-full mx-auto px-4 sm:px-6 py-8">
        {activeTab === "DrugSafe" && <DrugSafe />}
        {activeTab === "ConsentReader" && <ConsentReader />}
        {activeTab === "HealthPartner" && <HealthPartner />}
      </main>

      {/* Footer */}
      <footer className="border-t border-gray-200 bg-white/60 backdrop-blur-sm">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 py-4 flex flex-col sm:flex-row items-center justify-between gap-2 text-xs text-gray-400">
          <p>
            Aegis Health is a research prototype. Always consult a healthcare
            professional.
          </p>
          <p>
            Powered by Gemma 4 · Runs entirely on-device ·{" "}
            <span className="text-aegis-600 font-medium">Web fallback demo</span>
          </p>
        </div>
      </footer>
    </div>
  );
}
