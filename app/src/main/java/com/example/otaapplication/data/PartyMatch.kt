package com.example.otaapplication.data

/**
 * Forgiving buyer/supplier name matching, used to find the bills a payment advice can pay.
 *
 * Party names are typed by hand on orders ("Shyam Garments Surat", "M/s Raymond Ltd.") and picked
 * from the catalog on advices ("Shyam Garments", "Raymond"), so an exact match misses real bills.
 * Kept Android-free: the desktop app compiles this file as-is.
 */
object PartyMatch {
    enum class Level { NONE, CLOSE, EXACT }

    private val nonWord = Regex("[^\\p{L}\\p{N}]+")
    private val honorifics = setOf("shree", "shri", "sri", "shre", "shrii")
    private val leading = listOf(listOf("m", "s"), listOf("ms"), listOf("messrs"), listOf("messers"))
    private val trailing = setOf("pvt", "private", "ltd", "limited", "llp", "co", "company", "inc", "corp", "corporation", "and")

    /** The comparable core of a party name: first line, no punctuation, honorifics and company suffixes. */
    fun core(text: String): String {
        var words = SearchText.firstLine(text).lowercase().replace("&", " and ")
            .split(nonWord).filter { it.isNotEmpty() }
            .map { if (it in honorifics) "shri" else it }
        leading.firstOrNull { prefix -> words.size > prefix.size && words.take(prefix.size) == prefix }
            ?.let { words = words.drop(it.size) }
        while (words.size > 1 && words.last() in trailing) words = words.dropLast(1)
        return words.joinToString(" ")
    }

    /**
     * EXACT: same name ignoring case and spacing. CLOSE: same core, same core ignoring spaces, or one
     * name is the other plus extra trailing words (usually the city typed on the same line).
     */
    fun level(a: String, b: String): Level {
        val keyA = SearchText.partyKey(a)
        val keyB = SearchText.partyKey(b)
        if (keyA.isEmpty() || keyB.isEmpty()) return Level.NONE
        if (keyA == keyB) return Level.EXACT
        val coreA = core(a)
        val coreB = core(b)
        if (coreA.isEmpty() || coreB.isEmpty()) return Level.NONE
        if (coreA == coreB || coreA.replace(" ", "") == coreB.replace(" ", "")) return Level.CLOSE
        val wordsA = coreA.split(' ')
        val wordsB = coreB.split(' ')
        val (short, long) = if (wordsA.size <= wordsB.size) wordsA to wordsB else wordsB to wordsA
        val prefix = long.take(short.size) == short && short.joinToString("").length >= 3
        return if (prefix) Level.CLOSE else Level.NONE
    }

    /** A buyer/supplier pair matches as well as its weaker side. */
    fun pairLevel(buyerA: String, supplierA: String, buyerB: String, supplierB: String): Level =
        minOf(level(buyerA, buyerB), level(supplierA, supplierB))

    fun matches(a: String, b: String): Boolean = level(a, b) != Level.NONE
}
