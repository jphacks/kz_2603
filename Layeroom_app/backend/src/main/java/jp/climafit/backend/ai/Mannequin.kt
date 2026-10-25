package jp.climafit.backend.ai

/** 上下の服の画像から「その服を着たマネキン」の画像を作る。 */
interface MannequinRenderer {
    /** upper / lower は服の画像（どちらかは null でもよい）。desc は「白のTシャツ」などの説明。 */
    suspend fun render(upper: ByteArray?, lower: ByteArray?, upperDesc: String?, lowerDesc: String?): ByteArray
}

/** OpenAI の画像編集で、服の写真からマネキンの着用画像を作る。 */
class OpenAiMannequin(private val openAi: OpenAi) : MannequinRenderer {
    override suspend fun render(upper: ByteArray?, lower: ByteArray?, upperDesc: String?, lowerDesc: String?): ByteArray {
        val images = listOfNotNull(upper?.let { "upper.jpg" to it }, lower?.let { "lower.jpg" to it })
        if (images.isEmpty()) throw OpenAiException("服の画像がありません")
        return openAi.editImage(images, prompt(upper != null, lower != null, upperDesc, lowerDesc))
    }

    companion object {
        /** 画像生成への指示。写真に写っているハンガーや人・小物は使わず、服だけをマネキンに着せる。 */
        fun prompt(hasUpper: Boolean, hasLower: Boolean, upperDesc: String?, lowerDesc: String?): String {
            val garments = buildList {
                var n = 1
                if (hasUpper) add("input image ${n++} is the top garment${upperDesc?.let { " ($it)" }.orEmpty()}")
                if (hasLower) add("input image $n is the bottom garment${lowerDesc?.let { " ($it)" }.orEmpty()}")
            }.joinToString("; ")
            val outfit = if (hasUpper && hasLower) "wearing both garments together as one outfit" else "wearing this garment"
            // 見た目は、アパレル通販の着用画像のような「顔のない黒いマネキン」の全身写真に合わせる
            return "Realistic e-commerce photo of a full-length retail display mannequin, standing straight, front view, " +
                "from head to feet, arms relaxed at the sides. The mannequin has a smooth faceless oval head and a matte black body. " +
                "It is $outfit, worn naturally with realistic drape, fit and layering, not laid flat. $garments. " +
                "Reproduce each garment's color, pattern, material and shape faithfully. " +
                "Use only the garments: ignore hangers, people, hands, props, other clothes and backgrounds in the input photos. " +
                "Plain simple shoes are fine. Plain white studio background, soft even lighting, the mannequin centered, " +
                "no text, no logos, no real person."
        }
    }
}
