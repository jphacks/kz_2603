package jp.climafit.app

/** 場所。バックエンドに接続していないときはサンプル（PreviewData）。 */
data class RoomUi(
    val name: String,
    val temperature: Double,
    val humidity: Int,
    val discomfort: String,
    val recommendationTitle: String,
    val recommendationDescription: String,
    val layers: List<String>,
    val photo: Int,
    /** db_server の場所ID（ひろばの投稿に使う。サンプルは null） */
    val id: Long? = null,
)
data class OutfitLogUi(
    val id: String,
    val date: String,
    val location: String,
    /** 保存したときの室温・金沢の気温（分からなければ null） */
    val indoorTemperature: Double?,
    val outdoorTemperature: Double?,
    val title: String,
    val comment: String,
    val photo: Int,
    /** 保存した上下の服を1枚にした画像（バックエンド接続時）。null なら photo を表示する */
    val image: android.graphics.Bitmap? = null,
)
data class ReviewUi(val id: String, val location: String, val feel: String, val comment: String, val date: String)
enum class TemperatureMode(val label: String) {
    ALL("すべて"), OUTDOOR("今の金沢"), CURRENT("今の室温"), CUSTOM("温度を指定")
}
enum class AiMode(val title: String) {
    PHOTO("写真でコーデ診断"), SUGGEST("AIからのコーデ提案")
}
/** Frontend action payload only; this does not define a server API or send anything. */
data class AiRequestUi(
    val mode: AiMode,
    val prompt: String? = null,
    val photo: android.graphics.Bitmap? = null,
    val location: String,
    val indoorTemperature: Double,
    val outdoorTemperature: Double,
)
const val TODAY_OUTFIT_PROMPT = "今日の服装を提案して"
/** Fixed copy for the clothing guide preview; no temperature-based recommendation logic. */
data class ClothingGuideUi(val top: String, val bottom: String, val layer: String, val note: String)
object PreviewData {
    // City weather preview, independent of the room selected for AI suggestions.
    val kanazawaClothingGuide = ClothingGuideUi(
        "Tシャツ", "薄手のズボン", "薄手のカーディガン",
        "金沢では、朝晩の冷えに備えて羽織りを一枚。脱ぎ着しやすい服装で出かけましょう。",
    )
    const val outdoorTemperature = 18.5
    val rooms = listOf(
        RoomUi("図書館", 21.0, 48, "66.4 · 快適", "羽織りを一枚、\n心地よく。", "長袖シャツに、軽いカーディガンを。屋外と室内の行き来も、脱ぎ着で調整できます。", listOf("カーディガン", "コットンシャツ", "ストレートパンツ"), R.drawable.outfit_warm),
        RoomUi("201教室", 24.2, 62, "71.9 · 快適", "軽やかに、\n教室も快適。", "シャツを主役に、羽織りものを一枚。暖かい教室では脱いで、すっきり過ごしましょう。", listOf("薄手のカーディガン", "長袖シャツ", "パンツ"), R.drawable.outfit_warm),
        RoomUi("学生食堂", 26.5, 70, "76.0 · やや暑い", "さらっと、\n涼しい一着。", "通気性のよいトップスを中心に。移動中に羽織れる一枚があると安心です。", listOf("薄手のトップス", "軽いパンツ"), R.drawable.outfit_warm),
        RoomUi("実験室3", 19.5, 42, "64.2 · 肌寒い", "冷える部屋には、\nやさしい一枚を。", "長袖にライトジャケットを重ねて。冷気が気になる場所でも調節しやすい組み合わせです。", listOf("ライトジャケット", "白いトップス", "ゆったりパンツ"), R.drawable.outfit_cool),
    )
    val logs = listOf(
        OutfitLogUi("preview-1", "10月24日", "201教室", 24.2, 18.5, "カーディガンで軽やかに", "脱ぎ着しやすいカーディガンで、気温差に合わせて。", R.drawable.outfit_warm),
        OutfitLogUi("preview-2", "10月23日", "実験室3", 19.5, 17.0, "肌寒い日のライトジャケット", "軽いジャケットを重ねて、ひんやりする日も心地よく。", R.drawable.outfit_cool),
    )
    val reviews = listOf(
        ReviewUi("preview-1", "図書館", "少し寒い", "窓際の席は少しひんやり。薄いカーディガンがあるとちょうどよかったです。", "10分前"),
        ReviewUi("preview-2", "201教室", "快適", "今日は過ごしやすい室温！長袖シャツ一枚で大丈夫でした。", "25分前"),
        ReviewUi("preview-3", "学生食堂", "少し暑い", "お昼は人が多くてぽかぽか。上着は脱いで過ごしています。", "40分前"),
        ReviewUi("preview-4", "実験室3", "かなり寒い", "空調の近くは冷えやすいので、羽織りものがおすすめです。", "1時間前"),
        ReviewUi("preview-5", "図書館", "快適", "奥の席はちょうどいい感じ。落ち着いて作業できました。", "2時間前"),
    )
}
