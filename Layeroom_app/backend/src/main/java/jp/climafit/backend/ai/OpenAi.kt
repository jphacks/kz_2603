package jp.climafit.backend.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

class OpenAiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * OpenAI の Chat Completions を、決めた形（JSON Schema）で答えさせる。
 * baseUrl を変えれば、キーを持つ中継サーバー（例: Ubuntu 上）経由にもできる（その場合 apiKey は空でよい）。
 */
class OpenAi(
    private val http: HttpClient,
    private val apiKey: String?,
    private val baseUrl: String = "https://api.openai.com/v1",
    /** 先頭から順に試し、使えない(404/403)なら次へ。 */
    val models: List<String> = listOf("gpt-6-luna", "gpt-4o", "gpt-4o-mini"),
    /** 画像の編集（マネキン画像）に使うモデル。先頭から順に試す。 */
    val imageModels: List<String> = DEFAULT_IMAGE_MODELS,
) {
    val isConfigured: Boolean get() = !apiKey.isNullOrBlank() || !baseUrl.startsWith("https://api.openai.com")

    /** messages を送り、schema の形の JSON を返す。 */
    suspend fun structured(messages: JsonArray, schemaName: String, schema: JsonObject, temperature: Double? = null): JsonObject {
        if (!isConfigured) throw OpenAiException("OpenAI のキーが設定されていません")
        var last: String? = null
        for (model in models) {
            val body = buildJsonObject {
                put("model", model)
                put("messages", messages)
                temperature?.let { put("temperature", it) }
                put("response_format", buildJsonObject {
                    put("type", "json_schema")
                    put("json_schema", buildJsonObject {
                        put("name", schemaName)
                        put("strict", true)
                        put("schema", schema)
                    })
                })
            }
            val res = try {
                http.post("${baseUrl.trimEnd('/')}/chat/completions") {
                    if (!apiKey.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
                    setBody(TextContent(body.toString(), ContentType.Application.Json))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw OpenAiException("OpenAIに接続できません: ${e.message}", e)
            }
            val text = res.bodyAsText()
            if (res.status == HttpStatusCode.NotFound || res.status == HttpStatusCode.Forbidden ||
                res.status == HttpStatusCode.BadRequest) {
                last = "$model: ${res.status} ${text.take(120)}"   // このキーで使えない／このモデルが受け付けない → 次を試す
                continue
            }
            if (res.status.value >= 400) throw OpenAiException("OpenAIの呼び出しに失敗: ${res.status} ${text.take(200)}")
            return try {
                val content = Json.parseToJsonElement(text).jsonObject["choices"]!!.jsonArray[0]
                    .jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
                Json.parseToJsonElement(content).jsonObject
            } catch (e: Exception) {
                throw OpenAiException("AIの返答を読み取れませんでした", e)
            }
        }
        throw OpenAiException("使えるモデルがありません: $last")
    }

    /**
     * 画像を元に新しい画像を作る（Images API の edits）。images は (ファイル名, 中身)。
     * 返事の data[0].b64_json を画像のバイト列にして返す。
     */
    suspend fun editImage(
        images: List<Pair<String, ByteArray>>,
        prompt: String,
        size: String = "1024x1536",
        quality: String = "medium",
    ): ByteArray {
        if (!isConfigured) throw OpenAiException("OpenAI のキーが設定されていません")
        if (images.isEmpty()) throw OpenAiException("元になる画像がありません")
        var last: String? = null
        for (model in imageModels) {
            val form = MultiPartFormDataContent(formData {
                append("model", model)
                append("prompt", prompt)
                append("size", size)
                append("quality", quality)
                append("output_format", "jpeg")
                images.forEach { (name, bytes) ->
                    append("image[]", bytes, Headers.build {
                        append(HttpHeaders.ContentType, imageType(bytes))
                        append(HttpHeaders.ContentDisposition, "filename=\"$name\"")
                    })
                }
            })
            val res = try {
                http.post("${baseUrl.trimEnd('/')}/images/edits") {
                    if (!apiKey.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
                    setBody(form)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw OpenAiException("OpenAIに接続できません: ${e.message}", e)
            }
            val text = res.bodyAsText()
            val unknownModel = res.status == HttpStatusCode.BadRequest && "model" in text
            if (res.status == HttpStatusCode.NotFound || res.status == HttpStatusCode.Forbidden || unknownModel) {
                last = "$model: ${res.status} ${text.take(160)}"   // このキーで使えないモデル → 次を試す
                continue
            }
            if (res.status.value >= 400) throw OpenAiException("OpenAIの画像生成に失敗: ${res.status} ${text.take(200)}")
            return try {
                val b64 = Json.parseToJsonElement(text).jsonObject["data"]!!.jsonArray[0]
                    .jsonObject["b64_json"]!!.jsonPrimitive.content
                Base64.getDecoder().decode(b64)
            } catch (e: Exception) {
                throw OpenAiException("AIの画像を読み取れませんでした", e)
            }
        }
        throw OpenAiException("使える画像モデルがありません: $last")
    }

    companion object {
        /** 画像編集に使えるモデルの候補（新しい順）。使えないものは飛ばす。 */
        val DEFAULT_IMAGE_MODELS = listOf("gpt-image-2.5-sunburst", "gpt-image-2", "gpt-image-1.5", "gpt-image-1")

        private fun imageType(bytes: ByteArray): String = when {
            bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
            bytes.size > 3 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "image/webp"
            else -> "image/jpeg"
        }

        fun message(role: String, content: JsonElement) = buildJsonObject {
            put("role", role)
            put("content", content)
        }
    }
}
