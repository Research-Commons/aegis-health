"""Curated drug-drug interactions: a high-confidence safety floor for the KB.

Every entry here is derived from a primary regulatory source (FDA drug label,
FDA Drug Safety Communication, or published clinical pharmacology guideline).
RxCUIs are stable NLM identifiers verified against the rxnorm_lookup table.

Severity scale (consistent with schema.sql CHECK constraint 1–5):
  5 – Contraindicated / life-threatening (e.g., respiratory arrest, fatal bleeding)
  4 – Serious / avoid unless no alternative + close monitoring
  3 – Significant / use with caution, monitor clinical effect
  2 – Moderate / low absolute risk but worth documenting
  1 – Minor / informational

Sources referenced below:
  FDA-WFN  : FDA warfarin prescribing information (Bristol-Myers Squibb, current label)
  FDA-MTX  : FDA methotrexate prescribing information (current label)
  FDA-DSC  : FDA Drug Safety Communication, opioids + benzodiazepines (Aug 2016)
  FDA-SIM  : FDA simvastatin label update (June 2011) re: drug interactions
  FDA-DIG  : FDA digoxin prescribing information (current label)
  FDA-LIT  : FDA lithium label; Ragheb M, JCPH 1990 (NSAIDs + lithium review)
  FDA-SSRI : FDA fluoxetine / sertraline / venlafaxine labels re: serotonin syndrome
  FDA-CLO  : FDA clopidogrel label + Drug Safety Communication (ibuprofen 2007)
  FDA-DOAC : FDA rivaroxaban and apixaban labels (bleeding risk section)
  FDA-ACE  : FDA lisinopril label + ACC/AHA heart failure guidelines
  FDA-QT   : FDA hydroxychloroquine label + ondansetron label (QT section)
  FDA-BUP  : FDA bupropion label (seizure risk section)
"""
from __future__ import annotations

import logging
import sqlite3

log = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Curated interaction data
# Each entry:
#   rxcui_1, name_1  – first drug (RxNorm concept ID + generic name)
#   rxcui_2, name_2  – second drug
#   severity         – 1-5 (see scale above)
#   description      – what happens and why it is dangerous
#   source           – regulatory / guideline citation
# ---------------------------------------------------------------------------

