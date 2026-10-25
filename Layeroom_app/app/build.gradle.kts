import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// db_server の場所と OpenAI のキーは local.properties（コミットしない）から読む。無ければサンプル表示で動く
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun local(key: String) = (localProps.getProperty(key) ?: System.getenv(key) ?: "").replace("\"", "\\\"")

android {
    namespace = "jp.climafit.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "jp.climafit.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "1.19"

        buildConfigField("String", "DB_SERVER_URL", "\"${local("DB_SERVER_URL")}\"")
        buildConfigField("String", "OPENAI_API_KEY", "\"${local("OPENAI_API_KEY")}\"")
        // キーを端末に置かず、中継サーバー経由にするときはここを変える（例: http://<ubuntu>:<port>/v1）
        buildConfigField("String", "OPENAI_BASE_URL", "\"${local("OPENAI_BASE_URL").ifEmpty { "https://api.openai.com/v1" }}\"")
        // マネキン画像に使う画像モデル（空なら既定の候補を順に試す）
        buildConfigField("String", "OPENAI_IMAGE_MODEL", "\"${local("OPENAI_IMAGE_MODEL")}\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":backend"))
    implementation(platform("androidx.compose:compose-bom:2025.03.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // バックエンド接続時の画面テスト（db_server を偽物にする）
    testImplementation("io.ktor:ktor-client-mock:3.3.0")

}
