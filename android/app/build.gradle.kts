import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.learnai.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.learnai.app"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"

        // Content from Cloudflare Pages, guarded by functions/_middleware.js: the app sends its key with every request.
        // The key lives in android/local.properties (made by make_key.py, never committed); empty = bundled content only.
        val local = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use { load(it) } }
        buildConfigField("String", "CONTENT_URL", "\"https://<your-project>.pages.dev\"")
        buildConfigField("String", "CONTENT_KEY", "\"${local.getProperty("learnai.key", "")}\"")
    }

    buildFeatures { compose = true; buildConfig = true }

    // Readium's streamer/navigator use newer java.* APIs: required by their AAR metadata
    compileOptions { isCoreLibraryDesugaringEnabled = true }

    // Content bundled from ../../site (publish.py output): the offline first-run fallback.
    // Content.sync() downloads newer content from Cloudflare.
    sourceSets["main"].assets.srcDirs("../../site")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")  // same version Readium 3.4.0 builds with
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")

    // E-reader engine (BSD-3): open EPUBs (streamer) and render them (navigator). No PDF adapter: EPUB only.
    implementation("org.readium.kotlin-toolkit:readium-shared:3.4.0")
    implementation("org.readium.kotlin-toolkit:readium-streamer:3.4.0")
    implementation("org.readium.kotlin-toolkit:readium-navigator:3.4.0")
    implementation("androidx.appcompat:appcompat:1.8.0")  // ReaderActivity hosts the navigator Fragment
    implementation("androidx.fragment:fragment-ktx:1.9.1")  // commitNow {} (Readium uses it but doesn't expose it)

    // Spaced repetition (Review tab): the official FSRS-6 scheduler, MIT (github.com/open-spaced-repetition/java-fsrs)
    implementation("io.github.open-spaced-repetition:fsrs:1.0.0")
    // Daily "N cards due" notification
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    testImplementation("junit:junit:4.13.2")  // FocusStatsTest: ./gradlew testDebugUnitTest
}
