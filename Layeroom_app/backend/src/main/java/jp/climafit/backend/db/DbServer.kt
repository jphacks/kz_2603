package jp.climafit.backend.db

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** db_server のエラー。status が 0 ならつながらなかった。 */
class DbServerException(val status: Int, message: String) : Exception(message) {
    val isRoomNotFound get() = status == 404 && "room" in message.orEmpty()
}

/**
 * db_server（Ubuntu 上の FastAPI + PostgreSQL）を呼ぶ。トークンは X-User-Token ヘッダで送る。
 * JSON はそのまま返し、読む側（LayeRoomBackend）で必要な項目を取り出す。
 */
class DbServer(private val http: HttpClient, baseUrl: String) {
    private val base = baseUrl.trimEnd('/')

    private suspend fun send(
        method: HttpMethod,
        path: String,
        token: String? = null,
        query: Map<String, Any?> = emptyMap(),
        json: JsonElement? = null,
        form: MultiPartFormDataContent? = null,
    ): HttpResponse {
        val res = try {
            http.request(base + path) {
                this.method = method
                query.forEach { (k, v) -> if (v != null) url.parameters.append(k, v.toString()) }
                token?.let { header("X-User-Token", it) }
                when {
                    form != null -> setBody(form)
                    json != null -> setBody(TextContent(json.toString(), ContentType.Application.Json))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DbServerException(0, "DBサーバーに接続できません（${e.message}）")
        }
        if (res.status.value >= 400) throw DbServerException(res.status.value, detailOf(res.bodyAsText()))
        return res
    }

    private suspend fun sendJson(
        method: HttpMethod, path: String, token: String? = null, query: Map<String, Any?> = emptyMap(),
        json: JsonElement? = null, form: MultiPartFormDataContent? = null,
    ): JsonElement {
        val text = send(method, path, token, query, json, form).bodyAsText()
        if (text.isBlank()) return JsonNull
        return runCatching { Json.parseToJsonElement(text) }.getOrElse { throw DbServerException(502, "DBサーバーから読めない返事が来ました") }
    }

    // ---------- 利用者 ----------
    /** 利用者を作る → (user_id, token)。 */
    suspend fun createUser(name: String): Pair<Long, String> {
        val o = sendJson(HttpMethod.Post, "/users", json = buildJsonObject { put("name", name) }) as JsonObject
        val id = (o["user_id"] ?: o["id"])?.jsonPrimitive?.content?.toLongOrNull()
        val token = (o["token"] as? JsonPrimitive)?.content
        if (id == null || token == null) throw DbServerException(502, "DBサーバーから利用者IDが返ってきませんでした")
        return id to token
    }

    // ---------- 気象・場所 ----------
    /** 室内・室外の最新値。classroom を渡すとその部屋の値。db_server に無い部屋なら部屋なしで取り直す。 */
    suspend fun conditions(classroom: String? = null): JsonObject = try {
        sendJson(HttpMethod.Get, "/conditions/latest", query = mapOf("classroom" to classroom?.ifBlank { null })) as JsonObject
    } catch (e: DbServerException) {
        if (classroom.isNullOrBlank() || !e.isRoomNotFound) throw e
        sendJson(HttpMethod.Get, "/conditions/latest") as JsonObject
    }

    suspend fun rooms(): JsonArray = sendJson(HttpMethod.Get, "/rooms") as JsonArray

    suspend fun roomPosts(roomId: Long, all: Boolean = false, limit: Int? = null, offset: Int? = null): JsonArray =
        sendJson(HttpMethod.Get, "/rooms/$roomId/posts", query = mapOf("all" to all.takeIf { it }, "limit" to limit, "offset" to offset)) as JsonArray

    suspend fun createPost(token: String, roomId: Long, feeling: String, comment: String): JsonObject =
        sendJson(HttpMethod.Post, "/rooms/$roomId/posts", token,
            json = buildJsonObject { put("feeling", feeling); put("comment", comment) }) as JsonObject

    // ---------- コーデ・服 ----------
    /** コーデ写真を保存 → outfit_id。classroom を渡すと、db_server がその部屋の室温を記録する。 */
    suspend fun postOutfit(token: String, jpeg: ByteArray, verdict: String, comment: String, classroom: String?, analysis: JsonObject?): Long {
        suspend fun post(room: String?): JsonObject = sendJson(HttpMethod.Post, "/outfits", token, form = MultiPartFormDataContent(formData {
            append("verdict", verdict)
            append("comment", comment)
            room?.let { append("classroom", it) }
            analysis?.let { append("analysis", it.toString()) }
            append("image", jpeg, imageHeaders("outfit.jpg"))
        })) as JsonObject
        val o = try {
            post(classroom?.ifBlank { null })
        } catch (e: DbServerException) {
            if (classroom.isNullOrBlank() || !e.isRoomNotFound) throw e
            post(null)
        }
        return idOf(o, "outfit_id")
    }

    /** 服（上=top / 下=bottom）を保存 → garment_id。meta は種類・色・暖かさなど。 */
    suspend fun postGarment(token: String, outfitId: Long, part: String, jpeg: ByteArray, meta: JsonObject): Long {
        val o = sendJson(HttpMethod.Post, "/outfits/$outfitId/garments", token, form = MultiPartFormDataContent(formData {
            append("part", part)
            append("meta", meta.toString())
            append("image", jpeg, imageHeaders("$part.jpg"))
        })) as JsonObject
        return idOf(o, "garment_id")
    }

    suspend fun garments(userId: Long, token: String): JsonArray = sendJson(HttpMethod.Get, "/users/$userId/garments", token) as JsonArray

    suspend fun outfits(userId: Long, token: String, limit: Int? = null): JsonArray =
        sendJson(HttpMethod.Get, "/users/$userId/outfits", token, mapOf("limit" to limit)) as JsonArray

    suspend fun patchFeedback(token: String, outfitId: Long, feedback: String) {
        sendJson(HttpMethod.Patch, "/outfits/$outfitId/feedback", token, json = buildJsonObject { put("feedback", feedback) })
    }

    suspend fun patchMeta(token: String, garmentId: Long, patch: JsonObject) {
        sendJson(HttpMethod.Patch, "/garments/$garmentId/meta", token, json = patch)
    }

    suspend fun garmentImage(token: String, garmentId: Long): ByteArray =
        send(HttpMethod.Get, "/garments/$garmentId/image", token).readRawBytes()

    // ---------- おすすめ ----------
    suspend fun postRecommendation(userId: Long, token: String, top: Long, bottom: Long?, reason: String): JsonObject =
        sendJson(HttpMethod.Post, "/users/$userId/recommendations", token, json = buildJsonObject {
            put("top_garment_id", top); put("bottom_garment_id", bottom); put("reason", reason)
        }) as JsonObject

    /** 最新のおすすめ。まだ無ければ null。 */
    suspend fun latestRecommendation(userId: Long, token: String): JsonObject? = try {
        sendJson(HttpMethod.Get, "/users/$userId/recommendations/latest", token) as JsonObject
    } catch (e: DbServerException) {
        if (e.status == 404) null else throw e
    }

    companion object {
        private fun imageHeaders(name: String) = Headers.build {
            append(HttpHeaders.ContentType, "image/jpeg")
            append(HttpHeaders.ContentDisposition, "filename=\"$name\"")
        }

        private fun idOf(o: JsonObject, alt: String): Long =
            (o["id"] ?: o[alt])?.jsonPrimitive?.content?.toLongOrNull() ?: throw DbServerException(502, "DBサーバーの返事にIDがありません")

        /** FastAPI のエラー本文から、人が読めるメッセージを取り出す。 */
        fun detailOf(text: String): String {
            val o = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return text.take(200)
            return when (val d = o["detail"]) {
                is JsonPrimitive -> d.content
                is JsonArray -> d.joinToString(" / ") { e ->
                    val m = e as? JsonObject
                    val field = (m?.get("loc") as? JsonArray)?.lastOrNull()?.jsonPrimitive?.content
                    val msg = (m?.get("msg") as? JsonPrimitive)?.content ?: e.toString()
                    if (field != null) "$field: $msg" else msg
                }
                else -> text.take(200)
            }
        }
    }
}
