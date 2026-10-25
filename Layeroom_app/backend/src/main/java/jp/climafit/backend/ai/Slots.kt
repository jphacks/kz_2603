package jp.climafit.backend.ai

/**
 * 保存する服は「上」と「下」の2枚だけ。判定には写っている全部の服を使う。以前の Python 版 slots.py の移植。
 * - 上: 見えている一番外側の服を切り抜く（上着があれば上着）。暖かさ(clo)は、中に着ているものも足した値にする。
 * - 下: ボトムス1つ。
 * - ワンピースは「上」に入れ、下は不要(wholeBody)。
 */
object Slots {
    private val VISIBLE_RANK = mapOf("outer" to 3, "mid" to 2, "top" to 1, "onepiece" to 0)
    private val INNER_ORDER = mapOf("onepiece" to 0, "top" to 1, "mid" to 2, "outer" to 3)

    data class Pick(
        /** "upper" / "lower" */
        val slot: String,
        /** 切り抜く服 */
        val item: WornItem,
        val clo: Double,
        /** 例: 白のTシャツ + 黒のジャケット */
        val detail: String,
        val wholeBody: Boolean,
    )

    private fun clo(i: WornItem) = Comfort.itemClo(i.type, i.thickness)

    fun pick(items: List<WornItem>): List<Pick> {
        val uppers = items.filter { Comfort.layerOf(it.type) in VISIBLE_RANK }
        val bottoms = items.filter { Comfort.layerOf(it.type) == "bottom" }
        val out = mutableListOf<Pick>()
        if (uppers.isNotEmpty()) {
            val visible = uppers.maxWithOrNull(
                compareBy<WornItem> { VISIBLE_RANK.getValue(Comfort.layerOf(it.type)) }.thenBy { it.confidence },
            )!!
            val ordered = uppers.sortedBy { INNER_ORDER.getValue(Comfort.layerOf(it.type)) }
            out += Pick(
                "upper", visible,
                pyRound(ordered.pySumOf { clo(it) }, 3),
                ordered.joinToString(" + ") { "${it.color}の${Comfort.typeJa(it.type)}" },
                uppers.any { Comfort.layerOf(it.type) == "onepiece" } && bottoms.isEmpty(),
            )
        }
        if (bottoms.isNotEmpty()) {
            val low = bottoms.maxByOrNull { it.confidence }!!
            out += Pick("lower", low, pyRound(clo(low), 3), "${low.color}の${Comfort.typeJa(low.type)}", false)
        }
        return out
    }
}
