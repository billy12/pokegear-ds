package com.enrpau.pokegeards.detection

/**
 * First-char-bucketed Levenshtein best-match, lifted from
 * DualDexAccessibilityService so the route-banner and catch-dialogue OCR passes
 * can reuse it.
 */
object FuzzyMatch {

    /** Best entry in [candidates] (id to name) matching [input], or null. */
    fun best(input: String, candidates: List<Pair<Int, String>>): Pair<Int, String>? {
        val q = input.trim()
        if (q.length < 3) return null
        candidates.firstOrNull { it.second.equals(q, true) }?.let { return it }

        val first = q.first().lowercaseChar()
        var bestPair: Pair<Int, String>? = null
        var bestDist = Int.MAX_VALUE
        for (c in candidates) {
            val name = c.second
            if (name.isEmpty() || name[0].lowercaseChar() != first) continue
            val dist = levenshtein(q.lowercase(), name.lowercase())
            val threshold = if (name.length < 6) 1 else 2
            if (dist <= threshold && dist < bestDist) {
                bestDist = dist
                bestPair = c
            }
        }
        return bestPair
    }

    /** Try to match any whitespace-run of words in [text] (1..maxWords long) against [candidates]. */
    fun bestPhrase(text: String, candidates: List<Pair<Int, String>>, maxWords: Int = 3): Pair<Int, String>? {
        val words = text.replace(Regex("[^A-Za-z0-9' -]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        for (n in maxWords downTo 1) {
            for (i in 0..(words.size - n)) {
                val phrase = words.subList(i, i + n).joinToString(" ")
                best(phrase, candidates)?.let { return it }
            }
        }
        return null
    }

    fun levenshtein(a: CharSequence, b: CharSequence): Int {
        val al = a.length
        var costs = IntArray(al + 1) { it }
        var next = IntArray(al + 1)
        for (i in 1..b.length) {
            next[0] = i
            for (j in 1..al) {
                val sub = costs[j - 1] + if (a[j - 1] == b[i - 1]) 0 else 1
                next[j] = minOf(costs[j] + 1, next[j - 1] + 1, sub)
            }
            val t = costs; costs = next; next = t
        }
        return costs[al]
    }
}
