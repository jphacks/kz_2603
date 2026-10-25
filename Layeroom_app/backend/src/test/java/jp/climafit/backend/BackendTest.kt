package jp.climafit.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import jp.climafit.backend.ai.Box
import jp.climafit.backend.ai.Coach
import jp.climafit.backend.ai.MannequinRenderer
import jp.climafit.backend.ai.OpenAi
import jp.climafit.backend.ai.OpenAiException
import jp.climafit.backend.ai.Vision
import jp.climafit.backend.ai.WornItem
import jp.climafit.backend.db.DbServer
import jp.climafit.backend.image.Crop
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException
import java.time.LocalDate
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val json = headersOf("Content-Type", "application/json")

/** db_server の偽物（MockEngine）。届いたリクエストを記録し、決めた返事を返す。 */
private class FakeDb {
    data class Call(val method: String, val path: String, val query: String, val token: String?, val body: String)

    val calls = mutableListOf<Call>()
    var down = false
    private val routes = mutableMapOf<String, suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>()

    fun on(key: String, body: String, status: HttpStatusCode = HttpStatusCode.OK) {
        routes[key] = { respond(body, status, json) }
    }

    /** 呼ばれるたびに、bodies を先頭から順に返す。 */
    fun onSequence(key: String, vararg bodies: String) {
        val queue = ArrayDeque(bodies.toList())
        routes[key] = { respond(queue.removeFirst(), HttpStatusCode.Created, json) }
    }

    fun onBytes(key: String, bytes: ByteArray) {
        routes[key] = { respond(bytes, HttpStatusCode.OK, headersOf("Content-Type", "image/jpeg")) }
    }

    fun last(path: String) = calls.last { it.path == path }

    val client = HttpClient(MockEngine { req ->
        if (down) throw IOException("connection refused")
        calls += Call(req.method.value, req.url.encodedPath, req.url.encodedQuery, req.headers["X-User-Token"], String(req.body.toByteArray()))
        val h = routes["${req.method.value} ${req.url.encodedPath}"] ?: return@MockEngine respond("""{"detail":"Not Found"}""", HttpStatusCode.NotFound, json)
        h(req)
    }) { expectSuccess = false }

    init {
        on("POST /users", """{"id":7,"user_id":7,"name":"x","token":"tok7"}""", HttpStatusCode.Created)
        on("GET /conditions/latest", """{"indoor":{"room_id":2,"room_name":"201教室","temperature":24.2,"humidity":62.0,
            "temperature_source":"sensor","humidity_source":"dummy","discomfort_index":71.0,"measured_at":"2026-10-24T10:00:00Z",
            "age_minutes":5.0,"stale":false},"outdoor":{"temperature":18.0,"humidity":90.0,"forecast_max_temp":19.5,
            "forecast_min_temp":16.4,"age_minutes":3.0},"diff":{"temperature":6.2}}""")
        on("GET /users/7/garments", "[]")
        on("GET /users/7/outfits", "[]")
    }
}

/** 写真の読み取り（OpenAI）の偽物。決めた服が写っていたことにする。 */
private class FakeVision(var items: List<WornItem>, var isOutfit: Boolean = true) : Vision {
    var calls = 0
    override suspend fun analyze(jpeg: ByteArray): Vision.Analysis {
        calls++
        return Vision.Analysis(isOutfit, items, "")
    }
}

