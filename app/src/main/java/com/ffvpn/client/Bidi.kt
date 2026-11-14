package com.ffvpn.client

/**
 * Persian text with a Latin word or a number in it is laid out by the Unicode
 * bidirectional algorithm, which reorders the punctuation and spaces around that
 * run: "VPN · کل دستگاه" comes out scrambled, and a count jumps to the wrong side
 * of its noun. Wrapping each left-to-right run in an isolate (U+2066 … U+2069)
 * keeps it one unit in logical order without changing a single word.
 *
 * The isolates are invisible; they only steer the layout. Both characters are
 * Unicode 6.3+, so every Android this app supports renders them as no-ops.
 */
object Bidi {
    private const val LRI = '\u2066'
    private const val PDI = '\u2069'

    /** Words, hosts, versions and counts: what has to stay in left-to-right order. */
    private val LTR_RUN = Regex("[A-Za-z0-9][A-Za-z0-9._:/%+@#&=?()'\\-]*")

    fun isolate(text: String): String =
        // Already isolated (through Texts.ui) or nothing to steer.
        if (text.isEmpty() || text.indexOf(LRI) >= 0) text
        else LTR_RUN.replace(text) { "$LRI${it.value}$PDI" }
}