CURATED_INTERACTIONS: list[dict] = [

    # ── Warfarin + NSAIDs ─────────────────────────────────────────────────
    # Warfarin label §7.1: NSAIDs inhibit platelet function and cause GI
    # mucosal injury, compounding anticoagulation risk.  Individual NSAIDs
    # (ibuprofen, naproxen, aspirin) are named explicitly.

    dict(rxcui_1="11289", name_1="warfarin",
         rxcui_2="5640",  name_2="ibuprofen", severity=5,
         description=(
             "Concurrent NSAID use increases risk of serious and potentially "
             "fatal bleeding in anticoagulated patients via dual mechanism: "
             "ibuprofen inhibits platelet COX-1 (reducing platelet aggregation) "
             "and causes GI mucosal injury, both amplifying warfarin's "
             "anticoagulant effect. Avoid combination; if an analgesic is needed, "
             "acetaminophen is preferred."
         ),
         source="FDA-WFN §7.1; Shorr RI et al., Arch Intern Med 1993"),

    dict(rxcui_1="11289", name_1="warfarin",
         rxcui_2="7258",  name_2="naproxen", severity=5,
         description=(
             "Naproxen (NSAID) inhibits platelet COX-1 and causes GI mucosal "
             "injury, substantially increasing bleeding risk in patients on "
             "warfarin. Avoid combination."
         ),
         source="FDA-WFN §7.1"),

    dict(rxcui_1="11289", name_1="warfarin",
         rxcui_2="1191",  name_2="aspirin", severity=4,
         description=(
             "Aspirin at analgesic doses (≥325 mg) combined with warfarin "
             "increases major bleeding risk. Aspirin irreversibly inhibits "
             "platelet COX-1. Note: low-dose aspirin (81 mg) is sometimes "
             "intentionally co-prescribed under medical supervision for "
             "cardiovascular indications — that decision requires physician "
             "oversight and is not appropriate for self-medication."
         ),
         source="FDA-WFN §7.1; Fiore LD et al., Am J Med 1990"),

    # ── Warfarin + CYP2C9 inhibitors ─────────────────────────────────────
    # Warfarin (S-enantiomer, more potent) is primarily metabolised by CYP2C9.
    # Inhibiting CYP2C9 significantly raises warfarin plasma levels → elevated
    # INR → hemorrhage.

    dict(rxcui_1="11289", name_1="warfarin",
         rxcui_2="4450",  name_2="fluconazole", severity=5,
         description=(
             "Fluconazole is a potent CYP2C9 inhibitor.  It substantially "
             "increases S-warfarin plasma levels, causing dangerous INR elevation "
             "and life-threatening hemorrhage.  This interaction has caused "
             "fatalities; it is considered a contraindication in most clinical "
             "guidelines.  If antifungal therapy is unavoidable, intensive INR "
             "monitoring with warfarin dose reduction is required."
         ),
         source="FDA-WFN §7.1; Black DJ et al., J Clin Pharmacol 1996"),

    dict(rxcui_1="11289", name_1="warfarin",
         rxcui_2="203114", name_2="amiodarone hydrochloride", severity=5,
         description=(
             "Amiodarone inhibits CYP2C9 and CYP3A4. It dramatically increases "
             "warfarin levels. Because amiodarone has a half-life of 40–55 days, "
             "this interaction can persist for weeks to months after amiodarone "
             "is discontinued.  INR must be monitored closely and warfarin dose "
             "reduced (typically by 30–50%); combination requires specialist "
             "oversight."
         ),
         source="FDA-WFN §7.1; FDA-DIG; Sanoski CA & Bauman JL, Pharmacotherapy 2002"),

    dict(rxcui_1="11289", name_1="warfarin",
         rxcui_2="103866", name_2="metronidazole benzoate", severity=5,
         description=(
             "Metronidazole inhibits CYP2C9, reducing warfarin clearance and "
             "causing clinically significant INR increases.  Even a short course "
             "of metronidazole can lead to serious bleeding. Warfarin dose "
             "reduction and close INR monitoring are required; avoid if possible."
         ),
         source="FDA-WFN §7.1; Kazmier FJ, Mayo Clin Proc 1976"),

    # ── Opioids + Benzodiazepines (CNS + respiratory depression) ─────────
    # FDA black box warning (Aug 2016): concurrent use of opioids and
    # benzodiazepines causes additive CNS and respiratory depression that has
    # resulted in profound sedation, respiratory depression, coma, and death.

    dict(rxcui_1="7804",  name_1="oxycodone",
         rxcui_2="596",   name_2="alprazolam", severity=5,
         description=(
             "Concurrent opioid + benzodiazepine use causes additive CNS and "
             "respiratory depression. This combination has resulted in profound "
             "sedation, respiratory arrest, coma, and death. Reserve combination "
             "for patients for whom alternative treatments are inadequate; limit "
             "doses and duration; monitor for signs of respiratory depression. "
             "(FDA Black Box Warning, 2016.)"
         ),
         source="FDA-DSC; oxycodone label §5.6"),

    dict(rxcui_1="7804",  name_1="oxycodone",
         rxcui_2="2598",  name_2="clonazepam", severity=5,
         description=(
             "Concurrent opioid + benzodiazepine use causes additive CNS and "
             "respiratory depression with risk of respiratory arrest and death. "
             "(FDA Black Box Warning, 2016.)"
         ),
         source="FDA-DSC; oxycodone label §5.6"),

    # ── Digoxin + Amiodarone ──────────────────────────────────────────────
    # Amiodarone inhibits P-glycoprotein (P-gp) and renal tubular secretion of
    # digoxin, increasing digoxin steady-state levels by 70–100%.  Digoxin has
    # a narrow therapeutic index; toxicity causes life-threatening arrhythmias.

    dict(rxcui_1="3407",  name_1="digoxin",
         rxcui_2="203114", name_2="amiodarone hydrochloride", severity=5,
         description=(
             "Amiodarone inhibits P-glycoprotein and reduces renal tubular "
             "secretion of digoxin, raising digoxin serum levels by 70–100%. "
             "Digoxin has a very narrow therapeutic index; toxicity causes "
             "potentially fatal cardiac arrhythmias, nausea, and visual "
             "disturbances. Reduce digoxin dose by 30–50% when initiating "
             "amiodarone; monitor digoxin levels closely."
         ),
         source="FDA-DIG §7; Fenster PE et al., Am J Cardiol 1985"),

    # ── Simvastatin + CYP3A4 inhibitors / dose limiters ─────────────────
    # FDA updated simvastatin label (June 2011) with dose caps for combinations
    # that raise simvastatin AUC and increase myopathy / rhabdomyolysis risk.

    dict(rxcui_1="36567", name_1="simvastatin",
         rxcui_2="203114", name_2="amiodarone hydrochloride", severity=4,
         description=(
             "FDA dose restriction: simvastatin must not exceed 20 mg/day when "
             "co-administered with amiodarone (simvastatin label §5.1, 2011). "
             "Higher simvastatin doses with amiodarone increase myopathy and "
             "rhabdomyolysis risk. If higher statin doses are required, switch "
             "to a statin not metabolised by CYP3A4 (e.g., pravastatin, "
             "rosuvastatin)."
         ),
         source="FDA-SIM §5.1; FDA Drug Safety Communication June 2011"),

    dict(rxcui_1="36567", name_1="simvastatin",
         rxcui_2="11170", name_2="verapamil", severity=4,
         description=(
             "Verapamil is a moderate CYP3A4 inhibitor that substantially "
             "increases simvastatin AUC.  FDA simvastatin label §5.1 restricts "
             "dose to ≤10 mg/day with verapamil.  Exceeding this limit increases "
             "myopathy and rhabdomyolysis risk."
         ),
         source="FDA-SIM §5.1"),

    dict(rxcui_1="36567", name_1="simvastatin",
         rxcui_2="203211", name_2="diltiazem hydrochloride", severity=3,
         description=(
             "Diltiazem is a moderate CYP3A4 inhibitor that increases simvastatin "
             "exposure.  FDA simvastatin label §5.1 restricts dose to ≤10 mg/day "
             "with diltiazem.  Monitor for unexplained muscle pain, tenderness, "
             "or weakness."
         ),
         source="FDA-SIM §5.1"),

    # ── Serotonin syndrome: SSRI/SNRI + tramadol ─────────────────────────
    # Tramadol inhibits serotonin and norepinephrine reuptake. Combined with
    # SSRIs/SNRIs, it risks serotonin syndrome — a potentially life-threatening
    # condition (agitation, hyperthermia, tremor, myoclonus, seizures).
    # Fluoxetine also inhibits CYP2D6, reducing tramadol conversion to its
    # active opioid metabolite (reducing analgesia while raising serotonin risk).

    dict(rxcui_1="155137", name_1="sertraline hydrochloride",
         rxcui_2="10689",  name_2="tramadol", severity=4,
         description=(
             "Tramadol inhibits serotonin reuptake; combined with an SSRI, "
             "additive serotonergic effects can cause serotonin syndrome "
             "(agitation, hyperthermia, myoclonus, seizures, cardiovascular "
             "instability).  Serotonin syndrome can be life-threatening. "
             "Avoid combination when possible; if used, monitor for early "
             "signs and seek immediate care if they occur."
         ),
         source="FDA-SSRI; Rickli A et al., Eur J Pain 2015"),

    dict(rxcui_1="227224", name_1="fluoxetine hydrochloride",
         rxcui_2="10689",  name_2="tramadol", severity=4,
         description=(
             "Fluoxetine (1) inhibits serotonin reuptake (pharmacodynamic "
             "serotonin syndrome risk with tramadol) and (2) is a potent "
             "CYP2D6 inhibitor (reducing tramadol's O-demethylation to its "
             "active opioid metabolite, potentially reducing analgesia while "
             "elevated serotonin risk persists).  Avoid combination."
         ),
         source="FDA-SSRI; Gillman PK, Anesthesiology 2005"),

    dict(rxcui_1="235988", name_1="venlafaxine hydrochloride",
         rxcui_2="10689",  name_2="tramadol", severity=4,
         description=(
             "Venlafaxine (SNRI) and tramadol both inhibit serotonin reuptake. "
             "Combination increases risk of serotonin syndrome. Several case "
             "reports of serotonin syndrome with this combination have been "
             "published. Avoid; if co-prescribing is unavoidable, use the "
             "lowest effective doses and monitor closely."
         ),
         source="FDA-SSRI; Gardner DM & Lynd LD, Ann Pharmacother 1998"),

    dict(rxcui_1="10737", name_1="trazodone",
         rxcui_2="10689",  name_2="tramadol", severity=3,
         description=(
             "Trazodone has serotonergic activity (serotonin reuptake inhibition "
             "and 5-HT2 antagonism); tramadol also inhibits serotonin reuptake. "
             "Combined serotonergic load raises the risk of serotonin syndrome, "
             "though the absolute risk appears lower than with SSRIs/SNRIs. "
             "Monitor for serotonin toxicity signs."
         ),
         source="FDA-SSRI; Pedavally S et al., Headache 2014"),

    # ── Bupropion + tramadol (seizure threshold) ─────────────────────────
    dict(rxcui_1="203204", name_1="bupropion hydrochloride",
         rxcui_2="10689",  name_2="tramadol", severity=4,
         description=(
             "Both bupropion and tramadol independently lower the seizure "
             "threshold. Combination substantially increases seizure risk. "
             "Bupropion label §5.3 warns against combination with drugs that "
             "lower seizure threshold; tramadol label has a parallel warning. "
             "Avoid combination, especially at higher doses of either drug."
         ),
         source="FDA-BUP §5.3; tramadol label §5.1"),

    # ── Lithium + NSAIDs ─────────────────────────────────────────────────
    # NSAIDs reduce renal prostaglandin synthesis → reduce renal blood flow →
    # reduce lithium clearance.  Can increase lithium levels by 25–60%.
    # Lithium has a very narrow therapeutic index.

    dict(rxcui_1="6448",  name_1="lithium",
         rxcui_2="5640",  name_2="ibuprofen", severity=4,
         description=(
             "NSAIDs reduce renal prostaglandin synthesis, decreasing renal "
             "blood flow and lithium clearance. Ibuprofen can raise lithium "
             "serum levels by 25–60%, risking lithium toxicity: coarse tremor, "
             "confusion, ataxia, cardiac arrhythmias, renal failure, and "
             "seizures. Avoid NSAIDs in patients on lithium; use acetaminophen "
             "instead."
         ),
         source="FDA-LIT; Ragheb M, J Clin Psychopharmacol 1990"),

    dict(rxcui_1="6448",  name_1="lithium",
         rxcui_2="7258",  name_2="naproxen", severity=4,
         description=(
             "Naproxen (NSAID) reduces renal lithium clearance by 25–60% via "
             "prostaglandin inhibition, risking lithium toxicity. Avoid; use "
             "acetaminophen if analgesia is required."
         ),
         source="FDA-LIT; Ragheb M & Bent FG, J Clin Psychiatry 1985"),

    # ── Methotrexate + NSAIDs / aspirin ──────────────────────────────────
    # NSAIDs inhibit renal OAT transporters and compete for tubular secretion
    # of methotrexate. Even at low methotrexate doses (RA/psoriasis), this
    # can cause severe bone marrow suppression, GI toxicity, and nephrotoxicity.

    dict(rxcui_1="6851",  name_1="methotrexate",
         rxcui_2="5640",  name_2="ibuprofen", severity=5,
         description=(
             "NSAIDs inhibit OAT1/OAT3 renal transporters that clear "
             "methotrexate. Ibuprofen can cause severe, potentially fatal "
             "methotrexate toxicity even at low methotrexate doses used for "
             "rheumatoid arthritis: profound bone marrow suppression, "
             "life-threatening GI ulceration, and acute renal failure. "
             "Avoid all NSAIDs in patients on methotrexate."
         ),
         source="FDA-MTX §7; Frenia ML & Long KS, Ann Pharmacother 1992"),

    dict(rxcui_1="6851",  name_1="methotrexate",
         rxcui_2="7258",  name_2="naproxen", severity=5,
         description=(
             "Naproxen inhibits renal methotrexate clearance via OAT transporter "
             "blockade. Risk of severe, potentially fatal methotrexate toxicity "
             "(bone marrow suppression, GI necrosis, renal failure). "
             "Avoid all NSAIDs in patients on methotrexate."
         ),
         source="FDA-MTX §7"),

    dict(rxcui_1="6851",  name_1="methotrexate",
         rxcui_2="1191",  name_2="aspirin", severity=5,
         description=(
             "Aspirin inhibits renal OAT-mediated methotrexate clearance and "
             "reduces plasma protein binding of methotrexate, raising free "
             "drug levels. Risk of severe, potentially fatal toxicity. "
             "Avoid salicylates in patients on methotrexate."
         ),
         source="FDA-MTX §7; Tracy TS et al., Clin Pharmacol Ther 1992"),

    # ── ACE inhibitor + NSAID ─────────────────────────────────────────────
    dict(rxcui_1="29046", name_1="lisinopril",
         rxcui_2="5640",  name_2="ibuprofen", severity=3,
         description=(
             "NSAIDs blunt the antihypertensive and natriuretic effects of ACE "
             "inhibitors by inhibiting renal prostaglandins. In patients with "
             "volume depletion, heart failure, or renal impairment, the "
             "combination can precipitate acute kidney injury (triple whammy: "
             "ACE inhibitor + diuretic + NSAID). Monitor blood pressure and "
             "renal function if combination cannot be avoided."
         ),
         source="FDA-ACE; Heerdink ER et al., Arch Intern Med 1998"),

    # ── ACE inhibitor + potassium-sparing diuretic ────────────────────────
    dict(rxcui_1="29046", name_1="lisinopril",
         rxcui_2="9997",  name_2="spironolactone", severity=4,
         description=(
             "ACE inhibitors reduce aldosterone secretion, causing potassium "
             "retention. Spironolactone blocks aldosterone receptors, also "
             "retaining potassium. Combined, they can cause severe, potentially "
             "life-threatening hyperkalemia (serum K⁺ >6 mmol/L causes "
             "cardiac arrhythmia and cardiac arrest). Risk is highest in "
             "elderly, diabetic, or renally impaired patients. Monitor potassium "
             "closely; combination requires specialist oversight for heart "
             "failure or chronic kidney disease indications."
         ),
         source="FDA-ACE; Juurlink DN et al., NEJM 2004"),

    # ── Clopidogrel + ibuprofen ────────────────────────────────────────────
    dict(rxcui_1="236991", name_1="clopidogrel bisulfate",
         rxcui_2="5640",   name_2="ibuprofen", severity=3,
         description=(
             "Ibuprofen may reduce clopidogrel's antiplatelet effect via "
             "competitive COX-1 binding and CYP2C8 inhibition (reducing "
             "formation of clopidogrel's active thioester metabolite). "
             "Additionally, ibuprofen adds GI bleeding risk. FDA issued a "
             "Drug Safety Communication (2007) advising patients on clopidogrel "
             "to use acetaminophen for pain relief instead of ibuprofen when "
             "possible."
         ),
         source="FDA-CLO; FDA Drug Safety Communication 2007; Catella-Lawson F et al., NEJM 2001"),

    # ── Direct oral anticoagulants (DOACs) + NSAIDs ───────────────────────
    # Same principle as warfarin + NSAIDs: platelet inhibition + GI damage.
    # DOACs may also have P-gp interactions.

    dict(rxcui_1="1114195", name_1="rivaroxaban",
         rxcui_2="5640",    name_2="ibuprofen", severity=4,
         description=(
             "NSAIDs combined with rivaroxaban (factor Xa inhibitor) increase "
             "the risk of serious GI and systemic bleeding through additive "
             "anticoagulant and platelet-inhibiting effects. Avoid concurrent "
             "use of NSAIDs in patients anticoagulated with rivaroxaban."
         ),
         source="FDA-DOAC; rivaroxaban label §7.2"),

    dict(rxcui_1="1364430", name_1="apixaban",
         rxcui_2="5640",    name_2="ibuprofen", severity=4,
         description=(
             "NSAIDs combined with apixaban (factor Xa inhibitor) increase "
             "the risk of serious GI and systemic bleeding. Avoid concurrent "
             "NSAID use in anticoagulated patients."
         ),
         source="FDA-DOAC; apixaban label §7.3"),

    # ── SSRIs/SNRIs + NSAIDs (GI bleeding) ───────────────────────────────
    # SSRIs/SNRIs deplete platelet serotonin, impairing platelet activation.
    # NSAIDs add GI mucosal damage. Multiple cohort studies confirm 3–15×
    # increased upper GI bleeding risk.

    dict(rxcui_1="155137", name_1="sertraline hydrochloride",
         rxcui_2="5640",   name_2="ibuprofen", severity=3,
         description=(
             "SSRIs deplete platelet serotonin, impairing platelet haemostasis. "
             "Combined with an NSAID (GI mucosal damage + platelet inhibition), "
             "the upper GI bleeding risk is 3–15× that of either drug alone. "
             "If analgesia is needed, consider acetaminophen; if NSAID is "
             "required, add a proton pump inhibitor."
         ),
         source="FDA-SSRI; de Abajo FJ et al., BMJ 1999; Loke YK et al., Aliment Pharmacol Ther 2008"),

    dict(rxcui_1="227224", name_1="fluoxetine hydrochloride",
         rxcui_2="5640",   name_2="ibuprofen", severity=3,
         description=(
             "Fluoxetine depletes platelet serotonin; combined with ibuprofen "
             "(GI mucosal damage), upper GI bleeding risk increases substantially. "
             "Prefer acetaminophen for analgesia; add PPI if NSAID is essential."
         ),
         source="FDA-SSRI; Dalton SO et al., Arch Intern Med 2003"),

    dict(rxcui_1="235988", name_1="venlafaxine hydrochloride",
         rxcui_2="5640",   name_2="ibuprofen", severity=3,
         description=(
             "Venlafaxine (SNRI) depletes platelet serotonin, and concurrent "
             "NSAID use increases GI bleeding risk substantially. "
             "Avoid; use acetaminophen for analgesia."
         ),
         source="FDA-SSRI; Loke YK et al., Aliment Pharmacol Ther 2008"),

    # ── Lithium + ACE inhibitor ───────────────────────────────────────────
    # ACE inhibitors reduce aldosterone → increase renal sodium excretion →
    # compensatory proximal tubular sodium (and lithium) reabsorption →
    # elevated lithium serum levels → toxicity risk. Toxicity can cause
    # permanent neurological damage. Multiple controlled studies confirm this.

    dict(rxcui_1="6448",  name_1="lithium",
         rxcui_2="29046", name_2="lisinopril", severity=4,
         description=(
             "ACE inhibitors reduce aldosterone-mediated sodium excretion. "
             "Compensatory proximal tubular sodium reabsorption also retains "
             "lithium, raising serum lithium levels by 30–50%. Lithium has a "
             "very narrow therapeutic index; toxicity causes tremor, confusion, "
             "ataxia, cardiac arrhythmias, and irreversible neurological damage. "
             "Avoid combination when possible; if used, reduce lithium dose, "
             "monitor serum lithium closely, and avoid sodium-restricting diets."
         ),
         source="FDA-LIT; Finley PR et al., Clin Pharmacokinet 1995; "
                "Ragheb M, J Clin Psychiatry 1987"),

    # ── QT-prolonging drug combinations ──────────────────────────────────
    # Both drugs independently prolong cardiac QT interval. Combined
    # QT prolongation increases risk of torsades de pointes, which can
    # degenerate into ventricular fibrillation.

    dict(rxcui_1="153972", name_1="hydroxychloroquine sulfate",
         rxcui_2="203148", name_2="ondansetron hydrochloride", severity=4,
         description=(
             "Both hydroxychloroquine and ondansetron independently prolong the "
             "cardiac QT interval.  Concurrent use increases the risk of "
             "additive QT prolongation and torsades de pointes — a potentially "
             "fatal ventricular arrhythmia.  Avoid combination unless benefit "
             "clearly outweighs risk; if used, obtain baseline ECG, monitor QTc, "
             "correct electrolyte abnormalities (K⁺, Mg²⁺), and use lowest "
             "effective doses."
         ),
         source="FDA-QT; hydroxychloroquine label §5.3; ondansetron label §5.3"),

    # ── MAOI interactions (life-threatening — hypertensive crisis, ──────
    #    serotonin syndrome). Phenelzine and tranylcypromine are the
    #    most commonly prescribed US MAOIs. Nardil (phenelzine) label
    #    Section 4 lists these contraindications explicitly.

    dict(rxcui_1="8123", name_1="phenelzine",
         rxcui_2="221151", name_2="pseudoephedrine tannate", severity=5,
         description=(
             "MAOI + indirect sympathomimetic combination can cause "
             "severe hypertensive crisis (sudden catastrophic rise in "
             "blood pressure, intracranial hemorrhage, death). "
             "Pseudoephedrine releases stored norepinephrine, which MAOIs "
             "prevent from being metabolised — intensely amplifying its "
             "pressor effect. Contraindicated; must not be combined. "
             "A 14-day washout after discontinuing the MAOI is required "
             "before any sympathomimetic is used."
         ),
         source="FDA-MAOI; Nardil (phenelzine) label §4; Livingston MG & Livingston HM, Drug Saf 1996"),

    dict(rxcui_1="8123", name_1="phenelzine",
         rxcui_2="10689", name_2="tramadol", severity=5,
         description=(
             "MAOI + tramadol combination risks severe serotonin "
             "syndrome (hyperthermia, rigidity, autonomic instability, "
             "seizures, death) plus lowered seizure threshold. Tramadol "
             "inhibits serotonin and norepinephrine reuptake; MAOI "
             "inhibits their metabolism — producing catastrophic "
             "monoamine excess. Contraindicated."
         ),
         source="FDA-MAOI; phenelzine label §4.5; tramadol label §4"),

    dict(rxcui_1="8123", name_1="phenelzine",
         rxcui_2="36437", name_2="sertraline", severity=5,
         description=(
             "MAOI + SSRI combination is absolutely contraindicated. "
             "Serotonin syndrome risk is extreme and potentially fatal. "
             "Requires 14 days washout between stopping the MAOI and "
             "starting the SSRI (5 weeks for fluoxetine due to long "
             "half-life of its active metabolite)."
         ),
         source="FDA-MAOI; phenelzine label §4.5; sertraline label §4"),

    dict(rxcui_1="8123", name_1="phenelzine",
         rxcui_2="4493", name_2="fluoxetine", severity=5,
         description=(
             "MAOI + fluoxetine combination is absolutely contraindicated "
             "and has caused fatal serotonin syndrome. Fluoxetine and its "
             "active metabolite norfluoxetine have very long half-lives; "
             "a 5-week washout is required before starting an MAOI after "
             "stopping fluoxetine."
         ),
         source="FDA-MAOI; fluoxetine label §4; Sternbach H, Am J Psychiatry 1991"),

    dict(rxcui_1="8123", name_1="phenelzine",
         rxcui_2="103755", name_2="meperidine hydrochloride", severity=5,
         description=(
             "MAOI + meperidine combination has caused fatal reactions "
             "(hyperpyrexia, rigidity, coma) via severe serotonergic "
             "toxicity. Meperidine is an SNRI in addition to being an "
             "opioid. Contraindicated; all opioids should be used "
             "cautiously with MAOIs and meperidine specifically avoided."
         ),
         source="FDA-MAOI; phenelzine label §4.5; meperidine label §4; Browne B & Linter S, Br J Anaesth 1987"),

    dict(rxcui_1="8123", name_1="phenelzine",
         rxcui_2="236146", name_2="dextromethorphan polistirex", severity=4,
         description=(
             "MAOI + dextromethorphan combination carries serotonin "
             "syndrome risk. Dextromethorphan is a serotonin reuptake "
             "inhibitor at therapeutic doses. Avoid combination; use "
             "alternative cough suppressants (e.g., benzonatate) in "
             "patients on MAOIs."
         ),
         source="FDA-MAOI; phenelzine label §4.5; Rivers N & Horner B, CMAJ 1970"),

    # Tranylcypromine shares all MAOI class effects.
    dict(rxcui_1="10734", name_1="tranylcypromine",
         rxcui_2="221151", name_2="pseudoephedrine tannate", severity=5,
         description=(
             "MAOI + indirect sympathomimetic = hypertensive crisis risk. "
             "See phenelzine + pseudoephedrine — same mechanism and "
             "contraindication applies to all MAOIs including "
             "tranylcypromine."
         ),
         source="FDA-MAOI; Parnate (tranylcypromine) label §4"),

    dict(rxcui_1="10734", name_1="tranylcypromine",
         rxcui_2="36437", name_2="sertraline", severity=5,
         description=(
             "MAOI + SSRI combination is absolutely contraindicated due "
             "to fatal serotonin syndrome risk. 14-day MAOI washout "
             "required before starting any SSRI."
         ),
         source="FDA-MAOI; tranylcypromine label §4"),
]