private val UP = Box(0.2, 0.2, 0.6, 0.3)
private val LOW = Box(0.2, 0.55, 0.5, 0.4)
private fun item(t: String, color: String, box: Box) = WornItem(t, "normal", color, "all", 0.9, box)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BackendTest {
    private val db = FakeDb()
    private val vision = FakeVision(listOf(item("tshirt", "白", Box(0.25, 0.25, 0.3, 0.2)), item("jacket", "黒", UP), item("jeans", "青", LOW)))
    private var clock = 1_000_000L
    private val backend = LayeRoomBackend(
        DbServer(db.client, "http://db"), vision, Coach(null), Crop(jp.climafit.backend.image.AndroidImageOps()), InMemorySessionStore(),
        today = { LocalDate.of(2026, 10, 25) }, now = { clock },
    )

    private suspend fun login() = backend.start("たろう")

    private fun garmentJson(id: Int, slot: String, type: String, color: String, clo: Double, takenAt: String, extra: String = "") =
        """{"id":$id,"outfit_id":1,"part":"${if (slot == "upper") "top" else "bottom"}","taken_at":"$takenAt","image_url":"/garments/$id/image",
            "meta":{"type":"$type","color":"$color","clo":$clo,"slot":"$slot","thickness":"normal"$extra}}"""

    // ---------- ログイン ----------
    @Test
    fun `ニックネームで始めると db_server に利用者を作って覚える`() = runTest {
        val s = login()
        assertEquals(Session("たろう", 7, "tok7"), s)
        assertEquals("""{"name":"たろう"}""", db.last("/users").body)
        assertEquals(s, backend.session())
        val e = assertFailsWith<BackendException> { backend.start("  ") }
        assertEquals(ErrorKind.INPUT, e.kind)
        assertFailsWith<BackendException> { backend.start("あ".repeat(31)) }
    }

    @Test
    fun `ログイン前は使えない`() = runTest {
        assertEquals(ErrorKind.NOT_LOGGED_IN, assertFailsWith<BackendException> { backend.garments() }.kind)
    }

    // ---------- 写真診断 → 保存 ----------
    @Test
    fun `写真診断は保存せず、保存で上下2枚とコーデ写真を送る`() = runTest {
        login()
        db.on("POST /outfits", """{"id":11}""", HttpStatusCode.Created)
        db.onSequence("POST /outfits/11/garments", """{"id":21}""", """{"id":22}""")
        val r = backend.review(TestImages.photo(), "201教室")
        // Tシャツ+ジャケット+ジーンズ(0.99clo) と 24.2℃(必要0.69clo) → 上着を脱げばちょうどいい
        assertEquals("ok_remove", r.verdict)
        assertEquals(listOf("ジャケット"), r.removable)
        assertEquals(24.2, r.indoorTemp)
        assertTrue(r.humidityIsDummy)                       // 湿度が仮の値なら判定に使わない
        assertEquals(listOf("upper", "lower"), r.items.map { it.slot })
        assertEquals("白のTシャツ + 黒のジャケット", r.items[0].detail)
        assertTrue(r.itemImages.all { it != null && it[0] == 0xFF.toByte() })   // 切り抜き（JPEG）
        assertEquals("ok", r.outdoor?.verdict)
        assertTrue(r.advice.contains("201教室"))             // OpenAI 無しなら定型文
        assertTrue(db.calls.none { it.path == "/outfits" })   // まだ保存していない
        assertEquals("classroom=201%E6%95%99%E5%AE%A4", db.last("/conditions/latest").query)

        val saved = backend.save(r.draftId)
        assertEquals(11, saved.outfitId)
        assertEquals(listOf(21L, 22L), saved.items.map { it.garmentId })

        val outfit = db.last("/outfits")
        assertEquals("tok7", outfit.token)
        assertTrue("201教室" in outfit.body && "\"level\":\"" in outfit.body)   // 場所と判定内容（analysis）
        val garments = db.calls.filter { it.path == "/outfits/11/garments" }
        assertEquals(2, garments.size)
        assertTrue("name=part\r\n" in garments[0].body && "\r\ntop\r\n" in garments[0].body, garments[0].body.take(400))
        assertTrue("\"type\":\"jacket\"" in garments[0].body && "\"slot\":\"upper\"" in garments[0].body)
        assertTrue("\r\nbottom\r\n" in garments[1].body)

        // 同じ下書きは2回保存できない
        assertEquals(ErrorKind.NOT_FOUND, assertFailsWith<BackendException> { backend.save(r.draftId) }.kind)
    }

    @Test
    fun `下書きは30分で消える`() = runTest {
        login()
        val r = backend.review(TestImages.photo(), "201教室")
        clock += 31 * 60 * 1000L
        assertEquals(ErrorKind.NOT_FOUND, assertFailsWith<BackendException> { backend.save(r.draftId) }.kind)
    }

    @Test
    fun `服が写っていない・自信が低いときは保存の前に止める`() = runTest {
        login()
        vision.isOutfit = false
        assertEquals(ErrorKind.NO_CLOTHES, assertFailsWith<BackendException> { backend.review(TestImages.photo(), "") }.kind)
        vision.isOutfit = true
        vision.items = listOf(WornItem("tshirt", confidence = 0.2, bbox = UP))
        assertEquals(ErrorKind.NO_CLOTHES, assertFailsWith<BackendException> { backend.review(TestImages.photo(), "") }.kind)
        assertEquals(ErrorKind.INPUT, assertFailsWith<BackendException> { backend.review("not image".toByteArray(), "") }.kind)
    }

    // ---------- 提案 ----------
    @Test
    fun `服が4着未満なら気温だけの一般的な提案`() = runTest {
        login()
        val s = backend.suggestions("201教室")
        assertEquals("general", s.mode)
        assertTrue(s.suggestions.isEmpty() && s.general.size == 2)
        assertTrue(s.message.contains("あと4着分"))
    }

    @Test
    fun `保存した服から提案し、昨日着た服と隠した服は除く`() = runTest {
        login()
        db.on("GET /users/7/garments", "[" + listOf(
            garmentJson(1, "upper", "shirt", "白", 0.25, "2026-10-20T03:00:00Z"),
            garmentJson(2, "lower", "pants", "黒", 0.2, "2026-10-20T03:00:00Z"),
            garmentJson(3, "upper", "hoodie", "灰", 0.4, "2026-10-24T03:00:00Z"),   // 昨日（JST 10/24）
            garmentJson(4, "lower", "jeans", "青", 0.25, "2026-10-20T03:00:00Z"),
            garmentJson(5, "upper", "knit", "茶", 0.35, "2026-10-20T03:00:00Z", ""","hidden":true"""),
            """{"id":6,"part":"top","taken_at":"2026-10-20T03:00:00Z","meta":null}""",   // 種類が分からない服
        ).joinToString(",") + "]")
        val g = backend.garments()
        assertEquals(listOf(4L, 3L, 2L, 1L), g.garments.map { it.garmentId })
        val s = backend.suggestions("201教室")
        assertEquals("personal", s.mode)
        assertEquals(1, s.excludedYesterday)
        assertTrue(s.suggestions.isNotEmpty())
        assertTrue(s.suggestions.all { sug -> sug.garments.none { it.garmentId == 3L || it.garmentId == 5L } })
        assertNotNull(s.suggestions[0].upperId)
    }

    // ---------- 気温別コーデ・感想・おすすめ ----------
    @Test
    fun `気温別コーデは保存時の室温・場所で、感想は日本語に戻す`() = runTest {
        login()
        db.on("GET /users/7/garments", "[" + garmentJson(1, "upper", "shirt", "白", 0.25, "2026-10-20T03:00:00Z") + "]")
        db.on("GET /users/7/outfits", """[
            {"id":9,"taken_at":"2026-10-20T03:00:00Z","verdict":"good","comment":"c","user_feedback":"hot",
             "conditions":{"indoor":{"room_name":"図書館","temperature":21.0},"outdoor":{"temperature":15.0}},"garments":[{"id":1}]},
            {"id":8,"taken_at":"2026-10-19T03:00:00Z","verdict":"bad","conditions":{"indoor":{"room_name":"201教室","temperature":26.0},"outdoor":null},"garments":[]}]""")
        val all = backend.outfits()
        assertEquals(listOf(9L, 8L), all.map { it.outfitId })
        assertEquals("図書館", all[0].classroom)
        assertEquals("暑かった", all[0].userFeedback)
        assertEquals("良い", all[0].verdictJa)
        assertEquals("シャツ", all[0].title)
        assertEquals(listOf(8L), backend.outfits(temp = 25.0, kind = "indoor").map { it.outfitId })
        assertEquals(listOf(9L), backend.outfits(temp = 16.0).map { it.outfitId })

        db.on("PATCH /outfits/9/feedback", "{}")
        backend.feedback(9, "寒かった")
        assertEquals("""{"feedback":"寒かった"}""", db.last("/outfits/9/feedback").body)
        assertEquals(ErrorKind.INPUT, assertFailsWith<BackendException> { backend.feedback(9, "hot") }.kind)
    }

    @Test
    fun `おすすめの保存と最新`() = runTest {
        login()
        db.on("GET /users/7/garments", "[" + garmentJson(1, "upper", "shirt", "白", 0.25, "2026-10-20T03:00:00Z") + "," +
            garmentJson(2, "lower", "pants", "黒", 0.2, "2026-10-20T03:00:00Z") + "]")
        db.on("GET /users/7/recommendations/latest", """{"detail":"no recommendation yet"}""", HttpStatusCode.NotFound)
        assertNull(backend.latestRecommendation())
        db.on("POST /users/7/recommendations", "{}", HttpStatusCode.Created)
        backend.saveRecommendation(1, 2, "今日は暖かい")
        assertEquals("""{"top_garment_id":1,"bottom_garment_id":2,"reason":"今日は暖かい"}""", db.last("/users/7/recommendations").body)
        db.on("GET /users/7/recommendations/latest", """{"id":1,"top":{"id":1},"bottom":{"id":2},"reason":"r","created_at":"t"}""")
        val r = backend.latestRecommendation()!!
        assertEquals(listOf(1L, 2L), listOf(r.top?.garmentId, r.bottom?.garmentId))
    }

    @Test
    fun `誤認識の服は meta に hidden を付けて隠す`() = runTest {
        login()
        db.on("PATCH /garments/5/meta", "{}")
        backend.hideGarment(5)
        assertEquals("""{"hidden":true}""", db.last("/garments/5/meta").body)
        assertEquals("tok7", db.last("/garments/5/meta").token)
    }

    @Test
    fun `上下の画像を1枚にする`() = runTest {
        login()
        db.onBytes("GET /garments/1/image", TestImages.photo(200, 300, 0xFF0000))
        db.onBytes("GET /garments/2/image", TestImages.photo(200, 100, 0x0000FF))
        assertEquals(480 to (24 + 300 + 24 + 100 + 24), TestImages.size(backend.comboImage(1, 2)))
    }

    /** マネキン画像を作る AI の偽物。受け取った内容を記録する。 */
    private class FakeMannequin(var fail: Boolean = false) : MannequinRenderer {
        val calls = mutableListOf<List<Any?>>()
        override suspend fun render(upper: ByteArray?, lower: ByteArray?, upperDesc: String?, lowerDesc: String?): ByteArray {
            calls += listOf(upper?.size, lower?.size, upperDesc, lowerDesc)
            if (fail) throw OpenAiException("失敗")
            return byteArrayOf(1, 2, 3)
        }
    }

    private fun backendWith(mannequin: MannequinRenderer?) = LayeRoomBackend(
        DbServer(db.client, "http://db"), vision, Coach(null), Crop(jp.climafit.backend.image.AndroidImageOps()), InMemorySessionStore(),
        today = { LocalDate.of(2026, 10, 25) }, now = { clock }, mannequin = mannequin,
    )

    @Test
    fun `マネキン画像は上下の画像と説明を渡して作り、同じ組み合わせは作り直さない`() = runTest {
        val fake = FakeMannequin()
        val b = backendWith(fake)
        b.start("たろう")
        db.on("GET /users/7/garments", "[${garmentJson(1, "upper", "tshirt", "白", 0.09, "2026-10-20T00:00:00Z", ""","detail":"白のTシャツ"""")}," +
            "${garmentJson(2, "lower", "jeans", "青", 0.25, "2026-10-20T00:00:00Z", ""","detail":"青のジーンズ"""")}]")
        db.onBytes("GET /garments/1/image", ByteArray(10))
        db.onBytes("GET /garments/2/image", ByteArray(20))
        assertTrue(b.canRenderMannequin)
        assertContentEquals(byteArrayOf(1, 2, 3), b.mannequinImage(1, 2))
        assertEquals(listOf<Any?>(10, 20, "白のTシャツ", "青のジーンズ"), fake.calls.single())
        assertContentEquals(byteArrayOf(1, 2, 3), b.mannequinImage(1, 2))
        assertEquals(1, fake.calls.size)                                   // 2回目はキャッシュ
        b.mannequinImage(1, null)                                          // 下の服なし（ワンピースなど）
        assertEquals(listOf<Any?>(10, null, "白のTシャツ", null), fake.calls.last())
    }

    @Test
    fun `マネキン画像は AI が無ければ null、失敗したら AI のエラー`() = runTest {
        login()
        assertTrue(!backend.canRenderMannequin)
        assertNull(backend.mannequinImage(1, 2))
        val b = backendWith(FakeMannequin(fail = true))
        b.start("たろう")
        db.onBytes("GET /garments/1/image", ByteArray(10))
        assertEquals(ErrorKind.AI, assertFailsWith<BackendException> { b.mannequinImage(1, null) }.kind)
        assertEquals(ErrorKind.NOT_FOUND, assertFailsWith<BackendException> { b.mannequinImage(null, null) }.kind)
    }

    // ---------- 提案タブの気温・場所・ひろば ----------
    @Test
    fun `提案タブの気温と注意書き`() = runTest {
        val c = backend.conditions("201教室")
        assertEquals("201教室", c.indoor?.roomName)
        assertEquals("sensor", c.indoor?.temperatureSource)
        assertEquals("金沢", c.outdoor?.location)
        assertEquals(19.5, c.forecastMax)
        assertTrue(c.notices.any { "湿度は仮の値" in it })
    }

    @Test
    fun `ひろばの投稿は送る前に確かめる`() = runTest {
        login()
        db.on("POST /rooms/2/posts", """{"id":1,"room_id":2,"feeling":"slightly_cold","feeling_label":"少し寒い","comment":"寒い","created_at":"t"}""", HttpStatusCode.Created)
        val p = backend.post(2, "少し寒い", "  寒い  ")
        assertEquals("少し寒い", p.feelingLabel)
        assertEquals("""{"feeling":"少し寒い","comment":"寒い"}""", db.last("/rooms/2/posts").body)
        val before = db.calls.size
        assertEquals(ErrorKind.INPUT, assertFailsWith<BackendException> { backend.post(2, "快適", " ") }.kind)
        assertEquals(ErrorKind.INPUT, assertFailsWith<BackendException> { backend.post(2, "快適", "あ".repeat(501)) }.kind)
        assertEquals(ErrorKind.INPUT, assertFailsWith<BackendException> { backend.post(2, "暑い", "x") }.kind)
        assertEquals(before, db.calls.size)
    }

    @Test
    fun `場所の一覧`() = runTest {
        db.on("GET /rooms", """[{"id":2,"name":"201教室","temperature":24.2,"humidity":62.0,"is_dummy":false,"temperature_source":"sensor",
            "humidity_source":"dummy","measured_at":"t","stale":true,"today":{"comfortable":1,"slightly_cold":2,"very_cold":0,"slightly_hot":0,"total":3}}]""")
        val r = backend.rooms().single()
        assertEquals("sensor", r.temperatureSource)
        assertEquals(3, r.today.total)
    }

    // ---------- エラー ----------
    @Test
    fun `db_server のエラーを画面向けに直す`() = runTest {
        login()
        db.on("GET /users/7/garments", """{"detail":"invalid user token"}""", HttpStatusCode.Unauthorized)
        assertEquals(ErrorKind.NOT_LOGGED_IN, assertFailsWith<BackendException> { backend.garments() }.kind)
        db.down = true
        val e = assertFailsWith<BackendException> { backend.rooms() }
        assertEquals(ErrorKind.SERVER, e.kind)
        assertTrue("接続できません" in e.message!!)
    }

    // ---------- OpenAI ----------
    @Test
    fun `OpenAI は使えないモデルなら次を試し、アドバイスは失敗しても定型文`() = runTest {
        val tried = mutableListOf<String>()
        val http = HttpClient(MockEngine { req ->
            val model = Json.parseToJsonElement(String(req.body.toByteArray())).jsonObject["model"].toString().trim('"')
            tried += model
            assertEquals("Bearer sk-test", req.headers["Authorization"])
            if (model == "gpt-4o") respond("{}", HttpStatusCode.NotFound, json)
            else respond("""{"choices":[{"message":{"content":"{\"is_outfit_photo\":true,\"items\":[{\"type\":\"shirt\",\"color\":\"白\",\"thickness\":\"thin\",\"season\":\"all\",\"bbox\":{\"x\":0.1,\"y\":0.1,\"w\":0.5,\"h\":0.4},\"confidence\":0.9}],\"note\":\"\"}"}}]}""", HttpStatusCode.OK, json)
        }) { expectSuccess = false }
        val a = jp.climafit.backend.ai.OpenAiVision(OpenAi(http, "sk-test")).analyze(byteArrayOf(1))
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), tried)
        assertEquals(WornItem("shirt", "thin", "白", "all", 0.9, Box(0.1, 0.1, 0.5, 0.4)), a.items.single())

        val broken = HttpClient(MockEngine { respond("boom", HttpStatusCode.InternalServerError) }) { expectSuccess = false }
        val facts = jp.climafit.backend.ai.AdviceFacts("general", "図書館", 21.0, null, null, null, "", true, "ok", "ちょうどいい",
            1.0, 1.0, emptyList(), emptyList(), null, "")
        assertEquals(Coach.template(facts), Coach(OpenAi(broken, "sk-test")).write(facts))
        assertFailsWith<jp.climafit.backend.ai.VisionException> { jp.climafit.backend.ai.OpenAiVision(OpenAi(broken, null)).analyze(byteArrayOf(1)) }
    }
}
