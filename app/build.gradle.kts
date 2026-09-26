plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "io.github.sreepv43.streamhub"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.sreepv43.streamhub"
        minSdk = 23
        targetSdk = 35
        // CI builds count up, so every new build installs over the previous one.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.2.$build"
        buildConfigField("int", "BUILD_NUMBER", build.toString())
        buildConfigField("String", "UPDATE_REPO", "\"sreepv43-lab/Test\"")
        // Trakt API app (trakt.tv/oauth/applications), from GitHub secrets; empty = Trakt sync unavailable.
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"${System.getenv("TRAKT_CLIENT_ID").orEmpty()}\"")
        buildConfigField("String", "TRAKT_CLIENT_SECRET", "\"${System.getenv("TRAKT_CLIENT_SECRET").orEmpty()}\"")
    }

    // The permanent key and its password come from GitHub secrets in CI (see the workflow); without
    // them, builds fall back to the debug key and can't update an install made with the real one.
    val permanentKey = System.getenv("STREAMHUB_KEYSTORE_FILE")?.let(::File)?.takeIf { it.exists() }
    val permanentKeyPassword = System.getenv("STREAMHUB_KEYSTORE_PASSWORD")
    signingConfigs {
        if (permanentKey != null && !permanentKeyPassword.isNullOrEmpty()) {
            create("permanent") {
                storeFile = permanentKey
                storePassword = permanentKeyPassword
                keyAlias = System.getenv("STREAMHUB_KEY_ALIAS") ?: "streamhub"
                keyPassword = permanentKeyPassword
            }
        }
    }

    buildTypes {
        val signing = signingConfigs.findByName("permanent") ?: signingConfigs.getByName("debug")
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signing
        }
        debug {
            signingConfig = signing
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // One small APK per CPU type plus a universal APK (the torrent engine is native code).
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Robolectric runs the Compose UI tests (remote navigation) on the JVM.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

val torrentDesktopNative: Configuration by configurations.creating

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Installs the libraries' baseline profiles on sideloaded installs, so Compose code is
    // precompiled instead of interpreted (much smoother scrolling on TV boxes).
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    val libtorrent = "2.1.0-39"
    implementation("org.libtorrent4j:libtorrent4j:$libtorrent")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$libtorrent")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$libtorrent")
    implementation("org.libtorrent4j:libtorrent4j-android-x86:$libtorrent")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:$libtorrent")
    torrentDesktopNative("org.libtorrent4j:libtorrent4j-linux:$libtorrent")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// JVM unit tests run the real torrent engine against the desktop build of libtorrent (Linux x86-64).
val extractTorrentNative by tasks.registering(Copy::class) {
    from({ torrentDesktopNative.map { zipTree(it) } }) { include("lib/x86_64/*.so") }
    into(layout.buildDirectory.dir("torrent-native"))
}

tasks.withType<Test>().configureEach {
    dependsOn(extractTorrentNative)
    val native = layout.buildDirectory.file("torrent-native/lib/x86_64/libtorrent4j.so").get().asFile
    systemProperty("libtorrent4j.jni.path", native.absolutePath)
    // libtorrent installs signal handlers; HotSpot needs signal chaining to coexist with them.
    val jsig = File(System.getProperty("java.home"), "lib/libjsig.so")
    if (jsig.exists()) environment("LD_PRELOAD", jsig.absolutePath)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
