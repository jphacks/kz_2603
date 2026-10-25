package jp.climafit.backend

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import jp.climafit.backend.ai.AdviceFacts
import jp.climafit.backend.ai.Coach
import jp.climafit.backend.ai.Comfort
import jp.climafit.backend.ai.General
import jp.climafit.backend.ai.Garment
import jp.climafit.backend.ai.MannequinRenderer
import jp.climafit.backend.ai.OpenAi
import jp.climafit.backend.ai.OpenAiMannequin
import jp.climafit.backend.ai.OpenAiVision
import jp.climafit.backend.ai.OutdoorInfo
import jp.climafit.backend.ai.Preference
import jp.climafit.backend.ai.Slots
import jp.climafit.backend.ai.Suggest
import jp.climafit.backend.ai.Vision
import jp.climafit.backend.ai.VisionException
import jp.climafit.backend.ai.WornItem
import jp.climafit.backend.ai.fmt0
import jp.climafit.backend.db.DbServer
import jp.climafit.backend.db.DbServerException
import jp.climafit.backend.image.AndroidImageOps
import jp.climafit.backend.image.Crop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * バックエンド（端末内）。画面はこのクラスだけを呼ぶ。
 * - 保存・取得は db_server（Ubuntu）。端末に残すのはログイン情報だけ。
 * - 写真の読み取りとアドバイス文は OpenAI。暑い/寒いの判定と提案はプログラム（ai パッケージ）。
 * 中身は、以前の Python 版 AI（router.py）と Kotlin のサーバー（/api）を1つにまとめたもの。
 */
