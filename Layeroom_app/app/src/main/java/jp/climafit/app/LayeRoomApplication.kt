package jp.climafit.app

import android.app.Application
import jp.climafit.backend.LayeRoomBackend

/**
 * アプリ全体で1つのバックエンド（:backend モジュール）。
 * local.properties に DB_SERVER_URL が無ければ null で、画面はサンプルを表示する。
 */
class LayeRoomApplication : Application() {
    val backend: LayeRoomBackend? by lazy {
        BuildConfig.DB_SERVER_URL.takeIf { it.isNotBlank() }?.let { url ->
            LayeRoomBackend.create(
                context = this,
                dbServerUrl = url,
                openAiKey = BuildConfig.OPENAI_API_KEY.ifBlank { null },
                openAiBaseUrl = BuildConfig.OPENAI_BASE_URL,
                openAiImageModel = BuildConfig.OPENAI_IMAGE_MODEL.ifBlank { null },
            )
        }
    }
}
