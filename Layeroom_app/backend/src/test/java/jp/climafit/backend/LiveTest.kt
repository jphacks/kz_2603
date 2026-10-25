package jp.climafit.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import jp.climafit.backend.ai.Box
import jp.climafit.backend.ai.Coach
import jp.climafit.backend.ai.OpenAi
import jp.climafit.backend.ai.OpenAiVision
import jp.climafit.backend.ai.Vision
import jp.climafit.backend.ai.WornItem
import jp.climafit.backend.db.DbServer
import jp.climafit.backend.image.Crop
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 本物の db_server（と OpenAI）につなぐテスト。データを書き込む（利用者名は test- で始める）。
 *   LIVE_DB_SERVER_URL=http://<db_server>:<port> [OPENAI_API_KEY=...] ./gradlew :backend:testDebugUnitTest --tests "*LiveTest*"
 * 写真の読み取りは、決めた服が写っていたことにする（服の写真が無いので）。OPENAI_API_KEY があればアドバイス文は本物の OpenAI。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LiveTest {
    private val dbUrl: String? = System.getenv("LIVE_DB_SERVER_URL")
    private val openAiKey: String? = System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
    private val http = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = 90_000 }
    }

    private class ScriptedVision : Vision {
        private val outfits = ArrayDeque(listOf(
            listOf(item("jacket", "ブラウン", UP), item("jeans", "青", LOW)),
            listOf(item("shirt", "白", UP), item("pants", "ベージュ", LOW)),
        ))
        override suspend fun analyze(jpeg: ByteArray) = Vision.Analysis(true, outfits.removeFirst().also { outfits.addLast(it) }, "")

        companion object {
            val UP = Box(0.2, 0.2, 0.6, 0.3)
            val LOW = Box(0.2, 0.55, 0.5, 0.4)
            fun item(t: String, c: String, b: Box) = WornItem(t, "normal", c, "all", 0.9, b)
        }
    }

    private fun backend(vision: Vision = ScriptedVision()) = LayeRoomBackend(
        DbServer(http, dbUrl!!), vision, Coach(openAiKey?.let { OpenAi(http, it) }), Crop(jp.climafit.backend.image.AndroidImageOps()), InMemorySessionStore(),
    )

    @Test
    fun `画面の流れを本物の db_server で通す`() = runBlocking {
        assumeTrue("LIVE_DB_SERVER_URL が無いので飛ばす", dbUrl != null)
        val b = backend()
        val nick = "test-android-${UUID.randomUUID().toString().take(6)}"
        b.start(nick)

        // 場所と提案タブ
        val rooms = b.rooms()
        assertEquals(listOf("図書館", "201教室", "学生食堂", "実験室3"), rooms.map { it.name }.take(4))
        val lib = rooms.first { it.name == "図書館" }
        val cond = b.conditions("図書館")
        assertEquals(lib.temperature, cond.indoor?.temperature)
        assertNotNull(cond.outdoor?.temperature)
        assertEquals("general", b.suggestions("図書館").mode)

        // 写真診断 → 保存（2回で4着）
        val r1 = b.review(TestImages.photo(), "図書館")
        assertEquals(lib.temperature, r1.indoorTemp)       // 選んだ場所の室温で判定
        assertTrue(r1.advice.isNotBlank() && r1.tip.isNotBlank())
        val s1 = b.save(r1.draftId)
        assertEquals(2, s1.items.size)
        b.save(b.review(TestImages.photo(), "図書館").draftId)

        // 服の一覧・画像
        val gs = b.garments().garments
        assertEquals(setOf("jacket", "jeans", "shirt", "pants"), gs.map { it.type }.toSet())
        val img = b.garmentImage(gs[0].garmentId!!)
        assertTrue(img[0] == 0xFF.toByte() && img[1] == 0xD8.toByte())

        // 提案（保存した服から）→ おすすめの保存
        val sug = b.suggestions("図書館")
        assertEquals("personal", sug.mode)
        assertTrue(sug.suggestions.isNotEmpty())
        val first = sug.suggestions[0]
        assertEquals(480, TestImages.size(b.comboImage(first.upperId, first.lowerId)).first)
        assertNull(b.latestRecommendation())
        b.saveRecommendation(first.upperId!!, first.lowerId, "test")
        val latest = b.latestRecommendation()!!
        assertEquals(first.upperId, latest.top?.garmentId)
        assertEquals(first.lowerId, latest.bottom?.garmentId)

        // 気温別コーデ・感想
        val logs = b.outfits()
        assertEquals(2, logs.size)
        assertEquals("図書館", logs[0].classroom)
        assertEquals(lib.temperature, logs[0].indoorTemp)
        assertEquals(2, b.outfits(temp = lib.temperature, kind = "indoor").size)
        b.feedback(logs[0].outfitId, "暑かった")
        assertEquals("暑かった", b.outfits()[0].userFeedback)

        // 誤認識の服を隠す
        b.hideGarment(gs[0].garmentId!!)
        assertTrue(b.garments().garments.none { it.garmentId == gs[0].garmentId })

        // ひろば（投稿は1件、ひとことは [テスト] で始める）
        val room = rooms.first { it.name == "201教室" }
        val p = b.post(room.id, "快適", "[テスト] Android のバックエンドからの接続確認です")
        assertTrue(b.posts(room.id).any { it.id == p.id })
    }

    @Test
    fun `本物の OpenAI が、写真の読み取りの依頼を受け付ける`() = runBlocking {
        assumeTrue("LIVE_DB_SERVER_URL と OPENAI_API_KEY が無いので飛ばす", dbUrl != null && openAiKey != null)
        val b = backend(OpenAiVision(OpenAi(http, openAiKey)))
        b.start("test-android-ai-${UUID.randomUUID().toString().take(6)}")
        // 灰色一色の画像なので「服が見つからない」になるはず（依頼の形が正しく、OpenAI が答えた証拠）
        val e = assertFailsWith<BackendException> { b.review(TestImages.photo(), "図書館") }
        assertEquals(ErrorKind.NO_CLOTHES, e.kind, e.message)
    }

    @Test
    fun `本物の OpenAI がアドバイス文を書く（定型文に落ちていない）`() = runBlocking {
        assumeTrue("OPENAI_API_KEY が無いので飛ばす", openAiKey != null)
        val facts = jp.climafit.backend.ai.AdviceFacts(
            "general", "図書館", 21.0, 18.9, 90.0, null, "", true, "ok_remove", "ちょうどいい（上着を脱げば）",
            1.0, 1.24, listOf("ジャケット"), emptyList(), null, "",
        )
        val written = Coach(OpenAi(http, openAiKey)).write(facts)
        assertTrue(written != Coach.template(facts), "OpenAI が使えず定型文になった: $written")
        println("OpenAI のアドバイス: $written")
    }
}
