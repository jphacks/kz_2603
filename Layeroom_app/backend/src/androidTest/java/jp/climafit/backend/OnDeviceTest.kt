package jp.climafit.backend

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import jp.climafit.backend.ai.Box
import jp.climafit.backend.ai.Coach
import jp.climafit.backend.ai.OpenAi
import jp.climafit.backend.ai.Vision
import jp.climafit.backend.ai.WornItem
import jp.climafit.backend.db.DbServer
import jp.climafit.backend.image.AndroidImageOps
import jp.climafit.backend.image.Crop
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 端末（エミュレーター）の上で、本物の db_server と OpenAI につなぐ。
 *   ./gradlew :backend:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.dbServerUrl=http://<db_server>:<port> \
 *     -Pandroid.testInstrumentationRunnerArguments.openAiKey=sk-...
 * 通信（OkHttp）・画像処理（Bitmap）・ログイン情報の保存（SharedPreferences）は本物。写真の読み取りだけ決めた服にする。
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceTest {
    private val args = InstrumentationRegistry.getArguments()
    private val dbUrl: String? = args.getString("dbServerUrl")
    private val openAiKey: String? = args.getString("openAiKey")?.takeIf { it.isNotBlank() }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun photo(): ByteArray {
        val bmp = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private class FixedVision : Vision {
        override suspend fun analyze(jpeg: ByteArray) = Vision.Analysis(true, listOf(
            WornItem("jacket", "normal", "ブラウン", "all", 0.9, Box(0.2, 0.2, 0.6, 0.3)),
            WornItem("jeans", "normal", "青", "all", 0.9, Box(0.2, 0.55, 0.5, 0.4)),
        ), "")
    }

    @Test
    fun savesAndReadsBackOnDevice() = runBlocking {
        assumeTrue("dbServerUrl が無いので飛ばす", dbUrl != null)
        val http = HttpClient(OkHttp) { expectSuccess = false; install(HttpTimeout) { requestTimeoutMillis = 90_000 } }
        val store = SharedPreferencesSessionStore(context).apply { clear() }
        val b = LayeRoomBackend(DbServer(http, dbUrl!!), FixedVision(), Coach(openAiKey?.let { OpenAi(http, it) }),
            Crop(AndroidImageOps()), store)

        val s = b.start("test-device-${UUID.randomUUID().toString().take(6)}")
        assertEquals(s, SharedPreferencesSessionStore(context).load())   // 端末に保存された

        val lib = b.rooms().first { it.name == "図書館" }
        val review = b.review(photo(), "図書館")
        assertEquals(lib.temperature, review.indoorTemp)
        assertTrue(review.itemImages.all { it != null })                   // Bitmap で切り抜けた
        val saved = b.save(review.draftId)
        assertEquals(2, saved.items.size)

        val img = b.garmentImage(saved.items[0].garmentId!!)
        assertTrue(img[0] == 0xFF.toByte() && img[1] == 0xD8.toByte())
        val combo = b.comboImage(saved.items[0].garmentId, saved.items[1].garmentId)
        assertTrue(combo.size > 1000)
        val log = b.outfits().single()
        assertEquals("図書館", log.classroom)
        assertEquals(lib.temperature, log.indoorTemp)
        store.clear()
    }

    @Test
    fun realOpenAiReadsPhotoOnDevice() = runBlocking {
        assumeTrue("dbServerUrl と openAiKey が無いので飛ばす", dbUrl != null && openAiKey != null)
        val b = LayeRoomBackend.create(context, dbUrl!!, openAiKey)   // アプリと同じ作り方
        b.signOut()
        b.start("test-device-ai-${UUID.randomUUID().toString().take(6)}")
        val e = assertFailsWith<BackendException> { b.review(photo(), "図書館") }
        assertEquals(ErrorKind.NO_CLOTHES, e.kind, e.message)   // 灰色の画像 → 服が見つからない（OpenAI が答えた）
        b.signOut()
    }
}
