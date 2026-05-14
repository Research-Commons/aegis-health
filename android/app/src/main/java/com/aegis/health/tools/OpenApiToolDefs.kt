package com.aegis.health.tools

import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool

/**
 * Flat function declarations for the six Aegis tools, wrapped as LiteRT-LM
 * [OpenApiTool] instances.
 *
 * Registering these with `ConversationConfig.tools` makes the SDK render them
 * into the Gemma 4 system turn as `<|tool>declaration:name{...}<tool|>` blocks
 * — the same format the SFT model was trained on. Without this registration
 * the model never sees a tool catalog in the trained syntax and won't emit
 * `<|tool_call>` markers, so the agent loop exits on turn 1.
 *
 * Dispatch is still manual: `automaticToolCalling = false` in the
 * ConversationConfig, and [ToolDispatcher] handles every call. The
 * [OpenApiTool.execute] implementation here therefore throws — if the SDK
 * ever calls it, that means automaticToolCalling slipped back to true.
 *
 * Schema differs from `tools/tools/tool_defs.json` on purpose. The Python
 * training pipeline wraps each function in OpenAI Chat Completions shape
 * (`{"type":"function","function":{...}}`) because Hugging Face's
 * `apply_chat_template(tools=...)` expects it. The LiteRT-LM SDK's
 * `tool(OpenApiTool)` factory parses each description directly as a flat
 * JsonObject and reads top-level `"name"`, so we omit the wrapper here. The
 * `name` / `description` / `parameters` triple must stay in lockstep with
 * the canonical Python file.
 */
object AegisToolDefs {

    // The LiteRT-LM `tool(OpenApiTool)` factory parses each description as a
    // flat JsonObject and reads top-level "name" via JsonObject.get("name").
    // We therefore strip the OpenAI ChatCompletions {"type","function":{...}}
    // wrapper that `tools/tools/tool_defs.json` uses on the Python/training
    // side — the runtime's templating layer rewraps as needed when emitting
    // the trained `<|tool>declaration:name{...}<tool|>` system-turn block.
    private const val NORMALIZE_DRUG_JSON = """{"name":"normalize_drug","description":"Resolve a drug name (brand name, generic, or common misspelling) to its canonical generic name, RxCUI, and category (OTC / Rx / Controlled / Supplement).","parameters":{"type":"object","properties":{"name":{"type":"string","description":"The drug name to normalize (brand, generic, or misspelled)."}},"required":["name"]}}"""

    private const val DECOMPOSE_PRODUCT_JSON = """{"name":"decompose_product","description":"Break a combination product (e.g. NyQuil, Excedrin) into its individual active ingredients with RxCUI identifiers.","parameters":{"type":"object","properties":{"product_name":{"type":"string","description":"The name of the combination product to decompose."}},"required":["product_name"]}}"""

    private const val GET_DRUG_INFO_JSON = """{"name":"get_drug_info","description":"Retrieve the full drug record (name, class, category, warnings) for a given RxCUI identifier.","parameters":{"type":"object","properties":{"rxcui":{"type":"string","description":"The RxNorm Concept Unique Identifier for the drug."}},"required":["rxcui"]}}"""

    private const val CHECK_WARNINGS_JSON = """{"name":"check_warnings","description":"Analyse a list of drugs for drug-drug interactions, drug-condition contraindications, and special population risks (elderly, pregnancy, pediatric). Returns severity-graded flags and whether the case should be deferred to a healthcare professional.","parameters":{"type":"object","properties":{"drug_list":{"type":"array","items":{"type":"string"},"description":"List of drug names (generic or brand) to check."},"age":{"type":"integer","description":"Patient age in years. Used for elderly (>=65) and pediatric (<12) checks."},"conditions":{"type":"array","items":{"type":"string"},"description":"List of patient conditions (e.g. 'pregnancy', 'liver disease')."}},"required":["drug_list"]}}"""

    private const val LOOKUP_TERM_JSON = """{"name":"lookup_term","description":"Look up a medical term or abbreviation and return a plain-language definition with citation.","parameters":{"type":"object","properties":{"term":{"type":"string","description":"The medical term or abbreviation to define."}},"required":["term"]}}"""

    private const val GET_GUIDELINE_JSON = """{"name":"get_guideline","description":"Retrieve USPSTF preventive-care recommendations (grade A and B) that apply to a patient based on age, sex, and conditions.","parameters":{"type":"object","properties":{"age":{"type":"integer","description":"Patient age in years."},"sex":{"type":"string","enum":["male","female","m","f"],"description":"Patient sex."},"conditions":{"type":"array","items":{"type":"string"},"description":"Optional list of patient conditions for condition-specific screening recommendations."}},"required":["age","sex"]}}"""

    private val normalizeDrug: ToolProvider by lazy {
        tool(ManualDispatchTool("normalize_drug", NORMALIZE_DRUG_JSON))
    }
    private val decomposeProduct: ToolProvider by lazy {
        tool(ManualDispatchTool("decompose_product", DECOMPOSE_PRODUCT_JSON))
    }
    private val getDrugInfo: ToolProvider by lazy {
        tool(ManualDispatchTool("get_drug_info", GET_DRUG_INFO_JSON))
    }
    private val checkWarnings: ToolProvider by lazy {
        tool(ManualDispatchTool("check_warnings", CHECK_WARNINGS_JSON))
    }
    private val lookupTerm: ToolProvider by lazy {
        tool(ManualDispatchTool("lookup_term", LOOKUP_TERM_JSON))
    }
    private val getGuideline: ToolProvider by lazy {
        tool(ManualDispatchTool("get_guideline", GET_GUIDELINE_JSON))
    }

    private val drugSafe: List<ToolProvider> by lazy {
        listOf(normalizeDrug, decomposeProduct, getDrugInfo, checkWarnings)
    }

    private val healthPartner: List<ToolProvider> by lazy {
        listOf(getGuideline)
    }

    /**
     * Fallback catalog for unknown/future modes. Known modes should use the
     * narrowest catalog that can satisfy their prompt contract, which reduces
     * prefill cost and tool-choice ambiguity without changing the model.
     */
    val all: List<ToolProvider> by lazy {
        listOf(normalizeDrug, decomposeProduct, getDrugInfo, checkWarnings, lookupTerm, getGuideline)
    }

    fun forMode(mode: String): List<ToolProvider> {
        return when (mode.lowercase()) {
            "consentreader", "consent" -> emptyList()
            "reportreader" -> emptyList()
            "healthpartner" -> healthPartner
            "drugsafe" -> drugSafe
            else -> all
        }
    }

    private class ManualDispatchTool(
        private val name: String,
        private val descriptionJson: String,
    ) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = descriptionJson

        override fun execute(arguments: String): String {
            error(
                "Tool '$name' was invoked by SDK auto-dispatch, but Aegis dispatches tools " +
                    "manually via ToolDispatcher. Check ConversationConfig.automaticToolCalling.",
            )
        }
    }
}
