package com.enrpau.pokegeards.detection

/**
 * Turns one OCR'd route-banner string into one pack location id.
 *
 * Why this exists: lumi_plat splits some single in-game zones across several rows
 * ("Route 204 (South)" / "Route 204 (North)", "Oreburgh Gate - 1F" / "- B1F",
 * "Oreburgh Mine B1F" / "B2F"). The banner only ever shows the plain name, which
 * is far more than 2 edits away from any of those row names, so [FuzzyMatch] on
 * full names alone either misses them entirely or lands on a neighbouring route
 * number ("Route 204" is 1 edit from "Route 201").
 *
 * So we also match against each row's *base name* (qualifier stripped). A base-name
 * hit yields a **family** of 2+ rows, and the family is narrowed with a short
 * history of where the player has actually been plus a hand-authored adjacency map.
 *
 * Deliberately plain Kotlin — no Android types — so it is unit-testable.
 */
class LocationResolver(
    private val adjacency: Map<String, Set<String>> = SinnohAdjacency.EDGES,
    private val historyLimit: Int = HISTORY_LIMIT,
) {

    /** Confirmed location ids, oldest first, **most recent last**. Distinct. */
    private val history = ArrayList<Int>()

    fun history(): List<Int> = history.toList()

    fun reset() = history.clear()

    /** Seed history without emitting anything (e.g. after a manual pick). */
    fun remember(id: Int) {
        history.remove(id)
        history.add(id)
        while (history.size > historyLimit) history.removeAt(0)
    }

    /**
     * Resolve [text] against [candidates] (id to full name) and record the result
     * in history. Returns null when nothing matched — history is left untouched.
     */
    fun resolve(text: String, candidates: List<Pair<Int, String>>, maxWords: Int = 4): Int? {
        val family = matchFamily(text, candidates, maxWords)
        if (family.isEmpty()) return null
        val picked = disambiguate(family, candidates)
        remember(picked)
        return picked
    }

    /**
     * The set of rows the banner could plausibly be. Size 1 for an ordinary
     * location, 2+ when the banner named a split family by its base name.
     */
    fun matchFamily(
        text: String,
        candidates: List<Pair<Int, String>>,
        maxWords: Int = 4,
    ): List<Pair<Int, String>> {
        // Same tokenisation as FuzzyMatch.bestPhrase so behaviour is unchanged for
        // plain locations.
        val words = text.replace(Regex("[^A-Za-z0-9' -]"), " ")
            .split(Regex("\\s+")).filter { it.isNotBlank() }
        // Only rows that actually carry a qualifier contribute a base-name variant.
        val baseVariants = candidates.mapNotNull { (id, name) ->
            val base = baseName(name)
            if (base.equals(name, true)) null else id to base
        }
        for (n in maxWords downTo 1) {
            for (i in 0..(words.size - n)) {
                val phrase = words.subList(i, i + n).joinToString(" ")
                familyFor(phrase, candidates, baseVariants)?.let { return it }
            }
        }
        return emptyList()
    }

    private fun familyFor(
        phrase: String,
        candidates: List<Pair<Int, String>>,
        baseVariants: List<Pair<Int, String>>,
    ): List<Pair<Int, String>>? {
        val full = FuzzyMatch.bestScored(phrase, candidates)
        val base = FuzzyMatch.bestScored(phrase, baseVariants)
        // A base-name hit only wins when it is *strictly* closer than the best
        // full-name hit. That keeps every existing full-name outcome intact
        // (including exact matches, which are distance 0 and unbeatable) and only
        // fires where the old matcher was already going to be wrong or empty.
        if (base != null && (full == null || base.dist < full.dist)) {
            val fam = candidates.filter { baseName(it.second).equals(base.name, true) }
            if (fam.isNotEmpty()) return fam
        }
        return full?.let { listOf(it.id to it.name) }
    }

    /**
     * Tie-break: **the most recent informative memory wins.** Walk history newest
     * to oldest and take the first entry that says something:
     *
     *  0. a one-member family is returned as-is;
     *  1. entry *is* a family member -> stay on it. This is the "left Route 204
     *     into the cave and came back" case — we resume the half we were on rather
     *     than re-guessing;
     *  2. entry is adjacent (per the map) to exactly **one** family member -> that
     *     member. Adjacent to none or to several says nothing, so keep walking;
     *  3. nothing in history said anything -> lowest location id, for determinism.
     *
     * Unifying the plan's "adjacency" and "stay put" rules into one recency scan
     * (rather than running one strictly before the other) is deliberate: putting
     * adjacency first makes a B2F -> B1F false flip whenever an older neighbour is
     * still in history, and putting stay-put first makes a family permanently
     * sticky once either half has been seen.
     */
    fun disambiguate(
        family: List<Pair<Int, String>>,
        candidates: List<Pair<Int, String>>,
    ): Int {
        if (family.size == 1) return family[0].first
        val nameById = candidates.associate { it.first to it.second }
        val familyIds = family.map { it.first }.toSet()

        for (i in history.indices.reversed()) {
            val past = history[i]
            if (past in familyIds) return past
            val pastName = nameById[past] ?: continue
            val neighbours = family.filter { isAdjacent(pastName, it.second) }
            if (neighbours.size == 1) return neighbours[0].first
        }

        return family.minOf { it.first }
    }

    private fun isAdjacent(a: String, b: String): Boolean =
        adjacency[a.trim().lowercase()]?.contains(b.trim().lowercase()) == true

    companion object {
        const val HISTORY_LIMIT = 5

        // Suffix shapes actually present in lumi_plat/locations.csv:
        //   " (South)" / " (North)" / " (Outside)" / " (After)" / " (Riley's Room)"
        //   " - 1F" / " - B1F" / " - Area 3" / " - Entrance" / " - d17r0103"
        //   " B1F" / " B2F"           (Oreburgh Mine, no dash)
        private val TRAILING_PAREN = Regex("\\s*\\([^()]*\\)\\s*$")
        private val DASH_QUALIFIER = Regex("\\s+-\\s+.*$")
        private val TRAILING_FLOOR = Regex("\\s+B?\\d+F$", RegexOption.IGNORE_CASE)

        /**
         * "Route 204 (North)" -> "Route 204", "Oreburgh Gate - B1F" -> "Oreburgh
         * Gate", "Oreburgh Mine B1F" -> "Oreburgh Mine". Names with no qualifier
         * come back unchanged.
         */
        fun baseName(name: String): String {
            var s = name.trim()
            s = TRAILING_PAREN.replace(s, "")
            s = DASH_QUALIFIER.replace(s, "")
            s = TRAILING_FLOOR.replace(s, "")
            s = s.trim()
            return if (s.isEmpty()) name.trim() else s
        }
    }
}
