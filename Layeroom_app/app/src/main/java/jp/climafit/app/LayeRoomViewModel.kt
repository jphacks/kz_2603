package jp.climafit.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import jp.climafit.backend.BackendException
import jp.climafit.backend.ErrorKind
import jp.climafit.backend.LayeRoomBackend
import jp.climafit.backend.Review
import jp.climafit.backend.Room
import jp.climafit.backend.Suggestions
import jp.climafit.backend.ai.Comfort
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 画面の状態。backend があれば db_server・OpenAI のデータを使い、無ければサンプル（PreviewData）を表示する。
 * テストとプレビューは backend 無し（LayeRoomViewModel(application)）で動く。
 */
class LayeRoomViewModel(application: Application, private val backend: LayeRoomBackend?) : AndroidViewModel(application) {
    /** サンプル表示（バックエンド無し）。 */
    constructor(application: Application) : this(application, null)

    /** アプリから作るとき用（バックエンドを渡す）。 */
    class Factory(private val application: Application, private val backend: LayeRoomBackend?) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LayeRoomViewModel(application, backend) as T
    }

    val connected: Boolean get() = backend != null
    var rooms by mutableStateOf(PreviewData.rooms); private set
    var outdoorTemperature by mutableStateOf(PreviewData.outdoorTemperature); private set
    var logs by mutableStateOf(PreviewData.logs); private set
    var reviews by mutableStateOf(PreviewData.reviews); private set
    /** 登録したユーザー名（未登録なら null） */
    var userName by mutableStateOf(backend?.session()?.nickname); private set
    /** サーバーにつないでいて未登録なら、登録するまでほかの機能を使えないようにする（サンプル表示では求めない） */
    val mustRegister: Boolean get() = backend != null && userName == null
    var registering by mutableStateOf(false); private set
    var registrationError by mutableStateOf<String?>(null); private set
    var roomName by mutableStateOf(rooms.first().name); private set
    val room: RoomUi get() = rooms.firstOrNull { it.name == roomName } ?: rooms.first()
    var notice by mutableStateOf<String?>(null); private set
    var photo by mutableStateOf<Bitmap?>(null); private set
    var loadingPhoto by mutableStateOf(false); private set
    var photoError by mutableStateOf<String?>(null); private set
    var latestAiRequest by mutableStateOf<AiRequestUi?>(null); private set

    // ---------- 写真でコーデ診断（バックエンド接続時） ----------
    var diagnosis by mutableStateOf<Review?>(null); private set
    var diagnosisImages by mutableStateOf<List<Bitmap?>>(emptyList()); private set
    var diagnosing by mutableStateOf(false); private set
    var diagnosisError by mutableStateOf<String?>(null); private set
    var savingDiagnosis by mutableStateOf(false); private set
    var diagnosisSaved by mutableStateOf(false); private set

    // ---------- 場所別のおすすめ（バックエンド接続時） ----------
    var suggestion by mutableStateOf<Suggestions?>(null); private set
    var suggestionImage by mutableStateOf<Bitmap?>(null); private set
    /** suggestionImage が AI で作ったマネキン画像になったら true（それまでは上下を並べた画像） */
    var suggestionIsMannequin by mutableStateOf(false); private set
    var renderingMannequin by mutableStateOf(false); private set
    /** マネキン画像を作れなかった理由（作れたとき・作っていないときは null） */
    var mannequinError by mutableStateOf<String?>(null); private set
    var suggesting by mutableStateOf(false); private set
    var suggestionError by mutableStateOf<String?>(null); private set
    var suggestionSaved by mutableStateOf(false); private set

    init {
        if (backend != null) refresh()
    }

    fun requestPhotoPreview() {
        val selectedPhoto = photo ?: return
        if (loadingPhoto) return
        refreshConditions()
        latestAiRequest = AiRequestUi(AiMode.PHOTO, photo = selectedPhoto, location = roomName,
            indoorTemperature = room.temperature, outdoorTemperature = outdoorTemperature)
        diagnose()
    }
    fun requestSuggestionPreview() {
        refreshConditions()
        latestAiRequest = AiRequestUi(AiMode.SUGGEST, prompt = TODAY_OUTFIT_PROMPT, location = roomName,
            indoorTemperature = room.temperature, outdoorTemperature = outdoorTemperature)
        loadSuggestion()
    }
    private var photoJob: Job? = null
    private var diagnosisJob: Job? = null
    private var suggestionJob: Job? = null
    private var photoVersion = 0
    private var photoBytes: ByteArray? = null
    fun message(text: String) { notice = text }
    fun dismissNotice() { notice = null }
    fun selectRoom(name: String) { if (rooms.any { it.name == name }) roomName = name }
    fun clearPhoto() {
        photoVersion++; photoJob?.cancel(); diagnosisJob?.cancel(); photo = null; photoBytes = null; loadingPhoto = false
        photoError = null
        diagnosis = null; diagnosisImages = emptyList(); diagnosing = false; diagnosisError = null
        savingDiagnosis = false; diagnosisSaved = false
        if (latestAiRequest?.mode == AiMode.PHOTO) latestAiRequest = null
    }
    /** 写真フォルダから選んだ写真を読み込む。 */
    fun loadPhoto(uri: Uri) = loadPhotoFrom {
        val resolver = getApplication<Application>().contentResolver
        require(resolver.getType(uri)?.startsWith("image/") == true)
        resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read; require(total <= 10 * 1024 * 1024)
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: error("Unreadable image")
    }

    /** アプリのカメラで撮った写真（JPEG、向きは直したもの）を読み込む。 */
    fun loadCapturedPhoto(jpeg: ByteArray) = loadPhotoFrom {
        require(jpeg.size <= 10 * 1024 * 1024)
        jpeg
    }

    private fun loadPhotoFrom(read: () -> ByteArray) {
        clearPhoto(); loadingPhoto = true
        val version = photoVersion
        photoJob = viewModelScope.launch {
            try {
                val (bitmap, data) = withContext(Dispatchers.IO) {
                    val data = read()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0)
                    val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                    while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1400) options.inSampleSize *= 2
                    (BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: error("Unsupported image")) to data
                }
                if (version == photoVersion) {
                    photo = bitmap
                    photoBytes = data   // 診断には縮小前の写真を送る（向きの情報も残る）
                    loadingPhoto = false
                    requestPhotoPreview()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (version == photoVersion) {
                    val error = "写真を読み込めませんでした。10MB以下のJPEG・PNG等を選んでください。"
                    photoError = error
                    message(error)
                }
            }
            finally { if (version == photoVersion) loadingPhoto = false }
        }
    }

    // ======================================================================
    // バックエンド接続時の処理（backend が null なら何もしない）
    // ======================================================================

    /** 場所・金沢の気温・保存したコーデを読み直す。 */
    fun refresh() {
        val b = backend ?: return
        viewModelScope.launch {
            try {
                loadConditions(b)
            } catch (e: BackendException) {
                message(errorText(e))
            }
            loadLogs()
        }
    }

    private var conditionsJob: Job? = null

    /**
     * 室温・金沢の気温だけを読み直す（アプリを開いている間の定期更新と、AI に聞く直前）。
     * 定期更新なので、つながらなくてもメッセージは出さない（前の値のまま）。
     */
    fun refreshConditions() {
        val b = backend ?: return
        if (conditionsJob?.isActive == true) return
        conditionsJob = viewModelScope.launch {
            try { loadConditions(b) } catch (e: BackendException) { Log.w("LayeRoom", "conditions refresh failed", e) }
        }
    }

    private suspend fun loadConditions(b: LayeRoomBackend) {
        val loaded = b.rooms().map { it.toUi() }
        if (loaded.isNotEmpty()) {
            rooms = loaded
            if (loaded.none { it.name == roomName }) roomName = loaded.first().name
        }
        b.conditions(null).outdoor?.temperature?.let { outdoorTemperature = it }
    }

    /** AI が判定に使った温度を、画面の表示にもそのまま使う（表示と AI の温度がずれないように）。 */
    private fun showUsedTemperatures(mode: AiMode, place: String, indoor: Double, outdoor: Double?) {
        rooms = rooms.map { if (it.name == place) it.copy(temperature = indoor) else it }
        outdoor?.let { outdoorTemperature = it }
        val request = latestAiRequest ?: return
        if (request.mode == mode && request.location == place) {
            latestAiRequest = request.copy(indoorTemperature = indoor, outdoorTemperature = outdoor ?: request.outdoorTemperature)
        }
    }

    /** ユーザー登録（初回起動時の登録シート、または右上の歯車から）。 */
    fun register(name: String) {
        val b = backend ?: return
        if (registering) return
        registering = true; registrationError = null
        viewModelScope.launch {
            try {
                userName = b.start(name).nickname
                message("「${userName}」で登録しました")
                loadLogs()
            } catch (e: BackendException) {
                registrationError = errorText(e)
                message(errorText(e))
            } finally {
                registering = false
            }
        }
    }

    private fun diagnose() {
        val b = backend ?: return
        val bytes = photoBytes ?: return
        val version = photoVersion
        val place = roomName
        diagnosisJob?.cancel()
        diagnosing = true; diagnosisError = null; diagnosis = null; diagnosisSaved = false
        diagnosisJob = viewModelScope.launch {
            try {
                val r = b.review(bytes, place)
                val images = withContext(Dispatchers.Default) { r.itemImages.map { it?.let(::decode) } }
                if (version == photoVersion) {
                    diagnosis = r; diagnosisImages = images
                    showUsedTemperatures(AiMode.PHOTO, place, r.indoorTemp, r.outdoorTemp)
                }
            } catch (e: BackendException) {
                if (version == photoVersion) diagnosisError = errorText(e)
            } finally {
                if (version == photoVersion) diagnosing = false
            }
        }
    }

    /** 診断結果の「このコーデを保存」。 */
    fun saveDiagnosis() {
        val b = backend ?: return
        val r = diagnosis ?: return
        if (savingDiagnosis || diagnosisSaved) return
        savingDiagnosis = true
        viewModelScope.launch {
            try {
                b.save(r.draftId)
                diagnosisSaved = true
                message("コーデを保存しました")
                loadLogs()
            } catch (e: BackendException) {
                message(errorText(e))
            } finally {
                savingDiagnosis = false
            }
        }
    }

    private fun loadSuggestion() {
        val b = backend ?: return
        val place = roomName
        suggestionJob?.cancel()
        suggesting = true; suggestionError = null; suggestion = null; suggestionImage = null; suggestionSaved = false
        suggestionIsMannequin = false; renderingMannequin = false; mannequinError = null
        suggestionJob = viewModelScope.launch {
            try {
                val s = b.suggestions(place)
                suggestion = s
                showUsedTemperatures(AiMode.SUGGEST, place, s.indoorTemp, s.outdoorTemp)
                s.suggestions.firstOrNull()?.let { first ->
                    // まず上下を並べた画像をすぐ出し、マネキン画像ができたら差し替える
                    suggestionImage = runCatching { decode(b.comboImage(first.upperId, first.lowerId)) }.getOrNull()
                    suggesting = false
                    if (!b.canRenderMannequin) {
                        mannequinError = "OpenAIのキーが設定されていないため、マネキン画像は作れません"
                    } else {
                        renderingMannequin = true
                        try {
                            val bitmap = b.mannequinImage(first.upperId, first.lowerId)?.let(::decode)
                            if (bitmap != null) {
                                suggestionImage = bitmap
                                suggestionIsMannequin = true
                            } else {
                                mannequinError = "マネキン画像を読み込めませんでした"
                            }
                        } catch (e: BackendException) {
                            // 作れなければ上下を並べた画像のまま。理由は画面とログに出す
                            Log.w("LayeRoom", "mannequin image failed", e)
                            mannequinError = errorText(e)
                        } finally {
                            renderingMannequin = false
                        }
                    }
                }
            } catch (e: BackendException) {
                suggestionError = errorText(e)
            } finally {
                suggesting = false
            }
        }
    }

    /** 提案結果の「このコーデを保存」（保存した服からの提案のときだけ）。 */
    fun saveSuggestion() {
        val b = backend ?: return
        val first = suggestion?.suggestions?.firstOrNull() ?: return
        val top = first.upperId ?: return
        if (suggestionSaved) return
        viewModelScope.launch {
            try {
                b.saveRecommendation(top, first.lowerId, first.title)
                suggestionSaved = true
                message("コーデを保存しました")
            } catch (e: BackendException) {
                message(errorText(e))
            }
        }
    }

    /** 気温別コーデ（保存したコーデ）を読み直す。未登録なら空。 */
    fun loadLogs() {
        val b = backend ?: return
        if (b.session() == null) { logs = emptyList(); return }
        viewModelScope.launch {
            try {
                val list = b.outfits(limit = 50)
                logs = list.map { o ->
                    OutfitLogUi(
                        id = o.outfitId.toString(), date = formatDate(o.date), location = o.classroom.orEmpty(),
                        indoorTemperature = o.indoorTemp, outdoorTemperature = o.outdoorTemp,
                        title = o.title.ifBlank { "保存したコーデ" }, comment = o.advice.orEmpty(), photo = R.drawable.outfit_warm,
                    )
                }
                // 写真（上下を1枚にした画像）は後から順に読み込む
                for (o in list) {
                    val up = o.upper?.garmentId; val low = o.lower?.garmentId
                    if (up == null && low == null) continue
                    val image = runCatching { decode(b.comboImage(up, low)) }.getOrNull() ?: continue
                    logs = logs.map { if (it.id == o.outfitId.toString()) it.copy(image = image) else it }
                }
            } catch (e: BackendException) {
                message(errorText(e))
            }
        }
    }

    /** 今日のひろば：選んだ場所の投稿を読み込む。 */
    fun loadReviews(location: String) {
        val b = backend ?: return
        val roomId = rooms.firstOrNull { it.name == location }?.id ?: return
        viewModelScope.launch {
            try {
                val posts = b.posts(roomId)
                reviews = reviews.filter { it.location != location } + posts.map { p ->
                    ReviewUi("post-${p.id}", location, p.feelingLabel, p.comment.orEmpty(), relativeTime(p.createdAt))
                }
            } catch (e: BackendException) {
                message(errorText(e))
            }
        }
    }

    /** 今日のひろば：体感を投稿する。 */
    fun postReview(location: String, feel: String, comment: String) {
        val b = backend ?: return
        val roomId = rooms.firstOrNull { it.name == location }?.id ?: return
        viewModelScope.launch {
            try {
                b.post(roomId, feel, comment)
                message("投稿しました")
                loadReviews(location)
            } catch (e: BackendException) {
                message(errorText(e))
            }
        }
    }

    private fun errorText(e: BackendException): String = when (e.kind) {
        ErrorKind.NOT_LOGGED_IN -> "右上の歯車からユーザー登録をしてください。"
        ErrorKind.SERVER -> "サーバーにつながりませんでした。時間をおいて試してください。"
        else -> e.message ?: "うまくいきませんでした。"
    }

    private fun decode(jpeg: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)

    private fun Room.toUi(): RoomUi {
        val t = temperature ?: 0.0
        val di = if (temperature != null && humidity != null) Comfort.discomfortIndex(t, humidity!!) else null
        return RoomUi(name, t, humidity?.roundToInt() ?: 0, di?.let { "$it · ${Comfort.diLabel(it)}" }.orEmpty(),
            "", "", emptyList(), R.drawable.outfit_warm, id)
    }

    private fun formatDate(isoDate: String?): String =
        runCatching { LocalDate.parse(isoDate).let { "${it.monthValue}月${it.dayOfMonth}日" } }.getOrDefault("")

    private fun relativeTime(createdAt: String): String = runCatching {
        val t = OffsetDateTime.parse(createdAt).toInstant()
        val minutes = Duration.between(t, Instant.now()).toMinutes()
        when {
            minutes < 1 -> "たった今"
            minutes < 60 -> "${minutes}分前"
            minutes < 24 * 60 -> "${minutes / 60}時間前"
            else -> t.atZone(ZoneId.of("Asia/Tokyo")).toLocalDate().let { "${it.monthValue}月${it.dayOfMonth}日" }
        }
    }.getOrDefault("")
}