class LayeRoomBackend(
    private val db: DbServer,
    private val vision: Vision,
    private val coach: Coach,
    private val crop: Crop,
    private val sessions: SessionStore,
    private val locationName: String = "金沢",
    private val today: () -> LocalDate = { LocalDate.now(JST) },
    private val now: () -> Long = System::currentTimeMillis,
    /** 提案カードのマネキン画像を作る（AI）。null なら作らない。 */
    private val mannequin: MannequinRenderer? = null,
) {
    companion object {
        val JST: ZoneId = ZoneId.of("Asia/Tokyo")
        const val MAX_PHOTO_BYTES = 10 * 1024 * 1024
        const val MIN_CONFIDENCE = 0.4
        const val DRAFT_TTL_MILLIS = 30 * 60 * 1000L
        const val STALE_MINUTES = 30
        const val DUMMY_HUMIDITY = 55.0
        val FEEDBACKS = listOf("暑かった", "寒かった", "ちょうどいい")
        private val FEEDBACK_JA = mapOf("hot" to "暑かった", "cold" to "寒かった", "just_right" to "ちょうどいい")
        private val VERDICT_JA = Comfort.VERDICT_JA + mapOf("good" to "良い", "bad" to "悪い")
        private val PART = mapOf("upper" to "top", "lower" to "bottom")
        private val SLOT = mapOf("top" to "upper", "outer" to "upper", "bottom" to "lower")

        /** ひろばの体感（db_server の選択肢と同じ）。 */
        val FEELINGS = listOf(
            Feeling("comfortable", "快適"), Feeling("slightly_cold", "少し寒い"),
            Feeling("very_cold", "かなり寒い"), Feeling("slightly_hot", "少し暑い"),
        )

        /**
         * アプリから使うときの作り方。
         * openAiKey を空にして openAiBaseUrl に中継サーバーを指定すると、キーを端末に置かずに済む。
         */
        fun create(
            context: Context,
            dbServerUrl: String,
            openAiKey: String?,
            openAiBaseUrl: String = "https://api.openai.com/v1",
            /** マネキン画像に使うモデル（空なら既定の候補を順に試す）。 */
            openAiImageModel: String? = null,
        ): LayeRoomBackend {
            val http = HttpClient(OkHttp) {
                expectSuccess = false
                install(HttpTimeout) {
                    connectTimeoutMillis = 10_000
                    requestTimeoutMillis = 180_000   // 写真の読み取りは十数秒、マネキン画像の生成は1分以上かかることがある
                    // 生成中は OpenAI から何も届かない。OkHttp の既定（10秒）のままだと途中で切れるので延ばす
                    socketTimeoutMillis = 180_000
                }
            }
            val imageModels = listOfNotNull(openAiImageModel?.ifBlank { null }) + OpenAi.DEFAULT_IMAGE_MODELS
            val openAi = OpenAi(http, openAiKey, openAiBaseUrl, imageModels = imageModels.distinct())
            return LayeRoomBackend(
                DbServer(http, dbServerUrl), OpenAiVision(openAi), Coach(openAi), Crop(AndroidImageOps()),
                SharedPreferencesSessionStore(context),
                mannequin = OpenAiMannequin(openAi).takeIf { openAi.isConfigured },
            )
        }

        fun dateOf(s: String?): String? {
            if (s.isNullOrBlank()) return null
            val instant = runCatching { OffsetDateTime.parse(s).toInstant() }
                .recoverCatching { LocalDateTime.parse(s).toInstant(ZoneOffset.UTC) }   // タイムゾーン無しは UTC
                .getOrNull() ?: return null
            return instant.atZone(JST).toLocalDate().toString()
        }
    }

    // ======================================================================
    // ログイン（ニックネームで始める。ログイン画面は無い）
    // ======================================================================
    fun session(): Session? = sessions.load()

    /** 初回に1回。db_server に利用者を作り、端末に覚える。 */
    suspend fun start(nickname: String): Session {
        val nick = nickname.trim()
        if (nick.codePointCount(0, nick.length) !in 1..30) throw BackendException(ErrorKind.INPUT, "ニックネームは1〜30文字で入力してください")
        val (id, token) = remote { db.createUser(nick) }
        return Session(nick, id, token).also(sessions::save)
    }

    fun signOut() = sessions.clear()

    private fun requireSession(): Session =
        sessions.load() ?: throw BackendException(ErrorKind.NOT_LOGGED_IN, "ニックネームを登録してください")

    // ======================================================================
    // 場所・みんなのひろば
    // ======================================================================
    /** 場所の一覧（画面間で共通のプルダウン）。センサーの部屋は実測、ほかは仮の値。今日の体感の件数つき。 */
    suspend fun rooms(): List<Room> = remote { db.rooms() }.mapNotNull { it.obj() }.map { r ->
        val t = r.obj("today")
        Room(
            id = r.long("id") ?: 0, name = r.str("name").orEmpty(),
            temperature = r.num("temperature"), humidity = r.num("humidity"),
            temperatureSource = r.str("temperature_source") ?: if (r.bool("is_dummy") == false) "sensor" else "dummy",
            humiditySource = r.str("humidity_source") ?: "dummy",
            isDummy = r.bool("is_dummy") ?: true, measuredAt = r.str("measured_at"), stale = r.bool("stale") ?: false,
            today = FeelingCounts(
                t?.int("comfortable") ?: 0, t?.int("slightly_cold") ?: 0, t?.int("very_cold") ?: 0,
                t?.int("slightly_hot") ?: 0, t?.int("total") ?: 0,
            ),
        )
    }

    fun feelings(): List<Feeling> = FEELINGS

    /** その場所の投稿（新しい順）。all=false なら今日の分だけ。 */
    suspend fun posts(roomId: Long, all: Boolean = false, limit: Int? = null, offset: Int? = null): List<Post> =
        remote { db.roomPosts(roomId, all, limit, offset) }.mapNotNull { it.obj()?.let(::toPost) }

    /** 体感を投稿する。ひとことは1〜500文字（画面と同じ）。 */
    suspend fun post(roomId: Long, feeling: String, comment: String): Post {
        val s = requireSession()
        if (FEELINGS.none { it.code == feeling || it.label == feeling }) {
            throw BackendException(ErrorKind.INPUT, "体感は ${FEELINGS.joinToString(" / ") { it.label }} のどれかにしてください")
        }
        val text = comment.trim()
        if (text.codePointCount(0, text.length) !in 1..500) throw BackendException(ErrorKind.INPUT, "ひとことは1〜500文字で入力してください")
        return toPost(remote { db.createPost(s.token, roomId, feeling, text) })
    }

    private fun toPost(p: JsonObject) = Post(
        p.long("id") ?: 0, p.long("room_id") ?: 0, p.str("feeling").orEmpty(), p.str("feeling_label").orEmpty(),
        p.str("comment"), p.str("created_at").orEmpty(),
    )

    // ======================================================================
    // 提案タブの気温
    // ======================================================================
    /** 選んだ場所の室温・湿度（センサーの部屋は実測、ほかは仮の値）、外の気温、予報。 */
    suspend fun conditions(classroom: String?): Conditions {
        val c = remote { db.conditions(classroom) }
        val ind = c.obj("indoor")
        val out = c.obj("outdoor")
        val notices = mutableListOf<String>()
        val indoor = ind?.num("temperature")?.let { t ->
            val tSrc = ind.str("temperature_source") ?: "sensor"
            val hSrc = ind.str("humidity_source") ?: "api"
            if (tSrc == "dummy") notices += "${ind.str("room_name") ?: classroom}の室温・湿度は仮の値です"
            else {
                if (ind.bool("stale") == true) notices += "室温は${ind.num("age_minutes")?.toLong() ?: "?"}分前の値です（センサーが止まっているかもしれません）"
                when (hSrc) {
                    "dummy" -> notices += "湿度は仮の値です（湿度センサーが無いため）"
                    "api" -> notices += "室内の湿度は、外の湿度で代用しています"
                }
            }
            Indoor(
                ind.str("room_name"), t, ind.num("humidity"), ind.num("discomfort_index"), tSrc, hSrc,
                ind.str("measured_at"), ind.num("age_minutes"), ind.bool("stale") ?: false,
            )
        }
        if (indoor == null) notices += "室温のデータがまだありません"
        if (out == null) notices += "外の天気がまだ取得できていません"
        return Conditions(
            classroom, indoor,
            out?.let {
                Outdoor(locationName, it.num("temperature"), it.num("humidity"), it.int("weather_code"), it.num("precipitation"),
                    it.num("discomfort_index"), it.str("fetched_at"), it.num("age_minutes"))
            },
            out?.num("forecast_max_temp"), out?.num("forecast_min_temp"),
            c.obj("diff")?.num("temperature"), notices,
        )
    }

    /** 判定に使う室内外の値（以前の Python 版 climate.py の db_server の部分）。 */
    private data class Climate(
        val temp: Double, val humidity: Double, val humidityShared: Boolean, val humidityIsDummy: Boolean,
        val di: Double?, val diLabel: String, val outdoorTemp: Double?, val outdoorHumidity: Double?, val outdoorDi: Double?,
        val warnings: List<String>,
    ) {
        val notice: String
            get() = when {
                !humidityIsDummy -> ""
                humidityShared -> "湿度は室内外で共通の値です（室内の湿度センサーが未接続）。判定は気温だけで行っています。"
                else -> "室内の湿度は使っていません（センサー未接続）。判定は気温だけで行っています。"
            }
    }

    private suspend fun climate(classroom: String): Climate {
        val c = remote { db.conditions(classroom) }
        val ind = c.obj("indoor"); val out = c.obj("outdoor")
        val warnings = mutableListOf<String>()
        ind?.num("age_minutes")?.let { if (it > STALE_MINUTES) warnings += "室温センサーの値が${it.toInt()}分前のものです" }
        out?.num("age_minutes")?.let { if (it > STALE_MINUTES) warnings += "外の天気の値が${it.toInt()}分前のものです" }
        val temp = ind?.num("temperature") ?: throw BackendException(ErrorKind.SERVER, "室温が分かりません（DBサーバーに室温のデータがありません）")
        var ih = ind.num("humidity")
        val outT = out?.num("temperature")
        var oh = out?.num("humidity")
        val source = ind.str("humidity_source")
        var shared = false
        if (ih == null && oh != null) { ih = oh; shared = true }
        else if (oh == null && ih != null && outT != null) { oh = ih; shared = true }
        if (source == "api") shared = true   // 室内の湿度センサーが無く、外の湿度で代用している
        val missing = ih == null
        val humidity = ih ?: DUMMY_HUMIDITY
        val dummy = missing || source != "sensor"   // 湿度は db_server が「実測」と言うときだけ使う
        val di = if (dummy) null else Comfort.discomfortIndex(temp, humidity)
        val outDi = if (!dummy && outT != null && oh != null) Comfort.discomfortIndex(outT, oh) else null
        return Climate(temp, humidity, shared, dummy, di, di?.let(Comfort::diLabel).orEmpty(), outT, oh, outDi, warnings)
    }

    // ======================================================================
    // 服
    // ======================================================================
    private suspend fun myGarments(s: Session): List<Garment> = remote { db.garments(s.userId, s.token) }
        .mapNotNull { it.obj() }
        .mapNotNull { r ->
            val m = r.obj("meta") ?: return@mapNotNull null
            if (m.bool("hidden") == true) return@mapNotNull null   // 誤認識で隠した服
            val type = m.str("type"); val color = m.str("color"); val clo = m.num("clo")
            if (type == null || color == null || clo == null) return@mapNotNull null   // 種類・暖かさが分からない服は使えない
            Garment(
                id = r.long("id") ?: return@mapNotNull null,
                slot = m.str("slot") ?: SLOT[r.str("part")] ?: "upper",
                type = type, color = color, clo = clo,
                wholeBody = m.bool("whole_body") ?: false,
                thickness = m.str("thickness") ?: "normal", season = m.str("season") ?: "all",
                detail = m.str("detail") ?: color,
                up = m.int("up") ?: 0, down = m.int("down") ?: 0, wearCount = 1,
                takenDate = dateOf(r.str("taken_at") ?: r.str("outfit_taken_at") ?: r.str("created_at")),
            )
        }
        .sortedByDescending { it.id }

    private fun view(g: Garment) = GarmentView(g.id, g.slot, g.type, Comfort.typeJa(g.type), g.color, g.thickness, g.clo, g.detail)

    /** 保存した服。slot = "upper" / "lower" で絞れる。 */
    suspend fun garments(slot: String? = null): Garments {
        if (slot != null && slot !in setOf("upper", "lower")) throw BackendException(ErrorKind.INPUT, "slot は upper か lower です")
        val all = myGarments(requireSession())
        return Garments(all.filter { slot == null || it.slot == slot }.map(::view), Preference.build(all).text)
    }

    /** 誤認識した服を一覧から消す（db_server は削除しない方針なので、meta に hidden を付けて隠す）。 */
    suspend fun hideGarment(garmentId: Long) {
        val s = requireSession()
        remote { db.patchMeta(s.token, garmentId, buildJsonObject { put("hidden", true) }) }
    }

    suspend fun garmentImage(garmentId: Long): ByteArray {
        val s = requireSession()
        return remote { db.garmentImage(s.token, garmentId) }
    }

    /** 上の服と下の服を縦に並べた1枚の画像（提案カード・気温別コーデ用）。 */
    suspend fun comboImage(upperId: Long?, lowerId: Long?): ByteArray {
        val blobs = listOfNotNull(upperId, lowerId).map { garmentImage(it) }
        if (blobs.isEmpty()) throw BackendException(ErrorKind.NOT_FOUND, "画像がありません")
        return withContext(Dispatchers.Default) { crop.combine(blobs.getOrNull(0), blobs.getOrNull(1)) }
    }

    private val mannequinCache = ConcurrentHashMap<Pair<Long?, Long?>, ByteArray>()

    /** マネキン画像を作れるか（OpenAI が使えるか）。 */
    val canRenderMannequin: Boolean get() = mannequin != null

    /**
     * 上下の服を着たマネキンの画像（提案カード用・AIで生成）。作れないときは null。
     * 生成には時間と費用がかかるので、同じ組み合わせはアプリを閉じるまで作り直さない。
     */
    suspend fun mannequinImage(upperId: Long?, lowerId: Long?): ByteArray? {
        val renderer = mannequin ?: return null
        if (upperId == null && lowerId == null) throw BackendException(ErrorKind.NOT_FOUND, "画像がありません")
        val key = upperId to lowerId
        mannequinCache[key]?.let { return it }
        val byId = myGarments(requireSession()).associateBy { it.id }
        val upper = upperId?.let { garmentImage(it) }
        val lower = lowerId?.let { garmentImage(it) }
        val bytes = try {
            renderer.render(upper, lower, upperId?.let(byId::get)?.detail, lowerId?.let(byId::get)?.detail)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BackendException(ErrorKind.AI, "マネキンの画像を作れませんでした（${e.message}）", e)
        }
        mannequinCache[key] = bytes
        return bytes
    }

    // ======================================================================
    // 写真でコーデ診断 → 保存
    // ======================================================================
    private class Draft(
        val nick: String, val classroom: String, val clim: Climate, val result: Comfort.Judge, val outdoor: OutdoorInfo?,
        val advice: String, val tip: String, val detected: List<WornItem>, val slots: List<Pair<Slots.Pick, ByteArray?>>,
        val outfitJpeg: ByteArray, val createdAt: Long,
    )

    private val drafts = ConcurrentHashMap<String, Draft>()

    /** 写真で診断する。まだ保存しない（保存は save(draftId)。30分で消える）。 */
    suspend fun review(photo: ByteArray, classroom: String, message: String = ""): Review {
        val s = requireSession()
        if (photo.size > MAX_PHOTO_BYTES) throw BackendException(ErrorKind.INPUT, "画像が大きすぎます（10MBまで）")
        val img = withContext(Dispatchers.Default) {
            runCatching { crop.open(photo) }.getOrElse { throw BackendException(ErrorKind.INPUT, "画像を読み込めませんでした") }
        }
        val clim = climate(classroom)
        val analysis = try {
            vision.analyze(withContext(Dispatchers.Default) { crop.prepareForModel(img) })
        } catch (e: VisionException) {
            throw BackendException(ErrorKind.AI, e.message ?: "写真を読み取れませんでした", e)
        }
        val items = analysis.items.filter { it.type in Comfort.GARMENT_TYPES && it.confidence >= MIN_CONFIDENCE }
        if (!analysis.isOutfitPhoto || items.isEmpty()) {
            throw BackendException(ErrorKind.NO_CLOTHES, "服が見つかりませんでした。全身か上半身が写るように撮り直してください（顔は写さなくて大丈夫です）。")
        }

        val need = Comfort.requiredClo(clim.temp, clim.di, clim.humidityIsDummy)
        val result = Comfort.judge(items, need)   // 判定には写っている服を全部使う
        val outdoor = clim.outdoorTemp?.let { t ->
            val o = Comfort.judgeOutdoor(result.outfitClo, Comfort.requiredCloOutdoor(t))
            OutdoorInfo(t, clim.outdoorHumidity, clim.outdoorDi, o.verdict, o.verdictJa, o.needClo)
        }
        // 好みは「集計」だけを使う。環境とコーデを結びつけた記憶（前回の判定など）は使わない。
        val prior = myGarments(s)
        val mode = if (prior.size >= Preference.MIN_GARMENTS) "personal" else "general"
        val pref = Preference.build(prior)
        val detected = items.map {
            AdviceFacts.Detected(Comfort.typeJa(it.type), it.color, Comfort.THICKNESS_JA[it.thickness].orEmpty(), Comfort.itemClo(it.type, it.thickness))
        }
        val text = coach.write(AdviceFacts(
            mode, classroom, clim.temp, clim.outdoorTemp, clim.outdoorHumidity, clim.di, clim.diLabel, clim.humidityIsDummy,
            result.verdict, result.verdictJa, result.needClo, result.outfitClo, result.removable, detected, outdoor,
            if (mode == "personal") pref.text else "", message,
        ))

        // 保存するのは「上」と「下」の2枚だけ（重複しても登録する）。コーデ写真は服の範囲だけに切る。
        val (slotData, outfitJpeg) = withContext(Dispatchers.Default) {
            Slots.pick(items).map { it to crop.cropBytes(img, it.item.bbox) } to crop.outfitPhoto(img, items.map { it.bbox })
        }
        dropOldDrafts()
        val draftId = UUID.randomUUID().toString().replace("-", "")
        drafts[draftId] = Draft(s.nickname, classroom, clim, result, outdoor, text.advice, text.tip, items, slotData, outfitJpeg, now())

        return Review(
            draftId, mode, result.verdict, result.verdictJa, result.score, result.needClo, result.outfitClo, result.removable,
            clim.temp, clim.humidity, clim.humidityIsDummy, clim.di, clim.outdoorTemp, outdoor,
            items.map { GarmentView(null, Comfort.layerOf(it.type), it.type, Comfort.typeJa(it.type), it.color, it.thickness, Comfort.itemClo(it.type, it.thickness), "${it.color}の${Comfort.typeJa(it.type)}") },
            slotData.map { (p, _) -> GarmentView(null, p.slot, p.item.type, Comfort.typeJa(p.item.type), p.item.color, p.item.thickness, p.clo, p.detail) },
            slotData.map { it.second },
            text.advice, text.tip, clim.notice, clim.warnings,
        )
    }

    private fun dropOldDrafts() {
        val t = now()
        drafts.entries.removeIf { t - it.value.createdAt > DRAFT_TTL_MILLIS }
    }

    /** 診断結果の「このコーデを保存」。コーデ写真 → 上の服 → 下の服 の順に db_server に保存する。 */
    suspend fun save(draftId: String): Saved {
        val s = requireSession()
        val d = drafts[draftId]
        if (d == null || d.nick != s.nickname || now() - d.createdAt > DRAFT_TTL_MILLIS) {
            drafts.remove(draftId)
            throw BackendException(ErrorKind.NOT_FOUND, "保存できる下書きがありません（30分たつと消えます）。もう一度写真を送ってください")
        }
        val verdict = if (d.result.verdict in setOf("ok", "ok_remove")) "good" else "bad"
        val analysis = buildJsonObject {
            put("level", d.result.verdict); put("score", d.result.score)
            put("need_clo", d.result.needClo); put("outfit_clo", d.result.outfitClo)
            put("indoor_temp", d.clim.temp); put("classroom", d.classroom)
            d.outdoor?.let { o -> putJsonObject("outdoor") { put("temp", o.temp); put("level", o.verdict) } }
            putJsonArray("items") {
                d.detected.forEach { i -> add(buildJsonObject { put("type", i.type); put("color", i.color); put("clo", Comfort.itemClo(i.type, i.thickness)) }) }
            }
            put("tip", d.tip)
        }
        val outfitId = remote { db.postOutfit(s.token, d.outfitJpeg, verdict, d.advice, d.classroom.ifBlank { null }, analysis) }
        val saved = mutableListOf<GarmentView>()
        for ((p, jpeg) in d.slots) {
            if (jpeg == null) continue   // 切り抜けなかった服は、画像が無いので保存しない
            val meta = buildJsonObject {
                put("type", p.item.type); put("color", p.item.color); put("thickness", p.item.thickness); put("season", p.item.season)
                put("slot", p.slot); put("clo", p.clo); put("detail", p.detail); put("whole_body", p.wholeBody)
            }
            val gid = remote { db.postGarment(s.token, outfitId, PART.getValue(p.slot), jpeg, meta) }
            saved += GarmentView(gid, p.slot, p.item.type, Comfort.typeJa(p.item.type), p.item.color, p.item.thickness, p.clo, p.detail)
        }
        drafts.remove(draftId)
        return Saved(outfitId, saved)
    }

    // ======================================================================
    // 提案（提案タブ・コーデAI「今日の服装を提案して」）
    // ======================================================================
    private fun titles(gs: List<Garment>): Pair<String, String> {
        val sorted = gs.sortedBy { it.slot != "upper" }
        return sorted.joinToString(" + ") { Comfort.typeJa(it.type) } to sorted.joinToString("と、") { "${it.color}の${Comfort.typeJa(it.type)}" }
    }

    suspend fun suggestions(classroom: String): Suggestions {
        val s = requireSession()
        val clim = climate(classroom)
        val need = Comfort.requiredClo(clim.temp, clim.di, clim.humidityIsDummy)
        val general = General.suggest(need, clim.temp, clim.outdoorTemp).map {
            GeneralSuggestion(it.title, it.description, it.imageKey, it.items.map { i -> i.typeJa }, it.bring?.typeJa)
        }
        val garments = myGarments(s)
        if (garments.size < Preference.MIN_GARMENTS) {
            return Suggestions(
                "general", need, clim.temp, clim.outdoorTemp, emptyList(), general, Comfort.generalTip(need), "", 0,
                "服のデータがまだ少ないので、一般的な目安をお伝えします。あと${Preference.MIN_GARMENTS - garments.size}着分、" +
                    "コーデ写真を送ると、保存した服から提案できます。",
                clim.notice,
            )
        }
        val yesterday = today().minusDays(1).toString()
        val excluded = garments.filter { it.takenDate == yesterday }.map(Suggest::signature).toSet()   // 昨日着た服は除く
        val pref = Preference.build(garments)
        val combos = Suggest.outfits(garments, need, excluded, pref)
        return Suggestions(
            "personal", need, clim.temp, clim.outdoorTemp,
            combos.map { c ->
                val (title, desc) = titles(c.garments)
                OutfitSuggestion(
                    title, "${desc}の組み合わせです。今日の室温${fmt0(clim.temp)}℃に合う暖かさです。", c.totalClo,
                    c.garments.map(::view),
                    c.garments.firstOrNull { it.slot == "upper" }?.id, c.garments.firstOrNull { it.slot == "lower" }?.id,
                )
            },
            general, Comfort.generalTip(need), pref.text, excluded.size,
            if (combos.isEmpty()) "今日の室温に合う組み合わせが、保存した服からは作れませんでした。" else "",
            clim.notice,
        )
    }

    // ======================================================================
    // おすすめの保存
    // ======================================================================
    /** 提案結果の「このコーデを保存」。 */
    suspend fun saveRecommendation(topGarmentId: Long, bottomGarmentId: Long?, reason: String = "") {
        val s = requireSession()
        remote { db.postRecommendation(s.userId, s.token, topGarmentId, bottomGarmentId, reason) }
    }

    /** 最新のおすすめ。まだ無ければ null。 */
    suspend fun latestRecommendation(): Recommendation? {
        val s = requireSession()
        val r = remote { db.latestRecommendation(s.userId, s.token) } ?: return null
        val byId = myGarments(s).associateBy { it.id }
        val top = r.long("top_garment_id") ?: r.obj("top")?.long("id")
        val bottom = r.long("bottom_garment_id") ?: r.obj("bottom")?.long("id")
        return Recommendation(top?.let(byId::get)?.let(::view), bottom?.let(byId::get)?.let(::view), r.str("reason"), r.str("created_at"))
    }

    // ======================================================================
    // 気温別コーデ・感想
    // ======================================================================
    /**
     * 保存したコーデ（新しい順）。temp を渡すと、その気温（kind = "outdoor": 外 / "indoor": 室温）から ±within℃ だけ。
     */
    suspend fun outfits(temp: Double? = null, kind: String = "outdoor", within: Double = 2.0, limit: Int = 20): List<OutfitLog> {
        if (kind !in setOf("outdoor", "indoor")) throw BackendException(ErrorKind.INPUT, "kind は outdoor か indoor です")
        val s = requireSession()
        val lim = limit.coerceIn(1, 100)
        val byId = myGarments(s).associateBy { it.id }
        val out = mutableListOf<OutfitLog>()
        for (r in remote { db.outfits(s.userId, s.token, if (temp != null) 100 else lim) }.mapNotNull { it.obj() }) {
            val cond = r.obj("conditions")
            val ind = r.obj("indoor") ?: cond?.obj("indoor")
            val outd = r.obj("outdoor") ?: cond?.obj("outdoor")
            val indoorTemp = ind?.num("temperature")
            val outdoorTemp = outd?.num("temperature")
            if (temp != null) {
                val t = if (kind == "outdoor") outdoorTemp else indoorTemp
                if (t == null || kotlin.math.abs(t - temp) > within) continue
            }
            val items = r.arr("garments").mapNotNull { g -> (g.obj()?.long("id") ?: (g as? JsonPrimitive)?.content?.toLongOrNull())?.let(byId::get) }
            val verdict = r.str("verdict")
            val fb = r.str("user_feedback")
            out += OutfitLog(
                outfitId = r.long("id") ?: continue,
                title = if (items.isEmpty()) "" else titles(items).first,
                date = dateOf(r.str("taken_at") ?: r.str("created_at")),
                classroom = ind?.str("room_name") ?: r.str("classroom"),
                indoorTemp = indoorTemp, outdoorTemp = outdoorTemp,
                verdict = verdict, verdictJa = VERDICT_JA[verdict].orEmpty(),
                userFeedback = fb?.let { FEEDBACK_JA[it] ?: it },
                advice = r.str("comment"),
                upper = items.firstOrNull { it.slot == "upper" }?.let(::view),
                lower = items.firstOrNull { it.slot == "lower" }?.let(::view),
            )
        }
        return out.take(lim)
    }

    /** 着た感想（暑かった / 寒かった / ちょうどいい）。 */
    suspend fun feedback(outfitId: Long, feedback: String) {
        if (feedback !in FEEDBACKS) throw BackendException(ErrorKind.INPUT, "感想は ${FEEDBACKS.joinToString(" / ")} のどれかです")
        val s = requireSession()
        remote { db.patchFeedback(s.token, outfitId, feedback) }
    }

    // ======================================================================
    /** db_server のエラーを、画面向けのエラーに直す。 */
    private suspend fun <T> remote(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: DbServerException) {
        val kind = when (e.status) {
            401, 403 -> ErrorKind.NOT_LOGGED_IN
            404 -> ErrorKind.NOT_FOUND
            422 -> ErrorKind.INPUT
            else -> ErrorKind.SERVER
        }
        throw BackendException(kind, if (e.status == 0) e.message.orEmpty() else "DBサーバー: ${e.message}", e)
    }
}
