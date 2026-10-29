package com.zimdarselectric.app.data

/** What the app could work out from a photo of a label, box or shelf tag. Every field is a suggestion. */
data class LabelGuess(
    /** Best catalog matches, best first (up to 3). */
    val matches: List<CatalogItem> = emptyList(),
    val brand: String? = null,
    val model: String? = null,
    /** Feet on a roll / coil, e.g. 250. */
    val rollFeet: Int? = null,
    /** Pieces in a pack / box / bag. */
    val packCount: Int? = null,
    /** A price printed on a shelf tag or label. */
    val price: Double? = null,
    val barcodes: List<String> = emptyList(),
    val rawText: String = "",
) {
    val found: Boolean get() = matches.isNotEmpty() || brand != null || model != null || barcodes.isNotEmpty()

    /** Unit to track it in: feet for wire/cable rolls, otherwise the catalog's unit. */
    val unit: String? get() = when {
        rollFeet != null -> "ft"
        else -> matches.firstOrNull()?.unit
    }

    /** "Leviton GFNT2-W · 250 ft roll" style description. */
    val spec: String get() = listOfNotNull(
        listOfNotNull(brand, model).joinToString(" ").ifBlank { null },
        rollFeet?.let { "$it ft roll" },
        packCount?.let { "pack of $it" },
    ).joinToString(" · ")

    /** Cost per unit from the printed price: per foot for rolls, per piece for packs. */
    val unitCost: Double? get() = price?.let { p ->
        when {
            rollFeet != null && rollFeet > 0 -> Math.round(p / rollFeet * 1000) / 1000.0
            packCount != null && packCount > 0 -> Math.round(p / packCount * 100) / 100.0
            else -> p
        }
    }
}

object LabelReader {
    /** Brands you see on electrical supplies. Longer names first so "Square D" wins over "Square". */
    private val brands = listOf(
        "Square D", "Schneider Electric", "Cutler-Hammer", "Pass & Seymour", "Thomas & Betts", "Steel City", "Gardner Bender",
        "Commercial Electric", "Hampton Bay", "Halo", "Juno", "Lithonia", "Leviton", "Eaton", "Siemens", "Hubbell", "Lutron",
        "Legrand", "Carlon", "Southwire", "Cerrowire", "Cerro", "Encore", "Ideal", "Wago", "Raco", "Arlington", "Bridgeport",
        "Halex", "Madison", "Klein", "3M", "Cooper", "Eagle", "Cantex", "Allied", "Wheatland", "Republic", "Intermatic",
        "Bryant", "Milbank", "Erico", "Burndy", "Panduit", "Sylvania", "Philips", "Utilitech", "Kidde", "First Alert",
        "Broan", "NuTone", "Panasonic", "Orbit", "Sigma", "Topaz", "Morris", "Kopex", "AFC", "Romex", "Simpull", "GE",
    )

    /** Text that isn't a model number even though it looks like one. */
    private val notModel = Regex("""^(\d+(A|V|W|VAC|VDC|AMP|AMPS|FT|IN|MM|PK|PC|PCS|CT|HZ|AWG|KCMIL)|UL\d*|CAT\d+E?A?|RG\d+|NEMA|AWG|THHN|THWN-?2?|NM-?B|UF-?B|SER|SEU|USE-?2|XHHW-?2?|CSA|ETL|CUL|TR|WR|GFCI|AFCI|USB|LED|EMT|PVC|RMC|FMC|MC)$""")

    /** Lowercases and rewrites the many ways labels say the same thing, so labels and catalog names compare. */
    fun normalizeText(input: String): String {
        var s = " " + input.lowercase().replace('\n', ' ').replace('’', '\'').replace('”', '"') + " "
        val rules = listOf(
            """(\d+)\s*-\s*(\d+)\s*(w/g|w/grd|w/ground|with ground|wg)""" to "$1/$2 w/ground",
            """\b(\d{1,2})\s*-\s*(\d)\b(?!/)""" to "$1/$2",
            """\bw/\s*g(rd|round)?\b""" to "w/ground",
            """\bwith ground\b""" to "w/ground",
            """\bnm\s*-?\s*b\b""" to "nm-b",
            """\buf\s*-?\s*b\b""" to "uf-b",
            """\bromex\b""" to "nm-b",
            """\bgfi\b""" to "gfci",
            """\bground fault\b""" to "gfci",
            """\barc fault\b""" to "afci",
            """\b(\d+)\s*(amp|amps|ampere|a)\b""" to "$1a",
            """\b(single|1)[\s-]*pole\b|\b1p\b""" to "1-pole",
            """\b(double|two|2)[\s-]*pole\b|\b2p\b""" to "2-pole",
            """\b(three|3)[\s-]*way\b""" to "3-way",
            """\b(four|4)[\s-]*way\b""" to "4-way",
            """\btamper[\s-]*resistant\b|\btamper[\s-]*proof\b""" to "tr",
            """\bweather[\s-]*resistant\b""" to "wr",
            """\bthhn\s*/\s*thwn(-2)?\b|\bthwn(-2)?\b""" to "thhn",
            """\bwago\b""" to "lever",
            """\bset[\s-]*screw\b""" to "set-screw",
            """\bwire\s*nuts?\b""" to "twist-on",
            """\boutlets?\b""" to "receptacle",
            """\b(\d+(?:-\d+/\d+|/\d+)?)\s*(?:"|''|in\b|inch(?:es)?\b)""" to "$1 in",
            """\bsingle[\s-]*gang\b|\b1[\s-]*gang\b""" to "1-gang",
            """\bdouble[\s-]*gang\b|\b2[\s-]*gang\b""" to "2-gang",
            """\b3[\s-]*gang\b""" to "3-gang",
            """\b4[\s-]*gang\b""" to "4-gang",
            """\bsingle[\s-]*pole switch\b""" to "1-pole switch",
        )
        for ((pattern, replacement) in rules) s = Regex(pattern).replace(s, replacement)
        return s.replace(Regex("""[,;:()\[\]|•*]"""), " ").replace(Regex("""\s+"""), " ").trim()
    }

