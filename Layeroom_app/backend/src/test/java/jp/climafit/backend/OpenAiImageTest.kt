package jp.climafit.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import jp.climafit.backend.ai.OpenAi
import jp.climafit.backend.ai.OpenAiException
import jp.climafit.backend.ai.OpenAiMannequin
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** OpenAI の画像編集（マネキン画像）の送り方と受け取り方。 */
class OpenAiImageTest {
    private val json = headersOf("Content-Type", "application/json")
    private val bodies = mutableListOf<Pair<String, String>>()   // (url, 本文)

    private fun client(vararg replies: Pair<HttpStatusCode, String>): HttpClient {
        val queue = ArrayDeque(replies.toList())
        return HttpClient(MockEngine { req ->
            bodies += req.url.toString() to String(req.body.toByteArray(), Charsets.ISO_8859_1)
            val (status, body) = queue.removeFirst()
            respond(body, status, json)
        }) { expectSuccess = false }
    }

    private val image = Base64.getEncoder().encodeToString(byteArrayOf(9, 8, 7))

    @Test
    fun `上下の画像を image配列で送り、b64_json を画像にして返す`() = runTest {
        val ai = OpenAi(client(HttpStatusCode.OK to """{"data":[{"b64_json":"$image"}]}"""), "sk-test", imageModels = listOf("m1"))
        val out = OpenAiMannequin(ai).render(byteArrayOf(1), byteArrayOf(2), "白のTシャツ", "青のジーンズ")
        assertContentEquals(byteArrayOf(9, 8, 7), out)
        val (url, body) = bodies.single()
        assertEquals("https://api.openai.com/v1/images/edits", url)
        assertEquals(2, Regex("name=\"?image\\[]\"?").findAll(body).count())
        assertTrue("m1" in body && "1024x1536" in body && "jpeg" in body)
        assertTrue("白のTシャツ" in String(body.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8))
    }

    @Test
    fun `使えないモデルは飛ばして次のモデルを試す`() = runTest {
        val ai = OpenAi(client(
            HttpStatusCode.NotFound to """{"error":{"message":"not found"}}""",
            HttpStatusCode.BadRequest to """{"error":{"message":"Invalid value for model"}}""",
            HttpStatusCode.OK to """{"data":[{"b64_json":"$image"}]}""",
        ), "sk-test", imageModels = listOf("a", "b", "c"))
        assertContentEquals(byteArrayOf(9, 8, 7), ai.editImage(listOf("x.jpg" to byteArrayOf(1)), "p"))
        assertEquals(3, bodies.size)
    }

    @Test
    fun `キーが無い・ほかのエラーは OpenAiException`() = runTest {
        assertFailsWith<OpenAiException> { OpenAi(client(), null).editImage(listOf("x.jpg" to byteArrayOf(1)), "p") }
        val ai = OpenAi(client(HttpStatusCode.TooManyRequests to """{"error":{"message":"rate limit"}}"""), "sk-test", imageModels = listOf("a"))
        assertFailsWith<OpenAiException> { ai.editImage(listOf("x.jpg" to byteArrayOf(1)), "p") }
    }

    @Test
    fun `下の服が無いときは1枚だけ送り、指示文も1着にする`() {
        val p = OpenAiMannequin.prompt(hasUpper = true, hasLower = false, upperDesc = "ワンピース", lowerDesc = null)
        assertTrue("input image 1 is the top garment (ワンピース)" in p)
        assertTrue("bottom" !in p)
    }
}
