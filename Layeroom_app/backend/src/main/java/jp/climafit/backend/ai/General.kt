package jp.climafit.backend.ai

import kotlin.math.abs

/**
 * 服のデータが無いとき用の、気温だけからの「今日のおすすめ服装」。AIは使わず、表と計算だけで決める。
 * 室温から必要な暖かさ(clo)を出し、定番の組み合わせから、暖かさが一番近いものを選ぶ。以前の Python 版 general.py の移植。
 */
object General {
    /** (imageKey, 服の組み合わせ)。imageKey はフロントが持つ画像の種類（light / cardigan / jacket / coat）。 */
    val TEMPLATES: List<Pair<String, List<String>>> = listOf(
        "light" to listOf("tshirt", "shorts"),
        "light" to listOf("tshirt", "pants"),
        "light" to listOf("long_sleeve", "pants"),
        "light" to listOf("shirt", "pants"),
        "cardigan" to listOf("shirt", "cardigan", "pants"),
        "cardigan" to listOf("long_sleeve", "cardigan", "jeans"),
        "cardigan" to listOf("shirt", "knit", "pants"),
        "cardigan" to listOf("hoodie", "jeans"),
        "jacket" to listOf("shirt", "blazer", "pants"),
        "jacket" to listOf("knit", "jacket", "pants"),
        "coat" to listOf("knit", "coat", "pants"),
        "coat" to listOf("sweatshirt", "down", "jeans"),
    )
    private val OUTER_FOR_OUT = listOf("coat" to "コート", "down" to "ダウン", "jacket" to "ジャケット", "cardigan" to "カーディガン")

    data class TypeName(val type: String, val typeJa: String)

    data class Suggestion(
        val imageKey: String,
        val title: String,
        val totalClo: Double,
        val gap: Double,
        val items: List<TypeName>,
        val description: String,
        /** 外で足す1枚（例: コート）。外の気温が分からない・要らないときは null。 */
        val bring: TypeName?,
    )

    /** needIndoor: 教室で必要な暖かさ。outdoorTemp があれば、外で足す1枚も案内する。 */
    fun suggest(needIndoor: Double, indoorTemp: Double, outdoorTemp: Double? = null, limit: Int = 2): List<Suggestion> {
        data class Combo(val key: String, val types: List<String>, val total: Double, val gap: Double)
        val combos = TEMPLATES.map { (key, types) ->
            val total = Comfort.outfitClo(types.map { WornItem(it, "normal") })
            Combo(key, types, total, pyRound(total - needIndoor, 2))
        }.sortedBy { abs(it.gap) }.take(limit)

        var bring: TypeName? = null
        if (outdoorTemp != null) {
            val extra = Comfort.requiredCloOutdoor(outdoorTemp) - combos[0].total
            if (extra > 0.25) {   // 外では教室向けの服だと寒い → 羽織りを足す
                bring = OUTER_FOR_OUT.firstOrNull { (t, _) -> Comfort.itemClo(t, "normal") >= extra - 0.1 }
                    ?.let { TypeName(it.first, it.second) } ?: TypeName("down", "ダウン")
            }
        }
        return combos.map { c ->
            val title = c.types.joinToString(" + ") { Comfort.typeJa(it) }
            var text = "室温${fmt0(indoorTemp)}℃の教室に合う、定番の組み合わせです（$title）。"
            if (bring != null) text += "外は${fmt0(outdoorTemp!!)}℃なので、${bring.typeJa}を持っていくと安心です。教室では脱げます。"
            Suggestion(c.key, title, c.total, c.gap, c.types.map { TypeName(it, Comfort.typeJa(it)) }, text, bring)
        }
    }
}
