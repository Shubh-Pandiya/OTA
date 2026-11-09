package com.example.otaapplication

/**
 * Item rates are typed as free text on the order form. In PDFs a plain rupee amount is printed with
 * two decimals ("60" → "60.00", "1250" → "1,250.00"); anything else ("60 + GST", "as per sample")
 * is printed as typed. Shared by the phone and the desktop app.
 */
object Rates {
    // Optional ₹ / Rs / INR before, optional "/-" after; digits with optional commas and decimals.
    private val PLAIN = Regex("""^(?:₹|rs\.?|inr)?\s*((?:\d[\d,]*)(?:\.\d+)?|\.\d+)\s*(?:/-)?$""", RegexOption.IGNORE_CASE)

    /** The rupee amount of a typed rate, or null when it is not just a number. */
    fun parse(text: String): Double? {
        val match = PLAIN.matchEntire(text.trim()) ?: return null
        return match.groupValues[1].replace(",", "").toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** [text] as printed in a PDF: [money] formats a plain amount, other text stays as typed. */
    fun print(text: String, money: (Double) -> String): String = parse(text)?.let(money) ?: text.trim()
}
