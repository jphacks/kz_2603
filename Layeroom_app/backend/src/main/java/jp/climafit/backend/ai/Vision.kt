package jp.climafit.backend.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

class VisionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * OpenAIに写真を見せて、服の種類・色・厚さ・四角を構造化JSONで返してもらう。以前の Python 版 vision.py の移植。
 * AIが答えるのは「見えたもの」だけ。暑い/寒いの判定は Comfort（プログラム）が行う。
 */
interface Vision {
    data class Analysis(val isOutfitPhoto: Boolean, val items: List<WornItem>, val note: String)

    suspend fun analyze(jpeg: ByteArray): Analysis
}

class OpenAiVision(private val openAi: OpenAi) : Vision {

    override suspend fun analyze(jpeg: ByteArray): Vision.Analysis {
        if (!openAi.isConfigured) throw VisionException("OpenAI のキーが設定されていません")
        val dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)
        val messages = buildJsonArray {
            add(OpenAi.message("system", JsonPrimitive(SYSTEM)))
            add(OpenAi.message("user", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", "この写真の服を解析してください。") })
                add(buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") { put("url", dataUrl); put("detail", "high") }
                })
            }))
        }
        val out = try {
            openAi.structured(messages, "outfit", schema(), temperature = 0.0)
        } catch (e: OpenAiException) {
            throw VisionException(e.message ?: "OpenAIの呼び出しに失敗", e)
        }
        return parse(out)
    }

    companion object {
        val SYSTEM = """あなたはファッションの画像解析器です。写真に写っている服を一つずつ列挙してください。人が着ている写真でも、床・ベッド・机に上下の服を並べて置いた写真（組み合わせの確認）でも、同じ『着る予定の一式』として扱う。
- type は指定の列挙値から最も近いものを選ぶ。
- color は日本語の短い色名（例: 黒、白、ネイビー、ベージュ、グレー、カーキ）。
- thickness は見た目（編み目、裏起毛、生地の厚み）から thin / normal / thick を推定する。
- bbox は画像全体を 0〜1 とした正規化座標（x,y は左上、w,h は幅と高さ）。服だけを囲み、顔・頭・手・背景はできるだけ含めない。
- 写っていない服（フレーム外の下半身など）は推測して列挙しない。
- 並べて置かれた服は、重ね着として一緒に着る前提で列挙する（シャツとカーディガンが並んでいれば両方）。靴・帽子・バッグは小物(accessory)か、列挙しない。
- 服の写真・人が服を着た写真でなければ is_outfit_photo を false にして items を空にする。
- ブランド名や人物の特定はしない。"""

        fun schema(): JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonArray("required") { listOf("is_outfit_photo", "items", "note").forEach { add(JsonPrimitive(it)) } }
            putJsonObject("properties") {
                putJsonObject("is_outfit_photo") { put("type", "boolean") }
                putJsonObject("note") { put("type", "string") }
                putJsonObject("items") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        put("additionalProperties", false)
                        putJsonArray("required") {
                            listOf("type", "color", "thickness", "season", "bbox", "confidence").forEach { add(JsonPrimitive(it)) }
                        }
                        putJsonObject("properties") {
                            putJsonObject("type") { put("type", "string"); putEnum(Comfort.GARMENT_TYPES.keys) }
                            putJsonObject("color") { put("type", "string") }
                            putJsonObject("thickness") { put("type", "string"); putEnum(listOf("thin", "normal", "thick")) }
                            putJsonObject("season") { put("type", "string"); putEnum(listOf("summer", "spring_autumn", "winter", "all")) }
                            putJsonObject("bbox") {
                                put("type", "object")
                                put("additionalProperties", false)
                                putJsonArray("required") { listOf("x", "y", "w", "h").forEach { add(JsonPrimitive(it)) } }
                                putJsonObject("properties") {
                                    listOf("x", "y", "w", "h").forEach { k -> putJsonObject(k) { put("type", "number") } }
                                }
                            }
                            putJsonObject("confidence") { put("type", "number") }
                        }
                    }
                }
            }
        }

        private fun kotlinx.serialization.json.JsonObjectBuilder.putEnum(values: Collection<String>) =
            putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }

        /** OpenAI の返事（schema の形）を読み取る。形がおかしい服は読み飛ばす。 */
        fun parse(o: JsonObject): Vision.Analysis {
            val items = (o["items"] as? JsonArray).orEmpty().mapNotNull { e ->
                val i = e as? JsonObject ?: return@mapNotNull null
                fun s(k: String) = (i[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val b = i["bbox"] as? JsonObject
                fun n(k: String) = (b?.get(k) as? JsonPrimitive)?.doubleOrNull
                val box = if (b != null && listOf("x", "y", "w", "h").all { n(it) != null }) Box(n("x")!!, n("y")!!, n("w")!!, n("h")!!) else null
                WornItem(
                    type = s("type") ?: return@mapNotNull null,
                    thickness = s("thickness") ?: "normal",
                    color = s("color") ?: "",
                    season = s("season") ?: "all",
                    confidence = (i["confidence"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                    bbox = box,
                )
            }
            val isOutfit = (o["is_outfit_photo"] as? JsonPrimitive)?.let { runCatching { it.boolean }.getOrNull() } ?: false
            val note = (o["note"] as? JsonPrimitive)?.content ?: ""
            return Vision.Analysis(isOutfit, items, note)
        }
    }
}
