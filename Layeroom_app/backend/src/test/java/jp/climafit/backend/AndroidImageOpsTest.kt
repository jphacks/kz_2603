package jp.climafit.backend

import jp.climafit.backend.ai.Box
import jp.climafit.backend.image.AndroidImageOps
import jp.climafit.backend.image.Crop
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Android の Bitmap を使う画像処理（Robolectric の本物の画像処理で動かす）。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AndroidImageOpsTest {
    private val crop = Crop(AndroidImageOps())

    private fun size(jpeg: ByteArray) = TestImages.size(jpeg)

    @Test
    fun `写真を開いて、AIに送る大きさに縮める`() {
        val img = crop.open(TestImages.photo(2000, 3000))
        assertEquals(2000 to 3000, img.width to img.height)
        val sent = crop.prepareForModel(img)
        assertEquals(682 to 1024, size(sent))   // 長辺 1024 に縮小（縦横比はそのまま）
    }

    @Test
    fun `服の四角で切り抜く・小さすぎる四角は切らない`() {
        val img = crop.open(TestImages.photo(600, 800))
        val piece = crop.cropBytes(img, Box(0.2, 0.2, 0.6, 0.3))!!
        // 余白 0.04 を足して (0.16..0.84, 0.16..0.54) → 408 x 304
        assertEquals(408 to 304, size(piece))
        assertNull(crop.cropBytes(img, Box(0.1, 0.1, 0.01, 0.4)))
    }

    @Test
    fun `コーデ写真は服の範囲だけ（四角が無ければ上25%を落とす）`() {
        val img = crop.open(TestImages.photo(600, 800))
        // 2つの四角（余白込み）を合わせた範囲 x 0.16..0.84, y 0.16..0.99 → 408 x 664
        assertEquals(408 to 664, size(crop.outfitPhoto(img, listOf(Box(0.2, 0.2, 0.6, 0.3), Box(0.2, 0.55, 0.5, 0.4)))))
        assertEquals(600 to 600, size(crop.outfitPhoto(img, emptyList())))
    }

    @Test
    fun `上下を縦に1枚にする`() {
        val out = crop.combine(TestImages.photo(200, 300), TestImages.photo(200, 100))
        assertEquals(480 to (24 + 300 + 24 + 100 + 24), size(out))
        assertTrue(out[0] == 0xFF.toByte())
    }

    @Test
    fun `画像でないものは読めない`() {
        assertFailsWith<IllegalArgumentException> { crop.open("hello".toByteArray()) }
    }
}