    private val generic = setOf("in", "w/", "&", "-", "of", "a", "the", "with", "and", "style", "bag", "type", "a/c")

    private fun tokens(s: String): List<String> = normalizeText(s).split(' ').filter { it.isNotBlank() && it !in generic }

    /** Words labels often leave off ("cable", "wire"): no penalty when missing. */
    private val soft = setOf("cable", "wire", "copper", "connector", "connectors", "conduit")

    private fun isKey(t: String) = t.any { it.isDigit() }

    /** Scores a catalog item against the label: sizes/amps must match; extra plain words only cost a little. */
    private val tokenCache = java.util.concurrent.ConcurrentHashMap<CatalogItem, List<String>>()

    private fun score(item: CatalogItem, words: Set<String>): Double {
        var score = 0.0
        var plainHits = 0
        for (t in tokenCache.getOrPut(item) { tokens(item.name + " " + item.spec).distinct() }) {
            val hit = t in words
            when {
                isKey(t) -> score += if (hit) 3.0 else -3.0
                t in soft -> if (hit) score += 0.5
                hit -> { score += 1.0; plainHits++ }
                else -> score -= 1.0
            }
        }
        return if (plainHits == 0) -99.0 else score
    }

    fun read(rawText: String, barcodes: List<String> = emptyList(), catalog: List<CatalogItem> = Catalog.items): LabelGuess {
        val text = Regex("""(\d),(\d{3})\b""").replace(rawText, "$1$2")
        val norm = normalizeText(text)
        val words = norm.split(' ').toSet()

        val matches = catalog.map { it to score(it, words) }
            .filter { it.second >= 2.0 }
            .sortedByDescending { it.second }
            .take(3).map { it.first }

        val lower = text.lowercase()
        val brand = brands.firstOrNull { b -> Regex("""(?<![a-z0-9])${Regex.escape(b.lowercase())}(?![a-z0-9])""").containsMatchIn(lower) }
            ?.let { if (it == "Romex" || it == "Simpull") "Southwire" else it }

        val labeledModel = Regex("""(?i)\b(?:model|mod\.|cat\.?\s*(?:no|#)|catalog\s*(?:no|#)?)\s*[#:.]?\s*([A-Z0-9][A-Z0-9-]{2,15})""")
            .find(text)?.groupValues?.get(1)?.uppercase()
        val looseModel = Regex("""\b[A-Z]{1,6}-?\d{1,6}[A-Z0-9-]{0,8}\b""").findAll(text.uppercase())
            .map { it.value.trimEnd('-') }
            .firstOrNull { it.length in 4..14 && !notModel.matches(it) }
        val model = labeledModel ?: looseModel

        val rollFeet = Regex("""(?i)\b(\d{2,4})\s*(?:ft|feet|foot|')(?![a-z])""").findAll(text)
            .map { it.groupValues[1].toInt() }.firstOrNull { it in 25..2500 && it != 10 }

        val packCount = (Regex("""(?i)\b(\d{1,4})\s*-?\s*(?:pk|pack|pcs?|pieces|count|ct)\b""").find(text)?.groupValues?.get(1)
            ?: Regex("""(?i)\b(?:box|bag|pack|jar|carton)\s+of\s+(\d{1,4})\b""").find(text)?.groupValues?.get(1))
            ?.toIntOrNull()?.takeIf { it > 1 }

        val price = Regex("""\$\s?(\d{1,4}(?:,\d{3})*\.\d{2})""").find(text)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

        return LabelGuess(matches, brand, model, rollFeet, packCount, price, barcodes.distinct(), rawText.trim())
    }
}
