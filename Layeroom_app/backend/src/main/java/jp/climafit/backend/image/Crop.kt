package jp.climafit.backend.image

import jp.climafit.backend.ai.Box

/** 開いた画像。Android では Bitmap、テストでは BufferedImage が中身。 */
interface Picture {
    val width: Int
    val height: Int

    /** ピクセル座標で切り抜く（x1, y1 は含まない）。 */
    fun crop(x0: Int, y0: Int, x1: Int, y1: Int): Picture

    /** 縦横比を保って maxW × maxH に収まるよう縮小する（大きくはしない）。 */
    fun thumbnail(maxW: Int, maxH: Int): Picture

    fun jpeg(quality: Int): ByteArray
}

/** 画像の読み込みと、上下を1枚に並べる処理（端末ごとの実装）。 */
interface ImageOps {
    /** 読めない画像なら例外。スマホ写真の向き（EXIF）はここで直す。 */
    fun open(bytes: ByteArray): Picture

    /** 縦に並べて、背景色 (r, g, b) の1枚にする。 */
    fun stack(parts: List<Picture>, width: Int, pad: Int, background: Triple<Int, Int, Int>): Picture
}

/**
 * 画像の前処理と、服の切り抜き（AIが返した四角で切る）。元の写真は保存しない。以前の Python 版 crop.py の移植。
 */
class Crop(private val ops: ImageOps) {

    fun open(bytes: ByteArray): Picture = ops.open(bytes)

    /** AIに送る用に縮小してJPEGにする（料金と速度のため）。 */
    fun prepareForModel(img: Picture): ByteArray = img.thumbnail(MAX_MODEL_SIDE, MAX_MODEL_SIDE).jpeg(85)

    /** 服の四角を切り抜いてJPEGにする。小さすぎる四角は null。 */
    fun cropBytes(img: Picture, bbox: Box?): ByteArray? {
        val (x0, y0, x1, y1) = clampBox(bbox) ?: return null
        val w = img.width; val h = img.height
        return img.crop((x0 * w).toInt(), (y0 * h).toInt(), (x1 * w).toInt(), (y1 * h).toInt())
            .thumbnail(MAX_CROP_SIDE, MAX_CROP_SIDE).jpeg(85)
    }

    /** db_server に送るコーデ写真。crop は『服が写っている範囲』だけ（AIは顔を含めない四角を返す）。 */
    fun outfitPhoto(img: Picture, boxes: List<Box?>, full: Boolean = false): ByteArray {
        var im = img
        if (!full) {
            val bs = boxes.mapNotNull(::clampBox)
            val w = img.width; val h = img.height
            im = if (bs.isNotEmpty()) {
                img.crop((bs.minOf { it[0] } * w).toInt(), (bs.minOf { it[1] } * h).toInt(),
                    (bs.maxOf { it[2] } * w).toInt(), (bs.maxOf { it[3] } * h).toInt())
            } else {
                img.crop(0, (h * 0.25).toInt(), w, h)   // 四角が取れないときは、顔がありそうな上の25%を落とす
            }
        }
        return im.thumbnail(MAX_MODEL_SIDE, MAX_MODEL_SIDE).jpeg(85)
    }

    /** 上の服と下の服の切り抜きを、縦に1枚に並べた画像にする（おすすめの表示用。保存はしない）。 */
    fun combine(upper: ByteArray?, lower: ByteArray?, width: Int = 480): ByteArray {
        val parts = listOfNotNull(upper, lower).map { ops.open(it).thumbnail(width - 80, 300) }
        require(parts.isNotEmpty()) { "画像がありません" }
        return ops.stack(parts, width, 24, Triple(247, 243, 238)).jpeg(88)
    }

    companion object {
        const val MAX_MODEL_SIDE = 1024   // AIに送る画像の長辺
        const val MAX_CROP_SIDE = 512     // 保存する切り抜きの長辺
        private const val MIN_BOX = 0.05  // 幅・高さの最小（画像比）
        private const val MIN_AREA = 0.01 // 面積の最小（画像比）
        private const val MARGIN = 0.04

        /** 余白を足して 0〜1 に収めた (x0, y0, x1, y1)。小さすぎる四角は null。 */
        fun clampBox(b: Box?): DoubleArray? {
            if (b == null) return null
            if (b.w < MIN_BOX || b.h < MIN_BOX || b.w * b.h < MIN_AREA) return null
            val x0 = maxOf(0.0, b.x - MARGIN)
            val y0 = maxOf(0.0, b.y - MARGIN)
            val x1 = minOf(1.0, b.x + b.w + MARGIN)
            val y1 = minOf(1.0, b.y + b.h + MARGIN)
            if (x1 <= x0 || y1 <= y0) return null
            return doubleArrayOf(x0, y0, x1, y1)
        }
    }
}
