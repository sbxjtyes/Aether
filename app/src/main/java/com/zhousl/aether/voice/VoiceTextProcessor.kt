package com.zhousl.aether.voice

object VoiceTextProcessor {
    private val fencedCode = Regex("```[\\s\\S]*?```|~~~[\\s\\S]*?~~~")
    private val image = Regex("!\\[[^]]*]\\([^)]*\\)")
    private val link = Regex("\\[([^]]+)]\\([^)]*\\)")
    private val url = Regex("(?i)\\b(?:https?://|www\\.)\\S+")
    private val html = Regex("<[^>]+>")
    private val inlineCode = Regex("`[^`]*`")
    private val markdownPrefix = Regex("(?m)^\\s{0,3}(?:#{1,6}|>|[-+*]|\\d+[.)])\\s+")
    private val emphasis = Regex("[*_~]{1,3}")
    private val whitespace = Regex("[ \\t]+")
    private val blankLines = Regex("\\n{3,}")

    fun toSpeechText(markdown: String): String = markdown
        .replace(fencedCode, "\n")
        .replace(image, " ")
        .replace(link, "$1")
        .replace(inlineCode, " ")
        .replace(url, " ")
        .replace(html, " ")
        .replace(markdownPrefix, "")
        .replace(emphasis, "")
        .replace(whitespace, " ")
        .replace(blankLines, "\n\n")
        .trim()

    fun split(text: String, maxChars: Int = 160): List<String> {
        require(maxChars >= 20)
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        while (start < clean.length) {
            var end = minOf(start + maxChars, clean.length)
            if (end < clean.length) {
                val boundary = clean.substring(start, end).indexOfLast { it in "。！？；.!?;，,\n" }
                if (boundary >= maxChars / 3) end = start + boundary + 1
            }
            clean.substring(start, end).trim().takeIf(String::isNotEmpty)?.let(result::add)
            start = end
        }
        return result
    }
}
