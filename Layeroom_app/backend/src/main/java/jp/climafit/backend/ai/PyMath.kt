package jp.climafit.backend.ai

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Python と同じ丸め方。Python の round() と f"{x:.0f}" は、浮動小数点の正確な値を「偶数への丸め」で丸める。
 * Kotlin の Math.round とは結果が違うことがあるので、判定が以前の Python 版 AI とずれないようにこちらを使う。
 */
internal fun pyRound(x: Double, digits: Int): Double =
    BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()

internal fun pyRoundInt(x: Double): Int = BigDecimal(x).setScale(0, RoundingMode.HALF_EVEN).toInt()

/**
 * Python の sum()（3.12 以降）と同じ足し方。浮動小数点は Neumaier の補正つき加算で足すので、
 * 単純に順に足す sumOf とは最後の桁が違うことがあり、それが丸めで 1.66 / 1.67 のような差になる。
 */
internal inline fun <T> Iterable<T>.pySumOf(selector: (T) -> Double): Double {
    var sum = 0.0
    var c = 0.0
    for (e in this) {
        val x = selector(e)
        val t = sum + x
        c += if (kotlin.math.abs(sum) >= kotlin.math.abs(x)) (sum - t) + x else (x - t) + sum
        sum = t
    }
    return if (c != 0.0 && c.isFinite()) sum + c else sum
}

/** f"{x:.0f}"。-0.4 は Python と同じく "-0" になる。 */
internal fun fmt0(x: Double): String {
    val s = BigDecimal(x).setScale(0, RoundingMode.HALF_EVEN).toPlainString()
    return if (x < 0 && s == "0") "-0" else s
}
