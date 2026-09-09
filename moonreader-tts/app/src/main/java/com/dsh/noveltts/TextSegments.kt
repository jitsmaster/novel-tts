package com.dsh.noveltts

/**
 * Sentence/paragraph splitting shared by the diagnostic harness and the
 * pre-render queue.
 *
 * The sentence rule must stay in sync with how a reader app like Moon Reader
 * chops text into utterances, because the on-device cache is keyed on the raw
 * utterance string: a pre-rendered sentence only helps if its exact text
 * (including punctuation) matches what the app later sends to the engine.
 */
object TextSegments {

    /** Split on Chinese sentence enders 。 ！ ？ ； and newlines. */
    fun bySentence(raw: String): List<String> {
        val regex = Regex("""[^。！？；
]+[。！？；]?""")
        return regex.findAll(raw)
            .map { it.value.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    /** Split on blank lines (visual paragraphs). */
    fun byParagraph(raw: String): List<String> =
        raw.split(Regex("""
\s*
"""))
            .map { it.replace(Regex("""\s+"""), "").trim() }
            .filter { it.isNotEmpty() }
            .toList()
}
