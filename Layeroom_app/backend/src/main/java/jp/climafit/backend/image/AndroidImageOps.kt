package jp.climafit.backend.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Android の Bitmap で画像を扱う。 */
class AndroidImageOps : ImageOps {

    override fun open(bytes: ByteArray): Picture {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("画像を読み込めませんでした")
        return BitmapPicture(rotateByExif(bmp, bytes))
    }

    override fun stack(parts: List<Picture>, width: Int, pad: Int, background: Triple<Int, Int, Int>): Picture {
        val bitmaps = parts.map { (it as BitmapPicture).bitmap }
        val height = pad + bitmaps.sumOf { it.height + pad }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(background.first, background.second, background.third))
        var y = pad
        for (b in bitmaps) {
            canvas.drawBitmap(b, ((width - b.width) / 2).toFloat(), y.toFloat(), null)
            y += b.height + pad
        }
        return BitmapPicture(out)
    }

    /** スマホ写真の向きを直す（PIL の ImageOps.exif_transpose に相当）。 */
    private fun rotateByExif(bmp: Bitmap, bytes: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}

private class BitmapPicture(val bitmap: Bitmap) : Picture {
    override val width get() = bitmap.width
    override val height get() = bitmap.height

    override fun crop(x0: Int, y0: Int, x1: Int, y1: Int): Picture {
        val l = x0.coerceIn(0, width - 1); val t = y0.coerceIn(0, height - 1)
        val r = x1.coerceIn(l + 1, width); val b = y1.coerceIn(t + 1, height)
        return BitmapPicture(Bitmap.createBitmap(bitmap, l, t, r - l, b - t))
    }

    override fun thumbnail(maxW: Int, maxH: Int): Picture {
        if (width <= maxW && height <= maxH) return this
        val scale = minOf(maxW.toDouble() / width, maxH.toDouble() / height)
        val w = maxOf(1, (width * scale).toInt()); val h = maxOf(1, (height * scale).toInt())
        return BitmapPicture(Bitmap.createScaledBitmap(bitmap, w, h, true))
    }

    override fun jpeg(quality: Int): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}
