# REGULATORY.md — Aegis Health Position Paper

**Status:** Draft, v1 — Phase 1 commit
**Last updated:** 2026-05-13
**Applies to:** Aegis Health Android app (offline, on-device); all four modes — DrugSafe, ConsentReader, HealthPartner, ReportReader.

This document is the regulatory position of Aegis Health. It must be reviewed and committed before any external demonstration of the application (SAFETY-04 ship-block). It is intentionally short, declarative, and citation-anchored. It does NOT constitute legal advice; it documents the technical and product positioning the project relies on to stay within general-wellness regulatory boundaries.

## Position copy (the single sentence Aegis says about itself)

> Aegis Health helps you understand the words and numbers on your lab report, your prescription bottle, your consent form, and your preventive-care checklist. It does not diagnose disease, recommend treatment, or replace medical advice. PDFs and inputs are processed on your device. They are not uploaded to any server.

This is the only sentence used in product copy, demo voiceover, and submission writeup to describe what the app does. Anything more specific drifts toward disease-specificity and triggers the SaMD line (see [SaMD](#samd)).

## SaMD

**Position:** Aegis Health is consumer-facing software intended for general wellness. It does NOT qualify as a Software as a Medical Device (SaMD) under the FDA's 2026 Clinical Decision Support (CDS) guidance because the 520(o)(1)(E) Non-Device CDS exclusion applies only to healthcare-provider-directed software, and Aegis is patient-facing. The applicable path is the **general-wellness exclusion**, which requires the app to stay out of disease-specificity, diagnosis, and treatment recommendations.

**Operational hard lines:**

1. The app never says "you have <disease>" or any declarative-diagnosis variant (`this means`, `indicates`, `confirms`, `consistent with`).
2. The app never recommends a specific treatment, dosage adjustment, or clinical action.
3. The app uses the three-state framing "in range / borderline / outside range — discuss with your doctor". It never uses binary "normal/abnormal" or "good/bad".
4. The app auto-defers on diagnosis-adjacent test types: tumor markers (CA-125, PSA, CEA, CA 19-9, AFP), genetic results, pathology-grade tests. The row never receives an interpretation — only "discuss with your doctor".
5. The ReportReader synthesis turn is constrained by the system prompt to one or two plain-language sentences; it does NOT carry per-row clinical narrative.

**Sources:**
- FDA — [Clinical Decision Support Software FAQs](https://www.fda.gov/medical-devices/software-medical-device-samd/clinical-decision-support-software-frequently-asked-questions-faqs)
- Faegre Drinker — [Key Updates in FDA's 2026 General Wellness and CDS Guidance](https://www.faegredrinker.com/en/insights/publications/2026/1/key-updates-in-fdas-2026-general-wellness-and-clinical-decision-support-software-guidance)

## HIPAA

**Position:** Aegis Health is a consumer app. The developer has no business-associate (BA) relationship with any covered entity. Under HHS guidance on health-app developer scenarios (2016) and the HIPAA Access Right guidance for apps and APIs, **consumer apps that process records the patient themselves uploads from their provider's portal are not subject to HIPAA**. Aegis Health is in this category.

**Operational hard lines:**

1. The Android manifest declares ZERO INTERNET permission (`adb shell dumpsys package com.aegis.health | grep permission` is the verification gate — see CLAUDE.md "Key Constraints").
2. The app never claims "HIPAA-compliant" in user-facing copy. That phrase has no meaning for a non-covered-entity consumer app and invites scrutiny.
3. Privacy copy uses the verifiable claim "PDFs are processed on your device. They are not uploaded to any server." instead of compliance jargon.
4. The project declines business-associate-context partnerships (e.g., "co-branded with our hospital") in v1, because acceptance changes the regulatory posture.

**Sources:**
- HHS — [Health App Use Scenarios and HIPAA (2016)](https://www.hhs.gov/sites/default/files/ocr-health-app-developer-scenarios-2-2016.pdf)
- HHS — [HIPAA Access Right, Health Apps, and APIs](https://www.hhs.gov/hipaa/for-professionals/privacy/guidance/access-right-health-apps-apis/index.html)

## 21st Century Cures Act

**Position:** Under 21st Century Cures (effective 2021), patients already have unfettered portal access to raw lab results — typically before clinician review. Aegis Health is downstream of result release, not the cause of it. The product compares to two pre-existing alternatives:

1. **Without Aegis:** the patient sees raw values with no explanation, often interpreting them through anxiety or Google searches.
2. **With Aegis:** the patient sees the same raw values plus a calm, KB-grounded, defer-to-clinician explanation that auto-defers on diagnosis-adjacent cases.

The second is strictly safer. The framing Aegis uses publicly: "Aegis is downstream of result release. It does not show you anything you couldn't already see in your patient portal; it makes the words easier to understand and tells you when to call your doctor."

**Auto-defer protections** apply to malignancy-revealing results in alignment with California's preemption of parts of the Cures Act for tumor-marker results — Aegis auto-defers on the same test-type list at the row level (see [SaMD](#samd) hard line 4).

**Sources:**
- ACP — [Lab Results Reporting Ethics and the 21st Century Cures Act](https://www.acponline.org/clinical-information/medical-ethics-and-professionalism/ethics-case-studies-education-resources/lab-results-reporting-ethics-and-the-21st-century-cures-act-rule-on-information-blocking)
- JAMA Network Open — [Immediate Release of Test Results](https://jamanetwork.com/journals/jamanetworkopen/fullarticle/2785084)

## Language audit checklist

Phase 5 runs this checklist against all demo copy (video script, in-app strings, submission writeup, marketing material) before recording the final demo. Each item is a hard fail — any one match blocks the demo.

- [ ] **No diagnostic verbs** with disease nouns. Disallowed regex: `(your|this) (result|value|test|number) (indicates|means|shows|confirms|consistent with) (you have|diabetes|cancer|kidney disease|hypothyroidism|anemia|prediabetes|hyperlipidemia|hypertension)`.
- [ ] **No binary good/bad framing.** Disallowed: `good`, `bad`, `normal` (when adjacent to a value), `abnormal`, `fine`, `healthy`, `nothing wrong`, `all clear`. Allowed: `in range`, `outside range`, `borderline`, `discuss with your doctor`.
- [ ] **No "HIPAA-compliant" claim.** Use `Your lab report stays on your device` instead.
- [ ] **No treatment recommendation.** Disallowed verbs adjacent to drug names: `start`, `stop`, `take`, `increase`, `decrease`, `adjust`.
- [ ] **No urgency theatre.** Same urgency-tier language for `URGENT` critical values only; not for slight elevations. (Wired to the `URGENT` severity tier per PITFALLS S5.)
- [ ] **No magnitude anxiety.** Disallowed: `X% above range`, `X times higher than normal`. The UI hides raw delta-from-cutoff (PITFALLS U1).
- [ ] **No predictive language.** Disallowed: `you'll cross`, `you're heading toward`, `at this rate`. (Out-of-scope anti-feature A-5.)
- [ ] **Auto-defer test types named in the SaMD section appear in every demo flow that includes them.** Tumor markers / genetic / pathology rows must visibly route to deferral.
- [ ] **Single position sentence** matches the [position copy](#position-copy-the-single-sentence-aegis-says-about-itself) verbatim. Drift is a fail.

## Open questions

These remain unresolved at Phase 1 commit time and inform Phase 5's final language audit + any future regulatory engagement.

1. **General-wellness exclusion vs. CDS guidance edge cases.** The 2026 FDA CDS guidance is recent; an interpretation that lab-result explanation drifts into CDS would require revisiting the position. Re-check before any commercial launch.
2. **Pediatric range curation provenance.** Mayo Clinic Laboratories publishes pediatric ranges publicly; redistribution-license-clarity per URL is pending per-row review (handled in Plan 04, KB R4 pitfall). Open until KB row curation completes.
3. **CLIA "critical value" patient-facing language.** CLIA-88 mandates clinician-to-clinician critical-value reporting within 30 minutes. Aegis surfaces URGENT-tier flags with same-day-care CTA, NOT the CLIA-defined critical-value reporting language. Re-verify before demo recording.
4. **State-level preemption beyond California.** California preempted parts of Cures Act for malignancy-revealing results. Other states may follow; v1 ships with the auto-defer pattern that is already conservative.
5. **Future "co-branded with hospital" partnership requests.** Decline in v1 per HIPAA section; document the decision point and re-evaluate per partnership.

---

*REGULATORY.md is committed at repo root per D-14 for top-level visibility to reviewers and hackathon judges cloning the repo. Phase 5 runs the [Language audit checklist](#language-audit-checklist) against all demo copy before final demo recording.*
