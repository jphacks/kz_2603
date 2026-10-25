package jp.climafit.backend.ai

import kotlin.math.abs

/**
 * 判定ロジック（ここは AI ではなくプログラムが決める）。以前の Python 版 comfort.py の移植。
 * - 服ごとに「暖かさ(clo)」を表で決める（AIは服の種類と厚さを答えるだけ）。
 * - 室温から「必要な暖かさ」を式で出す。座って過ごす教室を想定。
 * - 両方を比べて 暑い / ちょうど / 寒い を決める。
 * clo は服の断熱の単位。0.5 ≒ 夏の軽装、1.0 ≒ 普通の室内服、1.5 ≒ 厚着。
 */
object Comfort {
    const val BASE_CLO = 0.15   // 下着・靴下など、写真に写らない分の固定値
    const val TOLERANCE = 0.2   // ±これ以内なら「ちょうど」
    const val TOLERANCE_OUT = 0.3   // 屋外は少し広めに許容

    data class GarmentType(val ja: String, val clo: Double, val layer: String)

    /** type → (日本語名, clo, 層)。順番は OpenAI に渡す列挙の順番にもなる。 */
    val GARMENT_TYPES: Map<String, GarmentType> = linkedMapOf(
        "tshirt" to GarmentType("Tシャツ", 0.09, "top"),
        "long_sleeve" to GarmentType("長袖Tシャツ", 0.20, "top"),
        "shirt" to GarmentType("シャツ", 0.25, "top"),
        "sweatshirt" to GarmentType("トレーナー", 0.35, "mid"),
        "hoodie" to GarmentType("パーカー", 0.40, "mid"),
        "knit" to GarmentType("ニット", 0.35, "mid"),
        "cardigan" to GarmentType("カーディガン", 0.30, "mid"),
        "vest" to GarmentType("ベスト", 0.15, "mid"),
        "blazer" to GarmentType("薄手ジャケット", 0.35, "outer"),
        "jacket" to GarmentType("ジャケット", 0.50, "outer"),
        "coat" to GarmentType("コート", 0.70, "outer"),
        "down" to GarmentType("ダウン", 0.90, "outer"),
        "shorts" to GarmentType("ショートパンツ", 0.06, "bottom"),
        "jeans" to GarmentType("ジーンズ", 0.25, "bottom"),
        "pants" to GarmentType("パンツ", 0.20, "bottom"),
        "sweatpants" to GarmentType("スウェットパンツ", 0.28, "bottom"),
        "skirt" to GarmentType("スカート", 0.15, "bottom"),
        "dress" to GarmentType("ワンピース", 0.30, "onepiece"),
        "accessory" to GarmentType("小物", 0.05, "other"),
    )

    val THICKNESS_FACTOR = mapOf("thin" to 0.8, "normal" to 1.0, "thick" to 1.25)
    val THICKNESS_JA = mapOf("thin" to "薄手", "normal" to "普通", "thick" to "厚手")

    val VERDICT_JA = mapOf(
        "ok" to "ちょうどいい",
        "ok_remove" to "ちょうどいい（上着を脱げば）",
        "too_hot" to "暑そう",
        "too_cold" to "寒そう",
    )
    val OUT_VERDICT_JA = mapOf("ok" to "外ではちょうどいい", "too_hot" to "外では暑そう", "too_cold" to "外では寒そう")

    fun typeJa(t: String): String = GARMENT_TYPES[t]?.ja ?: t

    fun layerOf(t: String): String = GARMENT_TYPES[t]?.layer ?: "other"

    fun itemClo(type: String, thickness: String = "normal"): Double {
        val base = GARMENT_TYPES.getValue(type).clo
        return pyRound(base * (THICKNESS_FACTOR[thickness] ?: 1.0), 3)
    }

    /** 不快指数 DI = 0.81T + 0.01H(0.99T - 14.3) + 46.3 */
    fun discomfortIndex(temp: Double, humidity: Double): Double =
        pyRound(0.81 * temp + 0.01 * humidity * (0.99 * temp - 14.3) + 46.3, 1)

    fun diLabel(di: Double): String {
        val table = listOf(
            55.0 to "寒い", 60.0 to "肌寒い", 65.0 to "何も感じない", 70.0 to "快適",
            75.0 to "暑くない", 80.0 to "やや暑い", 85.0 to "暑くて汗が出る",
        )
        return table.firstOrNull { di < it.first }?.second ?: "暑くてたまらない"
    }