def build(db_path: str) -> int:
    """Insert curated DDI pairs into the interactions table.

    Only inserts a pair when *both* rxcuis exist in rxnorm_lookup, so this
    function degrades gracefully if the KB was built with a subset of drugs.
    Duplicate pairs are silently skipped (INSERT OR IGNORE).

    Returns the number of rows inserted.
    """
    log.info("Curated DDI: inserting %d curated interaction pairs", len(CURATED_INTERACTIONS))
    conn = sqlite3.connect(db_path)
    inserted = 0
    skipped = 0

    try:
        cur = conn.cursor()
        cur.execute("SELECT rxcui FROM rxnorm_lookup")
        kb_rxcuis: set[str] = {r[0] for r in cur.fetchall()}

        for ix in CURATED_INTERACTIONS:
            r1, r2 = ix["rxcui_1"], ix["rxcui_2"]
            if r1 not in kb_rxcuis or r2 not in kb_rxcuis:
                log.warning(
                    "Curated DDI: skipping %s ↔ %s — one or both rxcuis not in "
                    "rxnorm_lookup (rebuild KB to include these drugs)",
                    ix["name_1"], ix["name_2"],
                )
                skipped += 1
                continue

            try:
                conn.execute(
                    "INSERT OR IGNORE INTO interactions "
                    "(drug_rxcui_1, drug_name_1, drug_rxcui_2, drug_name_2, "
                    " severity, description, source) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (
                        r1, ix["name_1"],
                        r2, ix["name_2"],
                        ix["severity"],
                        ix["description"],
                        ix["source"],
                    ),
                )
                inserted += 1
            except sqlite3.Error as exc:
                log.error("Curated DDI: DB error inserting %s ↔ %s: %s",
                          ix["name_1"], ix["name_2"], exc)

        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()

    log.info(
        "Curated DDI: inserted %d rows, skipped %d (missing rxcui)",
        inserted, skipped,
    )
    return inserted
