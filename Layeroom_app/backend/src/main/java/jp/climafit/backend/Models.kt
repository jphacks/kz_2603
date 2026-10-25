package jp.climafit.backend

/** 画面に返すデータの形。どれも LayeRoomBackend が返す。 */

/** 利用者（ニックネームと、db_server での利用者ID・トークン）。 */
data class Session(val nickname: String, val userId: Long, val token: String)

/** エラーの種類。画面ではこれを見て、表示する文や「ログインし直す」などを決める。 */
enum class ErrorKind {
    /** 入力が正しくない（文字数など） */
    INPUT,
    /** ログインしていない・トークンが無効 */
    NOT_LOGGED_IN,
    /** 見つからない（下書きの期限切れ、無い場所など） */
    NOT_FOUND,
    /** 写真に服が見つからない */
    NO_CLOTHES,
    /** OpenAI との通信に失敗 */
    AI,
    /** db_server に接続できない・エラー */
    SERVER,
}

class BackendException(val kind: ErrorKind, message: String, cause: Throwable? = null) : Exception(message, cause)

// ---------- 場所・ひろば ----------
data class FeelingCounts(val comfortable: Int, val slightlyCold: Int, val veryCold: Int, val slightlyHot: Int, val total: Int)

data class Room(
    val id: Long,
    val name: String,
    val temperature: Double?,
    val humidity: Double?,
    /** "sensor"（センサーの実測）/ "dummy"（仮の値） */
    val temperatureSource: String,
    val humiditySource: String,
    val isDummy: Boolean,
    val measuredAt: String?,
    val stale: Boolean,
    /** 今日（日本時間0時から）の体感ごとの投稿数 */
    val today: FeelingCounts,
)

data class Feeling(val code: String, val label: String)

data class Post(val id: Long, val roomId: Long, val feeling: String, val feelingLabel: String, val comment: String?, val createdAt: String)

// ---------- 提案タブの気温 ----------
data class Indoor(
    val roomName: String?,
    val temperature: Double,
    val humidity: Double?,
    val discomfortIndex: Double?,
    /** "sensor" / "dummy" */
    val temperatureSource: String,
    /** "sensor" / "api"（外の湿度で代用）/ "dummy" */
    val humiditySource: String,
    val measuredAt: String?,
    val ageMinutes: Double?,
    val stale: Boolean,
)

data class Outdoor(
    val location: String,
    val temperature: Double?,
    val humidity: Double?,
    val weatherCode: Int?,
    val precipitation: Double?,
    val discomfortIndex: Double?,
    val fetchedAt: String?,
    val ageMinutes: Double?,
)

data class Conditions(
    val classroom: String?,
    val indoor: Indoor?,
    val outdoor: Outdoor?,
    val forecastMax: Double?,
    val forecastMin: Double?,
    /** 室内 − 屋外 */
    val temperatureDifference: Double?,
    /** 画面に出す注意（仮の値・センサーが古い など） */
    val notices: List<String>,
)

// ---------- 服・コーデ ----------
data class GarmentView(
    val garmentId: Long?,
    /** "upper" / "lower" */
    val slot: String,
    val type: String,
    val typeJa: String,
    val color: String,
    val thickness: String,
    val clo: Double,
    val detail: String,
)

/** 写真診断の結果（まだ保存していない。保存は save(draftId)）。 */
data class Review(
    val draftId: String,
    val mode: String,
    val verdict: String,
    val verdictJa: String,
    val score: Int,
    val needClo: Double,
    val outfitClo: Double,
    val removable: List<String>,
    val indoorTemp: Double,
    val humidity: Double,
    val humidityIsDummy: Boolean,
    val discomfortIndex: Double?,
    val outdoorTemp: Double?,
    val outdoor: jp.climafit.backend.ai.OutdoorInfo?,
    /** 写っていた服（判定に使ったもの全部） */
    val detected: List<GarmentView>,
    /** 保存する上・下の服 */
    val items: List<GarmentView>,
    /** items と同じ順の切り抜き画像（JPEG。切り抜けなかった服は null） */
    val itemImages: List<ByteArray?>,
    val advice: String,
    val tip: String,
    /** 湿度などの注意（無ければ空文字） */
    val notice: String,
    val warnings: List<String>,
)

data class Saved(val outfitId: Long, val items: List<GarmentView>)

data class GeneralSuggestion(
    val title: String,
    val description: String,
    val imageKey: String,
    val items: List<String>,
    /** 外で足す1枚（例: コート） */
    val bring: String?,
)

data class OutfitSuggestion(
    val title: String,
    val description: String,
    val totalClo: Double,
    val garments: List<GarmentView>,
    /** 上下を1枚にした画像は comboImage(upperId, lowerId) で取る */
    val upperId: Long?,
    val lowerId: Long?,
)

data class Suggestions(
    /** "personal"（保存した服から）/ "general"（服が少ないので気温だけ） */
    val mode: String,
    val needClo: Double,
    val indoorTemp: Double,
    val outdoorTemp: Double?,
    val suggestions: List<OutfitSuggestion>,
    val general: List<GeneralSuggestion>,
    val tip: String,
    val preference: String,
    val excludedYesterday: Int,
    val message: String,
    val notice: String,
)

data class OutfitLog(
    val outfitId: Long,
    val title: String,
    val date: String?,
    val classroom: String?,
    val indoorTemp: Double?,
    val outdoorTemp: Double?,
    val verdict: String?,
    val verdictJa: String,
    /** 暑かった / 寒かった / ちょうどいい */
    val userFeedback: String?,
    val advice: String?,
    val upper: GarmentView?,
    val lower: GarmentView?,
)

data class Garments(val garments: List<GarmentView>, val preference: String)

data class Recommendation(val top: GarmentView?, val bottom: GarmentView?, val reason: String?, val createdAt: String?)
