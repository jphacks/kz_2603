package jp.climafit.backend.ai

import kotlin.math.abs

/** 保存した服（db_server の服 + 種類・色・暖かさ）。 */
data class Garment(
    val id: Long,
    /** "upper" / "lower" */
    val slot: String,
    val type: String,
    val color: String,
    val clo: Double,
    val wholeBody: Boolean = false,
    val thickness: String = "normal",
    val season: String = "all",
    val detail: String = color,
    val up: Int = 0,
    val down: Int = 0,
    val wearCount: Int = 1,
    /** 撮影日（日本時間の YYYY-MM-DD） */
    val takenDate: String? = null,
)

/**
 * 保存した「上」「下」の服から、今日の室温に合う組み合わせを探す（全探索。AIは使わない）。以前の Python 版 suggest.py の移植。
 * 昨日着た服は除外する。重複して保存されていても、同じ見た目（種類×色）の組み合わせは1つにまとめる。
 */
object Suggest {
    const val TOLERANCE = 0.25

    /** 見た目が同じ服を同一視するキー（重複登録されていても昨日の服を確実に除外するため）。 */
    fun signature(g: Garment): Triple<String, String, String> = Triple(g.slot, g.type, g.color)

    private fun itemScore(g: Garment, pref: Preference.Summary): Double {
        var s = (g.up - 2 * g.down) + 0.3 * minOf(g.wearCount, 5)
        if ((pref.colors[g.color] ?: 0.0) > 0) s += 0.5
        return s
    }

    data class Outfit(val totalClo: Double, val garments: List<Garment>)

    fun outfits(
        garments: List<Garment>,
        need: Double,
        excludeSignatures: Set<Triple<String, String, String>>,
        pref: Preference.Summary,
        tol: Double = TOLERANCE,
        limit: Int = 3,
    ): List<Outfit> {
        val usable = garments.filter { signature(it) !in excludeSignatures }
        val uppers = usable.filter { it.slot == "upper" }
        val lowers = usable.filter { it.slot == "lower" }

        data class Found(val score: Double, val combo: List<Garment>, val total: Double)
        val found = mutableListOf<Found>()
        for (u in uppers) {
            val pairs = if (u.wholeBody) listOf(listOf(u)) else lowers.map { listOf(u, it) }
            for (combo in pairs) {
                val total = Comfort.BASE_CLO + combo.pySumOf { it.clo }
                val diff = abs(total - need)
                if (diff <= tol) found += Found(combo.pySumOf { itemScore(it, pref) } - 4 * diff, combo, pyRound(total, 2))
            }
        }
        val out = mutableListOf<Outfit>()
        val seen = mutableSetOf<Set<Triple<String, String, String>>>()
        for (f in found.sortedBy { -it.score }) {
            val key = f.combo.map(::signature).toSet()
            if (!seen.add(key)) continue
            out += Outfit(f.total, f.combo)
            if (out.size >= limit) break
        }
        return out
    }
}

/** 好みの集計。着た回数と 👍/👎 から、よく選ぶ色・種類を出す（AIではなく集計）。以前の Python 版 preference.py の移植。 */
object Preference {
    /** 上＋下で2コーデ分。これ未満は「データなし」扱い（一般的な提案のみ） */
    const val MIN_GARMENTS = 4

    data class Summary(val colors: Map<String, Double>, val types: Map<String, Double>, val text: String)

    fun weight(g: Garment): Double = (g.wearCount + 2 * g.up - 2 * g.down).toDouble()

    fun build(garments: List<Garment>): Summary {
        val colors = linkedMapOf<String, Double>()
        val types = linkedMapOf<String, Double>()
        for (g in garments) {
            val w = weight(g)
            colors[g.color] = (colors[g.color] ?: 0.0) + w
            types[g.type] = (types[g.type] ?: 0.0) + w
        }
        val topC = colors.entries.sortedBy { -it.value }.filter { it.value > 0 }.map { it.key }.take(2)
        val topT = types.entries.sortedBy { -it.value }.filter { it.value > 0 }.map { it.key }.take(2)
        val parts = buildList {
            if (topC.isNotEmpty()) add("よく選ぶ色: " + topC.joinToString("・"))
            if (topT.isNotEmpty()) add("よく選ぶ服: " + topT.joinToString("・") { Comfort.typeJa(it) })
        }
        return Summary(colors, types, if (garments.size >= MIN_GARMENTS) parts.joinToString(" / ") else "")
    }
}
