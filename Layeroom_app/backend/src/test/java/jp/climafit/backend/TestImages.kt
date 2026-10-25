package jp.climafit.backend

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/** テスト用の写真（Robolectric の本物の画像処理で作る）。 */
object TestImages {
    /** 単色の写真（JPEG）。 */
    fun photo(w: Int = 600, h: Int = 800, rgb: Int = 0x999999): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF000000.toInt() or rgb) }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** JPEG の幅と高さ。 */
    fun size(jpeg: ByteArray): Pair<Int, Int> =
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size).let { it.width to it.height }
}
