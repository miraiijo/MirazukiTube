import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Kotlin is built into AGP 9: no org.jetbrains.kotlin.android here.
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing from the environment only (never from the repo). Without it the release APK is
// unsigned: CI builds it that way and mirazuki-ship signs it on the Mac (the key never reaches GitHub).
val releaseKeystore: String? = System.getenv("MIRAZUKITUBE_KEYSTORE_PATH")

android {
    namespace = "dev.miraiijo.lumentube"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.miraiijo.lumentube"
        minSdk = 28
        targetSdk = 35
        versionCode = 81
        versionName = "1.0 bêta 20"
        // The Freebox Player POP runs a 32-bit userspace; arm64 kept for other boxes.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        // Upstream modules have SmartTube's build flavors: use the "stable" one.
        missingDimensionStrategy("default", "ststable")
        // mirazuki-link v3: signing certificates of the miryo apps (public SHA-256, see gradle.properties,
        // written by mirazuki-keys init). Empty = no link with that app (FeedProvider answers nobody).
        fun cert(app: String) = (project.findProperty("mirazuki.cert.$app") as String?).orEmpty().trim().lowercase().replace(":", "")
        buildConfigField("String", "CERT_MIRAZUKI", "\"${cert("mirazuki")}\"")
        buildConfigField("String", "CERT_MIRAZUKITUBE", "\"${cert("mirazukitube")}\"")
        buildConfigField("String", "CERT_MIRAZUKIIPTV", "\"${cert("mirazukiiptv")}\"")
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("MIRAZUKITUBE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MIRAZUKITUBE_KEY_ALIAS")
                keyPassword = System.getenv("MIRAZUKITUBE_KEY_PASSWORD")
            }
        }
    }

    // AGP 9 makes unit tests for the tested build type only: CI tests the release one (testReleaseUnitTest).
    testBuildType = "release"

    buildTypes {
        release {
            // Not minified, like SmartTube: its engine relies on reflection (Retrofit, Gson, JsonPath).
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release") else null
        }
    }

    lint { checkReleaseBuilds = false }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += listOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // SmartTube's engine (built from the fork's folders, see settings.gradle).
    implementation(project(":sharedutils"))
    implementation(project(":mediaserviceinterfaces"))
    implementation(project(":youtubeapi"))
    implementation(project(":exoplayer-library-core"))
    implementation(project(":exoplayer-library-dash"))
    implementation(project(":exoplayer-library-sabr"))
    implementation(project(":exoplayer-library-hls"))
    implementation(project(":exoplayer-library-smoothstreaming"))
    implementation(project(":exoplayer-library-ui"))
    implementation(project(":exoplayer-extension-okhttp"))
    // Cronet (QUIC): SmartTube's default data source for video segments.
    implementation(project(":exoplayer-extension-cronet"))
    // Same versions as SmartTube (its API returns RxJava 2 observables).
    implementation("io.reactivex.rxjava2:rxjava:2.2.21")
    implementation("io.reactivex.rxjava2:rxandroid:2.1.1")
    implementation("com.squareup.okhttp3:okhttp:3.12.13")

    // UI: same stack as miryo.
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.tv:tv-material:1.1.0")
    // Installs the Baseline Profiles (AndroidX/Compose libraries' + src/main/baseline-prof.txt) on a
    // sideloaded APK too (no Play Store): faster cold start and first scrolls. Same version as miryo.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // Splash screen at launch (miryo's logo), the same on Android 9 to 11 as on 12+.
    implementation("androidx.core:core-splashscreen:1.0.1")
    // Thumbnails: Glide (no OkHttp 4, which Coil needs and the engine can't use).
    implementation("com.github.bumptech.glide:glide:5.0.9")
    implementation("com.github.bumptech.glide:compose:1.0.0-beta10")
    // QR code of the sign-in, drawn on the box (no web QR service sees the activation code).
    implementation("com.google.zxing:core:3.5.4")

    // Pure JVM unit tests (parental code, appearance), run by the CI before the APK.
    testImplementation("junit:junit:4.13.2")
}
