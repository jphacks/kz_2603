package jp.climafit.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import jp.climafit.backend.InMemorySessionStore
import jp.climafit.backend.LayeRoomBackend
import jp.climafit.backend.Session
import jp.climafit.backend.ai.Box
import jp.climafit.backend.ai.Coach
import jp.climafit.backend.ai.Vision
import jp.climafit.backend.ai.WornItem
import jp.climafit.backend.db.DbServer
import jp.climafit.backend.image.AndroidImageOps
import jp.climafit.backend.image.Crop
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Collections

/**
 * バックエンドにつないだ状態の画面。LayeRoomBackend は本物で、db_server との通信と写真の読み取り（OpenAI）だけ偽物。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class FrontendBackendTest {
    @get:Rule val compose = createComposeRule()

    /** db_server に届いたリクエスト（"POST /outfits" と本文）。 */
    private val calls = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    private var garmentId = 30

    private fun jpeg(): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GRAY) }
            .compress(Bitmap.CompressFormat.JPEG, 90, it)
    }.toByteArray()

    private fun garment(id: Int, slot: String, type: String, color: String, clo: Double) =
        """{"id":$id,"outfit_id":1,"part":"${if (slot == "upper") "top" else "bottom"}","taken_at":"2026-10-20T03:00:00Z",
            "meta":{"type":"$type","color":"$color","clo":$clo,"slot":"$slot","thickness":"normal"}}"""

    private val db = HttpClient(MockEngine { req ->
        val key = "${req.method.value} ${req.url.encodedPath}"
        calls += key to String(req.body.toByteArray())
        val json = headersOf("Content-Type", "application/json")
        fun ok(body: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(body, status, json)
        when (key) {
            "POST /users" -> ok("""{"id":7,"user_id":7,"name":"x","token":"tok7"}""", HttpStatusCode.Created)
            "GET /rooms" -> ok("""[
                {"id":1,"name":"図書館","temperature":21.0,"humidity":48.0,"is_dummy":true,"temperature_source":"dummy","humidity_source":"dummy","stale":false,"today":{"total":1}},
                {"id":2,"name":"201教室","temperature":23.4,"humidity":55.0,"is_dummy":false,"temperature_source":"sensor","humidity_source":"dummy","stale":false,"today":{"total":0}}]""")
            "GET /conditions/latest" -> {
                val t = if (req.url.parameters["classroom"] == "201教室") 23.4 else 21.0
                ok("""{"indoor":{"room_name":"${req.url.parameters["classroom"] ?: "201教室"}","temperature":$t,"humidity":55.0,
                    "temperature_source":"sensor","humidity_source":"dummy"},"outdoor":{"temperature":16.0,"humidity":80.0}}""")
            }
            "GET /users/7/garments" -> ok("[" + listOf(garment(1, "upper", "shirt", "白", 0.25), garment(2, "lower", "pants", "黒", 0.2),
                garment(3, "upper", "knit", "灰", 0.35), garment(4, "lower", "jeans", "青", 0.25)).joinToString(",") + "]")
            "GET /users/7/outfits" -> ok("""[{"id":9,"taken_at":"2026-10-20T03:00:00Z","verdict":"good","comment":"ちょうどよかった",
                "conditions":{"indoor":{"room_name":"図書館","temperature":21.0},"outdoor":{"temperature":16.0}},"garments":[{"id":1},{"id":2}]}]""")
            "POST /outfits" -> ok("""{"id":11}""", HttpStatusCode.Created)
            "POST /outfits/11/garments" -> ok("""{"id":${garmentId++}}""", HttpStatusCode.Created)
            "POST /users/7/recommendations" -> ok("{}", HttpStatusCode.Created)
            "GET /rooms/1/posts" -> ok("""[{"id":5,"room_id":1,"feeling":"slightly_cold","feeling_label":"少し寒い","comment":"窓際が寒い","created_at":"2026-10-24T03:00:00Z"}]""")
            "POST /rooms/1/posts" -> ok("""{"id":6,"room_id":1,"feeling":"comfortable","feeling_label":"快適","comment":"x","created_at":"2026-10-25T03:00:00Z"}""", HttpStatusCode.Created)
            else -> if (req.url.encodedPath.endsWith("/image")) respond(jpeg(), HttpStatusCode.OK, headersOf("Content-Type", "image/jpeg"))
                else ok("""{"detail":"Not Found"}""", HttpStatusCode.NotFound)
        }
    }) { expectSuccess = false }

    private class FixedVision : Vision {
        override suspend fun analyze(jpeg: ByteArray) = Vision.Analysis(true, listOf(
            WornItem("shirt", "normal", "白", "all", 0.9, Box(0.2, 0.1, 0.6, 0.4)),
            WornItem("pants", "normal", "黒", "all", 0.9, Box(0.2, 0.55, 0.5, 0.4)),
        ), "")
    }

    private var pickerResult: Uri? = null

    private fun showApp(session: Session? = Session("たろう", 7, "tok7")): LayeRoomViewModel {
        val app = RuntimeEnvironment.getApplication()
        val backend = LayeRoomBackend(DbServer(db, "http://db"), FixedVision(), Coach(null), Crop(AndroidImageOps()),
            InMemorySessionStore(session))
        val model = LayeRoomViewModel(app, backend)
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                // カメラの許可は断ったことにして、写真フォルダからの選択で進める
                if (contract is androidx.activity.result.contract.ActivityResultContracts.RequestPermission) dispatchResult(requestCode, false)
                else dispatchResult(requestCode, pickerResult)
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { LayeRoomTheme { LayeRoomApp(model) } }
        }
        waitFor { model.rooms.any { it.id != null } }
        return model
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun called(key: String) = synchronized(calls) { calls.filter { it.first == key } }

    private fun photoUri(): Uri {
        val app = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://layeroom.backend.photos/outfit.png")
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "image/png"
            override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?) = 0
        }
        provider.attachInfo(app, ProviderInfo().apply { authority = "layeroom.backend.photos" })
        ShadowContentResolver.registerProviderInternal("layeroom.backend.photos", provider)
        val bytes = ByteArrayOutputStream().also {
            Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        shadowOf(app.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        return uri
    }

    private fun selectLocation(name: String) {
        compose.onNodeWithTag("location-sheet").performScrollToNode(hasTestTag("location-$name"))
        compose.onNodeWithTag("location-$name").performClick()
        compose.waitForIdle()
    }

    @Test fun placesComeFromTheServerAndRegistrationCreatesAUser() {
        val model = showApp(session = null)
        compose.runOnIdle {
            assertEquals(listOf("図書館", "201教室"), model.rooms.map { it.name })
            assertEquals(23.4, model.rooms[1].temperature, 0.0)
            assertEquals(16.0, model.outdoorTemperature, 0.0)
            assertNull(model.userName)
            assertTrue(model.mustRegister)
        }
        // 未登録で起動すると、登録シートが自動で開く
        compose.onNodeWithTag("registration-sheet").assertExists()
        compose.onNodeWithTag("registration-name").performTextInput("金沢 太郎")
        compose.onNodeWithTag("registration-submit").performScrollTo().performClick()
        waitFor { model.userName != null }
        assertEquals("金沢 太郎", model.userName)
        assertEquals("""{"name":"金沢 太郎"}""", called("POST /users").single().second)
        compose.waitForIdle()
        compose.onNodeWithTag("registration-sheet").assertDoesNotExist()   // 登録できたら自動で閉じる

        compose.onNodeWithTag("home-screen").performScrollToNode(hasTestTag("home-room-picker"))
        compose.onNodeWithTag("home-room-picker").performClick()
        compose.onNodeWithText("室温 23.4℃ · 湿度 55%").assertExists()   // 場所の選択シートにサーバーの値
        selectLocation("201教室")
    }

    @Test fun photoDiagnosisShowsTheResultAndSavesTheOutfit() {
        pickerResult = photoUri()
        val model = showApp()
        compose.onNodeWithTag("home-screen").performScrollToNode(hasTestTag("home-photo"))
        compose.onNodeWithTag("home-photo").performClick()
        compose.onNodeWithTag("camera-gallery").performClick()   // カメラの左下から写真フォルダを開く
        waitFor { model.diagnosis != null || model.diagnosisError != null }
        assertNull(model.diagnosisError)
        compose.onNodeWithTag("diagnosis-result").assertExists()
        compose.onNodeWithText(model.diagnosis!!.verdictJa).assertExists()
        compose.onNodeWithText("白のシャツ").assertExists()
        compose.onNodeWithTag("save-outfit").performScrollTo().performClick()
        waitFor { model.diagnosisSaved }
        compose.onNodeWithText("保存しました").assertExists()
        assertTrue("図書館" in called("POST /outfits").single().second)   // 選んだ場所も送る
        assertEquals(2, called("POST /outfits/11/garments").size)
    }

    @Test fun unregisteredUserCannotCloseTheRegistrationSheet() {
        val model = showApp(session = null)
        compose.onNodeWithTag("registration-sheet").assertExists()
        compose.onNodeWithText("はじめに、ユーザー名を登録してください。").assertExists()
        compose.onNodeWithText("閉じる").assertDoesNotExist()             // 閉じるボタンが無い
        compose.onNodeWithTag("registration-submit").assertIsNotEnabled()  // 名前を入れるまで押せない
        compose.runOnIdle { assertTrue(model.mustRegister) }
        assertTrue(called("POST /users").isEmpty())
    }

    @Test fun registeredUserCanStillCloseTheRegistrationSheet() {
        val model = showApp()
        compose.runOnIdle { assertFalse(model.mustRegister) }
        compose.onNodeWithTag("registration-sheet").assertDoesNotExist()   // 登録済みなら自動では開かない
        compose.onNodeWithTag("open-registration").performClick()
        compose.onNodeWithText("あなたの名前で、今日のひろばへ。").assertExists()
        compose.onNodeWithText("閉じる").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("registration-sheet").assertDoesNotExist()
    }

    @Test fun suggestionComesFromSavedClothesAndCanBeSaved() {
        val model = showApp()
        // 201教室（23.4℃）なら、保存した服（ニット+ジーンズなど）で合う組み合わせがある
        compose.onNodeWithTag("home-screen").performScrollToNode(hasTestTag("home-room-picker"))
        compose.onNodeWithTag("home-room-picker").performClick()
        selectLocation("201教室")
        compose.onNodeWithTag("home-screen").performScrollToNode(hasTestTag("home-suggestion"))
        compose.onNodeWithTag("home-suggestion").performTouchInput { click(androidx.compose.ui.geometry.Offset(center.x, height - 12f)) }
        waitFor { model.suggestion != null || model.suggestionError != null }
        assertEquals("personal", model.suggestion?.mode)
        compose.onNodeWithTag("suggestion-result").assertExists()
        compose.onNodeWithText(model.suggestion!!.suggestions.first().title).assertExists()
        compose.onNodeWithTag("save-outfit").performScrollTo().performClick()
        waitFor { model.suggestionSaved }
        assertEquals(1, called("POST /users/7/recommendations").size)
    }

    @Test fun communityShowsServerPostsAndPosts() {
        val model = showApp()
        compose.onNodeWithTag("tab-COMMUNITY").performClick()
        selectLocation("図書館")
        waitFor { model.reviews.any { it.id == "post-5" } }
        compose.onNodeWithText("窓際が寒い").assertExists()
        compose.onNodeWithText("図書館の口コミ · 1件").assertExists()
        compose.onNodeWithText("いまの体感を投稿する").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("ちょうどいい")
        compose.onNodeWithText("投稿する").performScrollTo().performClick()
        waitFor { called("POST /rooms/1/posts").isNotEmpty() }
        assertEquals("""{"feeling":"快適","comment":"ちょうどいい"}""", called("POST /rooms/1/posts").single().second)
    }

    @Test fun outfitsTabShowsSavedOutfits() {
        val model = showApp()
        waitFor { model.logs.any { it.id == "9" } }
        compose.onNodeWithTag("tab-OUTFITS").performClick()
        compose.onNodeWithText("シャツ + パンツ").assertExists()
        compose.onNodeWithText("10月20日 · 図書館").assertExists()
        waitFor { model.logs.first().image != null }   // 上下を1枚にした写真も読み込む
    }
}
