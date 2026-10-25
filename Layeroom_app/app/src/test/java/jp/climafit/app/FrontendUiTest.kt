package jp.climafit.app

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class FrontendUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun launcherLoadsAndRendersCustomIconInsteadOfAndroidDefault() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.packageManager
        val info = manager.getActivityInfo(ComponentName(app, MainActivity::class.java), 0)
        assertEquals("LayeRoom", info.loadLabel(manager).toString())
        assertEquals("LayeRoom", app.getString(R.string.app_name))
        val icon = info.loadIcon(manager)
        assertTrue(icon is AdaptiveIconDrawable)
        fun render(drawable: Drawable): Bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, 192, 192); drawable.draw(Canvas(it))
        }
        val rendered = render(icon)
        assertFalse(rendered.sameAs(render(manager.defaultActivityIcon)))
        val output = File("build/reports/icon-render.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun materialSymbolsRenderOfflineWithoutClippedCoordinates() {
        val app = RuntimeEnvironment.getApplication()
        val symbols = listOf(R.drawable.ic_home, R.drawable.ic_nav_outfits, R.drawable.ic_nav_people,
            R.drawable.ic_camera, R.drawable.ic_nav_sun, R.drawable.ic_pin, R.drawable.ic_settings,
            R.drawable.ic_back, R.drawable.ic_add, R.drawable.ic_expand_more, R.drawable.ic_arrow_forward,
            R.drawable.ic_person, R.drawable.ic_save, R.drawable.ic_filter, R.drawable.ic_sun,
            R.drawable.ic_check, R.drawable.ic_close, R.drawable.ic_nav_closet, R.drawable.ic_delete)
        val sheet = Bitmap.createBitmap(600, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(android.graphics.Color.rgb(250, 247, 242))
        symbols.forEachIndexed { index, resource ->
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            val drawable = app.getDrawable(resource)!!
            drawable.setBounds(12, 12, 84, 84)
            drawable.draw(Canvas(bitmap))
            var visible = 0
            for (y in 0 until 96) for (x in 0 until 96) {
                if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 0) visible++
            }
            assertTrue("Blank or clipped symbol: $resource", visible > 100)
            canvas.drawBitmap(bitmap, (index % 6 * 100 + 2).toFloat(), (index / 6 * 120).toFloat(), null)
            val label = app.resources.getResourceEntryName(resource).removePrefix("ic_")
            canvas.drawText(label, (index % 6 * 100 + 5).toFloat(), (index / 6 * 120 + 110).toFloat(),
                android.graphics.Paint().apply { color = android.graphics.Color.DKGRAY; textSize = 11f; isAntiAlias = true })
        }
        val output = File("build/reports/material-symbols.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun closetListsUpperAndLowerClothesAndDeletesAfterConfirming() {
        val model = showApp()
        compose.onNodeWithTag("tab-CLOSET").performClick()
        compose.onNodeWithTag("closet-screen").assertExists()
        compose.onNodeWithText("上の服").assertExists()
        compose.onNodeWithText("下の服").assertExists()
        compose.onNodeWithText("白のTシャツ").assertExists()
        compose.onNodeWithText("紺のズボン").assertExists()
        // やめれば消えない
        compose.onNodeWithTag("closet-delete-preview-1").performClick()
        compose.onNodeWithTag("closet-delete-dialog").assertExists()
        compose.onNodeWithText("やめる").performClick()
        compose.onNodeWithTag("closet-delete-dialog").assertDoesNotExist()
        compose.onNodeWithText("白のTシャツ").assertExists()
        // 削除するを押すと消える
        compose.onNodeWithTag("closet-delete-preview-1").performClick()
        compose.onNodeWithTag("closet-delete-confirm").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("closet-item-preview-1").assertDoesNotExist()
        compose.onNodeWithText("ベージュのカーディガン").assertExists()
        compose.runOnIdle { assertEquals(listOf("preview-2", "preview-3"), model.closet.map { it.id }) }
    }

    private var pickerLaunches = 0
    private var cameraPermissionRequests = 0
    private fun showApp(photoResult: Uri? = null): LayeRoomViewModel {
        val model = LayeRoomViewModel(RuntimeEnvironment.getApplication())
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                if (contract is ActivityResultContracts.RequestPermission) {
                    // テストではカメラの許可を断ったことにする（写真フォルダからの選択で進める）
                    cameraPermissionRequests++
                    dispatchResult(requestCode, false)
                    return
                }
                check(contract is ActivityResultContracts.PickVisualMedia)
                pickerLaunches++
                dispatchResult(requestCode, photoResult)
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                LayeRoomTheme { LayeRoomApp(model) }
            }
        }
        return model
    }
    private fun selectLocation(name: String) {
        compose.onNodeWithTag("location-sheet").performScrollToNode(hasTestTag("location-$name"))
        compose.onNodeWithTag("location-$name").performClick()
        compose.waitForIdle()
    }
    private fun revealHomeAction(tag: String) {
        compose.onNodeWithTag("home-screen").performScrollToNode(hasTestTag(tag))
    }
    /** ホームの写真でコーデ診断 → カメラ → 左下の写真フォルダから選ぶ。 */
    private fun launchPhotoSelection() {
        revealHomeAction("home-photo")
        compose.onNodeWithTag("home-photo").performScrollTo().performClick()
        compose.onNodeWithTag("camera-screen").assertExists()
        compose.onNodeWithTag("camera-gallery").performClick()
        compose.runOnIdle { assertEquals("Native photo picker must have opened once", 1, pickerLaunches) }
    }

    @Test fun photoButtonOpensCameraWithShutterGalleryAndSwitch() {
        showApp()
        revealHomeAction("home-photo")
        compose.onNodeWithTag("home-photo").performScrollTo().performClick()
        compose.onNodeWithTag("camera-screen").assertExists()
        compose.onNodeWithTag("tab-HOME").assertDoesNotExist()             // カメラは全画面
        compose.runOnIdle { assertEquals(1, cameraPermissionRequests); assertEquals(0, pickerLaunches) }
        // 下の並び：左に写真フォルダ、真ん中にシャッター、右にカメラの切り替え
        val gallery = compose.onNodeWithTag("camera-gallery").fetchSemanticsNode().boundsInRoot
        val shutter = compose.onNodeWithTag("camera-shutter").fetchSemanticsNode().boundsInRoot
        val switch = compose.onNodeWithTag("camera-switch").fetchSemanticsNode().boundsInRoot
        assertTrue(gallery.center.x < shutter.center.x && shutter.center.x < switch.center.x)
        assertTrue(shutter.width > gallery.width)
        // 許可が無いと、撮影と切り替えはできず、写真フォルダは選べる
        compose.onNodeWithTag("camera-message").assertExists()
        compose.onNodeWithTag("camera-shutter").assertIsNotEnabled()
        compose.onNodeWithTag("camera-switch").assertIsNotEnabled()
        compose.onNodeWithTag("camera-gallery").assertIsEnabled()
        compose.onNodeWithTag("camera-close").performClick()
        compose.onNodeWithTag("home-screen").assertExists()
    }
    private fun photoUri(valid: Boolean = true): Uri {
        val app = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://layeroom.test.photos/outfit.png")
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "image/png"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(app, ProviderInfo().apply { authority = "layeroom.test.photos" })
        ShadowContentResolver.registerProviderInternal("layeroom.test.photos", provider)
        val bytes = if (valid) ByteArrayOutputStream().also {
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray() else byteArrayOf(0, 1, 2)
        shadowOf(app.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        return uri
    }

    @Test fun startsOnHomeWithFourTabsInRequestedOrderAndSideBySideActions() {
        showApp()
        compose.onNodeWithTag("tab-HOME").assertIsSelected()
        // 左から ホーム・気温別コーデ・今日のひろば・クローゼット
        val xs = listOf("HOME", "OUTFITS", "COMMUNITY", "CLOSET")
            .map { compose.onNodeWithTag("tab-$it").fetchSemanticsNode().boundsInRoot.center.x }
        assertEquals(xs.sorted(), xs)
        compose.onNodeWithText("クローゼット").assertExists()
        compose.onNodeWithTag("home-recommendation").assertExists()
        compose.onNodeWithText("今日の服装の目安").assertExists()
        compose.onNodeWithText("Tシャツ").assertExists()
        compose.onNodeWithText("薄手のズボン").assertExists()
        compose.onNodeWithText("薄手のカーディガン").assertExists()
        revealHomeAction("home-photo")
        val photo = compose.onNodeWithTag("home-photo").fetchSemanticsNode().boundsInRoot
        val suggestion = compose.onNodeWithTag("home-suggestion").fetchSemanticsNode().boundsInRoot
        assertTrue(photo.center.x < suggestion.center.x)
        assertEquals(photo.top, suggestion.top, 1f)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test fun tappingPlaceOnlyOpensSheetAndBodyRequestsSelectedPlaceInOneTap() {
        val model = showApp()
        revealHomeAction("home-room-picker")
        compose.onNodeWithTag("home-room-picker").performTouchInput { click() }
        compose.runOnIdle { assertNull(model.latestAiRequest) }
        selectLocation("201教室")
        compose.onNodeWithTag("home-screen").assertExists()
        compose.onNodeWithText(PreviewData.kanazawaClothingGuide.note).assertExists()
        compose.runOnIdle { assertNull(model.latestAiRequest); assertEquals("201教室", model.roomName) }
        revealHomeAction("home-suggestion")
        compose.onNodeWithTag("home-suggestion").performTouchInput {
            click(Offset(center.x, height.toFloat() - 12f))
        }
        compose.onNodeWithTag("suggestion-screen").assertExists()
        compose.runOnIdle {
            assertEquals("今日の服装を提案して", model.latestAiRequest?.prompt)
            assertEquals("201教室", model.latestAiRequest?.location)
            assertEquals(24.2, model.latestAiRequest!!.indoorTemperature, 0.0)
            assertNull(model.latestAiRequest?.photo)
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test fun selectingPhotoAutomaticallyNavigatesAndPreparesDiagnosisWithoutSubmitButton() {
        val uri = photoUri()
        val model = showApp(uri)
        launchPhotoSelection()
        compose.onNodeWithTag("photo-diagnosis-screen").assertExists()
        compose.waitUntil(10000) {
            shadowOf(Looper.getMainLooper()).idle()
            model.latestAiRequest?.mode == AiMode.PHOTO || model.photoError != null
        }
        compose.onNodeWithTag("photo-diagnosis-screen").assertExists()
        compose.runOnIdle {
            assertNull(model.photoError)
            assertEquals(AiMode.PHOTO, model.latestAiRequest?.mode)
            assertSame(model.photo, model.latestAiRequest?.photo)
            assertNotNull(model.latestAiRequest?.photo)
            assertNull(model.latestAiRequest?.prompt)
        }
        compose.onNodeWithText("この写真で診断する").assertDoesNotExist()
        compose.onNodeWithText("コーデを確認しています").assertExists()
    }

    @Test fun pickerCancellationReturnsToCameraWithoutSubmitting() {
        val model = showApp(null)
        launchPhotoSelection()
        compose.onNodeWithTag("camera-screen").assertExists()            // キャンセルしたらカメラに戻る
        compose.onNodeWithTag("photo-diagnosis-screen").assertDoesNotExist()
        compose.runOnIdle { assertNull(model.latestAiRequest) }
        compose.onNodeWithTag("camera-close").performClick()
        compose.onNodeWithTag("home-screen").assertExists()
    }

    @Test fun unreadablePhotoShowsRetryWithoutPreparingDiagnosisRequest() {
        val model = showApp(photoUri(valid = false))
        launchPhotoSelection()
        compose.onNodeWithTag("photo-diagnosis-screen").assertExists()
        compose.waitUntil(10000) {
            shadowOf(Looper.getMainLooper()).idle()
            model.photoError != null
        }
        compose.onNodeWithTag("photo-diagnosis-screen").assertExists()
        compose.onNodeWithText("写真を読み込めませんでした").assertExists()
        compose.runOnIdle { assertNull(model.latestAiRequest); assertNull(model.photo) }
    }

    @Test fun communityTabAlwaysOpensPlaceSheetAndFiltersOnlySelectedPlace() {
        val model = showApp()
        compose.onNodeWithTag("tab-COMMUNITY").performClick()
        compose.onNodeWithTag("location-sheet").assertExists()
        selectLocation("図書館")
        compose.onNodeWithText("図書館の口コミ · 2件").assertExists()
        compose.onNodeWithTag("review-preview-2").assertDoesNotExist()
        compose.onNodeWithTag("tab-COMMUNITY").performClick()
        compose.onNodeWithTag("location-sheet").assertExists()
        selectLocation("実験室3")
        compose.onNodeWithText("実験室3の口コミ · 1件").assertExists()
        compose.onNodeWithTag("review-preview-1").assertDoesNotExist()
        compose.onNodeWithTag("review-preview-4").assertExists()
        compose.runOnIdle { assertEquals("図書館", model.roomName) }
    }

    @Test fun archiveRoomAndCustomTemperatureFiltersRemainAvailable() {
        showApp()
        compose.onNodeWithTag("tab-OUTFITS").performClick()
        compose.onNodeWithText("今の室温").performClick()
        compose.onNodeWithText("図書館", useUnmergedTree = true).performClick()
        compose.onNodeWithText("201教室").performClick()
        compose.onNodeWithText("201教室の室温 24.2℃ ±2℃").assertExists()
        compose.onNodeWithText("温度を指定").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("17")
        compose.onNodeWithText("指定の気温 17.0℃ ±2℃").assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("肌寒い日のライトジャケット"))
        compose.onNodeWithText("肌寒い日のライトジャケット").assertExists()
    }

    @Test fun communityFormStillAcceptsJapaneseAndFullWidthCharacters() {
        showApp()
        compose.onNodeWithTag("tab-COMMUNITY").performClick()
        selectLocation("図書館")
        compose.onNodeWithText("いまの体感を投稿する").performClick()
        val japanese = "図書館は少し寒いです。全角ＡＢＣ１２３"
        compose.onNode(hasSetTextAction()).performClick().performTextInput(japanese)
        compose.onNode(hasSetTextAction()).assertTextContains(japanese)
    }
    @Test fun registrationValidatesInputAndDiscardsItAfterClosing() {
        showApp()
        compose.onNodeWithTag("open-registration").performClick()
        compose.onNodeWithTag("registration-submit").assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithTag("registration-name").performTextInput("   ")
        compose.onNodeWithTag("registration-submit").assertIsNotEnabled()
        compose.onNodeWithTag("registration-name").performTextReplacement("金沢 太郎")
        compose.onNodeWithTag("registration-submit").assertIsEnabled().performScrollTo().performClick()
        compose.onNodeWithTag("registration-sheet").assertDoesNotExist()
        compose.onNodeWithTag("open-registration").performClick()
        compose.onNodeWithText("金沢 太郎").assertDoesNotExist()
        compose.onNodeWithTag("registration-submit").assertIsNotEnabled()
    }

}
