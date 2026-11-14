package com.shiraka.wereposition.utils

import java.util.Locale

/** Normalize common pasted notation without inventing or truncating address bytes. */
object MacAddressInput {
    private val compact = Regex("(?i)[0-9a-f]{12}")
    private val colon = Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")
    private val hyphen = Regex("(?i)[0-9a-f]{2}(-[0-9a-f]{2}){5}")
    private val dotted = Regex("(?i)[0-9a-f]{4}(\\.[0-9a-f]{4}){2}")

    fun normalize(value: String): String? {
        val text = value.trim()
        val hex = when {
            text.matches(compact) -> text
            text.matches(colon) -> text.replace(":", "")
            text.matches(hyphen) -> text.replace("-", "")
            text.matches(dotted) -> text.replace(".", "")
            else -> return null
        }
        return hex.uppercase(Locale.ROOT).chunked(2).joinToString(":")
    }
}
