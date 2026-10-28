import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Kotlin is built into AGP 9: no org.jetbrains.kotlin.android here.
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing from the environment only (never from the repo). Without it the release APK is
// unsigned: CI builds it that way and mirazuki-ship signs it on the PC (the key never reaches GitHub).
val releaseKeystore: String? = System.getenv("MIRYOTUBE_KEYSTORE_PATH")

android {
    namespace = "dev.miraiijo.miryotube"
    compileSdk = 37
    // NDK used only to strip the native libraries' debug symbols (stripReleaseDebugSymbols): J2V8 ships
    // with them, 24.3 MB instead of 14.8 MB. AGP downloads this version if it's missing; it's also one
    // of the versions preinstalled on GitHub's runners. checkNativeLibsStripped fails the build if not.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.miraiijo.lumentube"
        minSdk = 28
        targetSdk = 35
        versionCode = 87
        versionName = "1.0 bêta 26"
        // 32-bit only (Jordan, 2026-10-27): the Freebox Player POP runs a 32-bit userspace
        // (abilist armeabi-v7a, armeabi) and almost every Android TV box runs 32-bit code, so one
        // copy of the native libraries halves their weight. A 64-bit-only device can't install it.
        ndk { abiFilters += listOf("armeabi-v7a") }
        // Upstream modules have SmartTube's build flavors: use the "stable" one.
        missingDimensionStrategy("default", "ststable")
        // miryo-link v3: signing certificates of the miryo apps (public SHA-256, see gradle.properties,
        // written by mirazuki-keys init). Empty = no link with that app (FeedProvider answers nobody).
        fun cert(app: String) = (project.findProperty("miryo.cert.$app") as String?).orEmpty().trim().lowercase().replace(":", "")
        buildConfigField("String", "CERT_MIRYO", "\"${cert("miryo")}\"")
        buildConfigField("String", "CERT_MIRYOTUBE", "\"${cert("miryotube")}\"")
        buildConfigField("String", "CERT_MIRYOIPTV", "\"${cert("miryoiptv")}\"")
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("MIRYOTUBE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MIRYOTUBE_KEY_ALIAS")
                keyPassword = System.getenv("MIRYOTUBE_KEY_PASSWORD")
            }
        }
    }

    // AGP 9 makes unit tests for the tested build type only: CI tests the release one (testReleaseUnitTest).
    testBuildType = "release"

    buildTypes {
        release {
            // R8 shrinks and optimizes the well-behaved libraries (AndroidX with Compose, Kotlin, Guava,
            // RxJava, ExoPlayer, Glide, ZXing): un-shrunk Compose ran interpreted-heavy on the Player POP
            // (13 to 22 ms per frame composing while a row scrolled), and their unused classes only
            // made the dex heavier (bêta 26: 24.5 MB). SmartTube's engine (reflection: Retrofit, Gson,
            // JsonPath) and the JNI libraries are kept whole and unrenamed, see proguard-rules.pro.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release") else null
        }
    }

    lint {
        checkReleaseBuilds = false
        // miryo tube's own code only (SmartTube's modules are upstream's).
        checkDependencies = false
    }

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

// Without an NDK, AGP only warns and packages the native libraries unstripped (bêta 26: +9.5 MB of
// J2V8 debug info). The release APK must never ship that silently: the build fails here instead.
val checkNativeLibsStripped = tasks.register("checkNativeLibsStripped") {
    val apkDir = layout.buildDirectory.dir("outputs/apk/release")
    val ndk = android.ndkVersion
    inputs.dir(apkDir)
    doLast {
        // Names of an ELF file's sections (little-endian, 32 or 64 bits): stripped = no .debug_info.
        fun sectionNames(elf: ByteArray): List<String> {
            val b = java.nio.ByteBuffer.wrap(elf).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val is64 = elf[4].toInt() == 2
            val shoff = if (is64) b.getLong(0x28).toInt() else b.getInt(0x20)
            val shentsize = b.getShort(if (is64) 0x3A else 0x2E).toInt()
            val shnum = b.getShort(if (is64) 0x3C else 0x30).toInt()
            val shstrndx = b.getShort(if (is64) 0x3E else 0x32).toInt()
            fun offset(i: Int) = shoff + i * shentsize + if (is64) 0x18 else 0x10
            val strtab = if (is64) b.getLong(offset(shstrndx)).toInt() else b.getInt(offset(shstrndx))
            return (0 until shnum).map { i ->
                var p = strtab + b.getInt(shoff + i * shentsize)
                val start = p
                while (elf[p].toInt() != 0) p++
                String(elf, start, p - start)
            }
        }
        val apks = apkDir.get().asFile.listFiles { f -> f.extension == "apk" }.orEmpty()
        check(apks.isNotEmpty()) { "No release APK in ${apkDir.get().asFile}" }
        for (apk in apks) java.util.zip.ZipFile(apk).use { zip ->
            val unstripped = zip.entries().asSequence()
                .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                .filter { entry -> ".debug_info" in sectionNames(zip.getInputStream(entry).use { it.readBytes() }) }
                .map { it.name }.toList()
            check(unstripped.isEmpty()) {
                "Native libraries not stripped in ${apk.name}: $unstripped. Install NDK $ndk " +
                    "(Android Studio > SDK Manager > SDK Tools > NDK (Side by side)), then rebuild."
            }
        }
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(checkNativeLibsStripped) }

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
