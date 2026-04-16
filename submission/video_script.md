# Aegis Health — Video Script (3 minutes)

## 0:00–0:30 — The Problem

**[Screen: Statistics on dark background with text animations]**

NARRATION:
"Every year, 125,000 Americans die from preventable medication errors. 67% of us self-medicate with over-the-counter drugs — but no one checks if those drugs are safe together. Online tools exist, but they require internet. 24 million rural Americans don't have reliable broadband. And for everyone else, sending your medication list to a server raises serious privacy concerns."

**[Beat]**

"What if the safest pharmacist in your pocket... needed no internet at all?"

## 0:30–1:00 — DrugSafe Demo

**[Screen: Phone showing Aegis Health home screen, then navigating to DrugSafe]**

NARRATION:
"Meet Aegis Health. It runs Gemma 4 entirely on your phone. No cloud. No API calls. No data leaves your device."

**[Demo: User scans a pill bottle of ibuprofen with camera]**

"Scan a pill bottle — ML Kit reads the label, Gemma identifies the drug."

**[Demo: User types 'warfarin' as second drug. Results appear with red severity card]**

"Add warfarin. Instantly, a severity-5 warning: ibuprofen and warfarin together significantly increase bleeding risk. Every warning cites the specific FDA label."

**[Demo: Tap citation badge to expand, showing FDA source text]**

"Tap any citation to see the source. This isn't a hallucination — it's a verified lookup from openFDA data stored on your device."

## 1:00–1:30 — ConsentReader Demo

**[Screen: Navigate to ConsentReader]**

NARRATION:
"Medical consent forms are written at a post-graduate reading level. Patients sign what they can't understand."

**[Demo: Photograph a sample consent form. Simplified version appears]**

"Photograph any consent form. Aegis simplifies it to plain language, highlights medical terms you can tap for definitions..."

**[Demo: Tap a highlighted [TERM] to see definition popup]**

"...and preserves binding clauses verbatim, flagged so you know exactly what you're agreeing to."

**[Demo: Show a [BINDING] clause with visual emphasis]**

## 1:30–2:00 — HealthPartner Demo

**[Screen: Navigate to HealthPartner, fill in profile]**

NARRATION:
"Preventive screenings save lives — but only if you know which ones apply to you."

**[Demo: Enter age 55, male, history of smoking. Checklist appears]**

"Enter your profile. Aegis generates a personalized prevention checklist grounded in USPSTF Grade A and B recommendations. Each item cites a specific USPSTF recommendation ID."

**[Demo: Show checklist items with green grade badges]**

"Lung cancer screening, colorectal cancer screening, statin use for cardiovascular prevention — all backed by the strongest evidence."

## 2:00–2:30 — The Privacy Story

**[Screen: Pull down notification shade, show airplane mode enabled]**

NARRATION:
"Here's the important part."

**[Demo: Enable airplane mode. Run DrugSafe again. It works perfectly]**

"Airplane mode. No WiFi. No cellular. Everything still works. The Gemma 4 model, the FDA knowledge base, the USPSTF recommendations — all on device. Your medication list, your health profile, your consent forms never leave your phone."

**[Demo: Show AndroidManifest.xml briefly — no INTERNET permission]**

"We don't even request the internet permission."

## 2:30–3:00 — Technical Highlights + Close

**[Screen: Split-screen showing architecture diagram and eval metrics table]**

NARRATION:
"Under the hood: Gemma 4 E4B, fine-tuned with LoRA via Unsloth on 1,500 synthetic examples, then aligned with GRPO using four custom safety rewards. Quantized to INT4 at 1.4 gigabytes."

**[Show eval metrics: 98% JSON validity, 99% deferral accuracy, 100% safety boundary]**

"Our model has never — in any evaluation run — output a dosage recommendation or a diagnosis. It cites every claim. It defers to professionals when it should."

**[Screen: Aegis Health logo + tagline]**

"Aegis Health. Because safety shouldn't require a signal."
