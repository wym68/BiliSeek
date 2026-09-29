package com.biliseek.core.asr.internal

/** SenseVoice 输出文本清理（工单 AI-7 `AsrTextPolicy`）。 */
internal object AsrTextPolicy {
    private val TAG = Regex("""<\|[^|>]*\|>""")
    private val SPACES = Regex("""\s+""")
    private val REPEATED_PUNCT = Regex("""([，。！？、；：,.!?;:])\1+""")
    private val LEADING_PUNCT = Regex("""^[，。！？、；：,.!?;:\s]+""")

    /** 去 `<|zh|><|NEUTRAL|><|Speech|><|withitn|>` 类标签、规整空白、合并重复标点、去句首标点。 */
    fun clean(raw: String): String = raw
        .replace(TAG, "")
        .replace(SPACES, " ")
        .replace(REPEATED_PUNCT, "$1")
        .trim()
        .replace(LEADING_PUNCT, "")
        .trim()

    /** `<|zh|>` / `zh` → `zh`；空、`nospeech`、`unk` → null。 */
    fun language(raw: String?): String? {
        val lang = raw?.replace(TAG) { it.value.removePrefix("<|").removeSuffix("|>") }?.trim()?.lowercase()
        return lang?.takeIf { it.isNotEmpty() && it != "nospeech" && it != "unk" }
    }
}
