plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.sreepv43.soundhub"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.sreepv43.soundhub"
        minSdk = 23
        targetSdk = 35
        // CI builds count up, so a newer build is always a newer version.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.1.$build"
    }

    // One permanent key (the SOUNDHUB_KEYSTORE secret on CI), so every build installs over the last
    // one and the in-app updater works. Without it, builds use the CI machine's throwaway debug key.
    val stableKeystore = System.getenv("SOUNDHUB_KEYSTORE_FILE")?.let(::file)?.takeIf { it.isFile }
    val stable = stableKeystore?.let { keystore ->
        signingConfigs.create("stable") {
            storeFile = keystore
            storePassword = System.getenv("SOUNDHUB_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("SOUNDHUB_KEY_ALIAS") ?: "soundhub"
            keyPassword = System.getenv("SOUNDHUB_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        debug {
            if (stable != null) signingConfig = stable
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded, not from a store: the permanent key when there is one, else the debug key.
            signingConfig = stable ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    // Robolectric runs the remote-navigation UI tests on the JVM.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// The navigation tests save pictures of the main pages here; CI attaches them to each release.
tasks.withType<Test>().configureEach {
    systemProperty("soundhub.screenshots", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
}

dependencies {
    implementation(project(":soundhub-core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Installs the libraries' baseline profiles on sideloaded installs, so Compose code is
    // precompiled instead of interpreted (much smoother scrolling on TV boxes).
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    // Software decoding where the device has no decoder and no passthrough (e.g. TrueHD on a
    // tablet): Media3's FFmpeg extension, built and published by Jellyfin.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
