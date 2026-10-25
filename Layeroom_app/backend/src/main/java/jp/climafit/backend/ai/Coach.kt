package jp.climafit.backend.ai

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 判定結果（計算済みの事実）。AIにはこれを渡して、言い換えてもらうだけ。 */
data class AdviceFacts(
    val mode: String,
    val classroom: String,
    val indoorTemp: Double,
    val outdoorTemp: Double?,
    val outdoorHumidity: Double?,
    val discomfortIndex: Double?,
    val diLabel: String,
    val humidityIsDummy: Boolean,
    val verdict: String,
    val verdictJa: String,
    val needClo: Double,
    val outfitClo: Double,
    val removable: List<String>,
    val items: List<Detected>,
    val outdoor: OutdoorInfo?,
    val preference: String,
    val userMessage: String = "",
) {
    data class Detected(val typeJa: String, val color: String, val thickness: String, val clo: Double)

    /** OpenAI に渡す JSON（Python 版と同じ項目名）。 */
    fun toJson(): JsonObject = buildJsonObject {
        put("mode", mode)
        put("classroom", classroom)
        put("indoor_temp", indoorTemp)
        put("outdoor_temp", outdoorTemp)
        put("outdoor_humidity", outdoorHumidity)
        put("discomfort_index", discomfortIndex)
        put("di_label", diLabel)
        put("humidity_is_dummy", humidityIsDummy)
        put("verdict", verdict)
        put("verdict_ja", verdictJa)
        put("need_clo", needClo)
        put("outfit_clo", outfitClo)
        putJsonArray("removable") { removable.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("items") {
            items.forEach { d ->
                add(buildJsonObject {
                    put("type", d.typeJa); put("color", d.color); put("thickness", d.thickness); put("clo", d.clo)
                })
            }
        }
        put("outdoor", outdoor?.let { o ->
            buildJsonObject {
                put("temp", o.temp); put("humidity", o.humidity); put("di", o.di)
                put("verdict", o.verdict); put("verdict_ja", o.verdictJa); put("need_clo", o.needClo)
            }
        } ?: JsonNull)
        put("preference", preference)
        put("user_message", userMessage.trim().take(300))
    }
}

/** 外での判定（外の気温が分かるときだけ）。 */
data class OutdoorInfo(
    val temp: Double,
    val humidity: Double?,
    val di: Double?,
    val verdict: String,
    val verdictJa: String,
    val needClo: Double,
)

data class Advice(val advice: String, val tip: String)

/**
 * 判定結果をもとに、やさしい文章のアドバイスを書く。以前の Python 版 coach.py の移植。
 * 失敗しても定型文で必ず返す（デモで止まらないため）。
 */
class Coach(private val openAi: OpenAi?) {

    suspend fun write(facts: AdviceFacts): Advice {
        if (openAi == null || !openAi.isConfigured) return template(facts)
        return try {
            val messages = buildJsonArray {
                add(OpenAi.message("system", JsonPrimitive(SYSTEM)))
                add(OpenAi.message("user", JsonPrimitive(facts.toJson().toString())))
            }
            val out = openAi.structured(messages, "advice", SCHEMA)
            val advice = (out["advice"] as? JsonPrimitive)?.content
            val tip = (out["tip"] as? JsonPrimitive)?.content
            if (!advice.isNullOrBlank() && !tip.isNullOrBlank()) Advice(advice, tip) else template(facts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            template(facts)
        }
    }

    companion object {
        fun template(f: AdviceFacts): Advice {
            val t = fmt0(f.indoorTemp)
            val room = f.classroom.ifEmpty { "その教室" }
            var advice = when (f.verdict) {
                "ok" -> "${room}の室温${t}℃に、今の服装はちょうど合っています。"
                "ok_remove" -> {
                    val outer = f.removable.joinToString("・").ifEmpty { "上着" }
                    "${room}は${t}℃で少し暖かめです。${outer}は教室では脱ぐとちょうどよく、外に出るときは着ておくと安心です。"
                }
                "too_hot" -> "${room}の室温${t}℃に対して、少し暑そうです。もう少し薄手にするか、脱ぎ着しやすい重ね方がおすすめです。"
                else -> "${room}の室温${t}℃に対して、少し寒そうです。羽織りものを1枚足すと快適に過ごせそうです。"
            }
            val out = f.outdoor
            if (out != null && out.verdict == "too_cold") advice += "外（${fmt0(out.temp)}℃）では少し寒いかもしれません。"
            else if (out != null && out.verdict == "too_hot") advice += "外（${fmt0(out.temp)}℃）では少し暑いかもしれません。"
            return Advice(advice, Comfort.generalTip(f.needClo))
        }

        private val SCHEMA = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonArray("required") { add(JsonPrimitive("advice")); add(JsonPrimitive("tip")) }
            putJsonObject("properties") {
                putJsonObject("advice") { put("type", "string") }
                putJsonObject("tip") { put("type", "string") }
            }
        }

        private val SYSTEM = """あなたは大学生向けのコーデ相談AIです。渡される『判定結果』は計算済みで正しい前提です。覆さず、言い換えて伝えてください。
- advice: 2〜3文。まず教室の室温の観点から、今の服装が合っているかを伝える。
- tip: 1文。次にどうすればよいか（脱ぎ着、足す・減らす服）。
- 好みの押しつけはしない。好みの要約(preference)があるときだけ軽く触れる。過去の判定や服装の履歴には触れない。
- 外の気温(outdoor_temp)があるときは、外と教室の差にも一言触れる（外では上着が必要か、など）。
- 判定は気温だけで行っている。湿度や不快指数には触れない（discomfort_index が null のとき）。
- outdoor があれば、外では上着が必要か、教室では脱ぐと良いか、という流れで触れる。
- user_message（本人の一言）があるときは、その内容（行き先や不安）に答える形で触れる。
- 教室の判定が「寒そう」(verdict が too_cold)で、外(outdoor)の判定が「暑そう」のときは、「着込む」「重ね着する」とは言わず、「教室用に薄手の羽織り(カーディガンなど)を1枚持って行き、外では脱ぐかバッグに入れておく」と伝える。外の判定が「ちょうどいい」のときは、「教室では羽織りを1枚足すと快適」と伝え、外で脱ぐようには言わない。
- やわらかい丁寧語。絵文字は使わない。"""
    }
}