    /**
     * 必要な暖かさ。座位(1.2met)の快適温度の目安から線形で近似。
     * 18.5℃→1.5clo / 22℃→1.0clo / 25.5℃→0.5clo。湿度が本物のときだけ、不快指数で少し補正する。
     */
    fun requiredClo(indoorTemp: Double, di: Double? = null, humidityIsDummy: Boolean = true): Double {
        var need = 0.5 + (25.5 - indoorTemp) / 7.0
        if (di != null && !humidityIsDummy) need -= maxOf(-0.2, minOf(0.2, 0.04 * (di - 72)))
        return pyRound(maxOf(0.3, minOf(2.0, need)), 2)
    }

    /** 屋外で必要な暖かさ。歩くので、室内より気温の変化に対して必要量の増え方がゆるやか。 */
    fun requiredCloOutdoor(outdoorTemp: Double): Double =
        pyRound(maxOf(0.3, minOf(2.0, 0.7 + (20.0 - outdoorTemp) / 14.0)), 2)

    fun outfitClo(items: List<WornItem>): Double =
        pyRound(BASE_CLO + items.pySumOf { itemClo(it.type, it.thickness) }, 2)

    data class OutdoorJudge(val verdict: String, val verdictJa: String, val needClo: Double)

    fun judgeOutdoor(totalClo: Double, needOut: Double): OutdoorJudge {
        val diff = pyRound(totalClo - needOut, 2)
        val v = if (diff > TOLERANCE_OUT) "too_hot" else if (diff < -TOLERANCE_OUT) "too_cold" else "ok"
        return OutdoorJudge(v, OUT_VERDICT_JA.getValue(v), needOut)
    }

    private fun score(diff: Double): Int = pyRoundInt(maxOf(0.0, 100 - maxOf(0.0, abs(diff) - 0.05) * 120))

    data class Judge(
        val verdict: String,
        val verdictJa: String,
        val score: Int,
        val outfitClo: Double,
        val needClo: Double,
        val diff: Double,
        val withoutOuterClo: Double,
        /** 「上着を脱げば」のとき、脱ぐ服の日本語名。 */
        val removable: List<String>,
    )

    /** 上着(outer)を脱いだ場合も見る。 */
    fun judge(items: List<WornItem>, need: Double): Judge {
        val total = outfitClo(items)
        val diff = pyRound(total - need, 2)
        val outers = items.filter { layerOf(it.type) == "outer" }
        val outerClo = outers.pySumOf { itemClo(it.type, it.thickness) }
        val withoutOuter = pyRound(total - outerClo, 2)
        val (verdict, s) = when {
            diff > TOLERANCE ->
                if (outers.isNotEmpty() && abs(withoutOuter - need) <= TOLERANCE) "ok_remove" to minOf(90, score(withoutOuter - need))
                else "too_hot" to score(diff)
            diff < -TOLERANCE -> "too_cold" to score(diff)
            else -> "ok" to score(diff)
        }
        return Judge(
            verdict, VERDICT_JA.getValue(verdict), s, total, need, diff, withoutOuter,
            if (verdict == "ok_remove") outers.map { typeJa(it.type) } else emptyList(),
        )
    }

    /** データがない最初の頃の一般的な目安。 */
    fun generalTip(need: Double): String = when {
        need < 0.6 -> "半袖＋薄手のパンツくらいで十分です。"
        need < 0.8 -> "長袖または半袖に、薄手の羽織りがあると安心です。"
        need < 1.1 -> "長袖＋薄手の上着くらいがちょうどいいです。"
        need < 1.4 -> "トレーナーやニットに上着を重ねるのがおすすめです。"
        else -> "厚手の上着が必要です。重ね着で調整しましょう。"
    }
}

/** 写真に写っていた服（AIが読み取ったもの）。bbox は画像全体を 0〜1 とした四角。 */
data class WornItem(
    val type: String,
    val thickness: String = "normal",
    val color: String = "",
    val season: String = "all",
    val confidence: Double = 0.0,
    val bbox: Box? = null,
)

/** 正規化座標の四角（x, y は左上、w, h は幅と高さ）。 */
data class Box(val x: Double, val y: Double, val w: Double, val h: Double)
