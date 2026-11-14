plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ffvpn.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ffvpn.client"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.2.0"
    }

    // A fixed key kept in the repo. Every build, local or CI, signs with the
    // same certificate, so a new APK updates over an installed one instead of
    // failing with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("signing/niloofar.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // One APK per CPU family plus a universal one that runs anywhere.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        // Pairs with Kotlin 1.9.24.
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            // The engine is exec'd from nativeLibraryDir, so the .so files must
            // stay real files on disk instead of being mapped from the APK.
            useLegacyPackaging = true
            keepDebugSymbols += listOf(
                "**/libaether.so",
                "**/libc2tun.so",
                "**/libffvpn.so",
                "**/liblantern.so",
                "**/liblyrebird.so",
                "**/libgojni.so",
                "**/libsstp.so",
                "**/libsunlion.so",
                "**/libsunlionbridge.so",
                "**/libtor.so",
                "**/libtorrunner.so"
            )
        }
    }
}

dependencies {
    // The official Psiphon engine for Android (gomobile psi.Psi), published as a
    // release artifact by Psiphon-Labs/psiphon-tunnel-core-binaries. The engine
    // used to be a vendored .so built on clientlib, whose StartTunnel closes its
    // "connected" channel again when the engine replaces a dead tunnel and takes
    // the whole process down with "panic: close of closed channel".
    implementation(files("libs/ca.psiphon.aar"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")

    implementation("androidx.compose.ui:ui:1.6.6")
    implementation("androidx.compose.ui:ui-graphics:1.6.6")
    implementation("androidx.compose.foundation:foundation:1.6.6")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.material:material-icons-extended:1.6.6")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    debugImplementation("androidx.compose.ui:ui-tooling:1.6.6")
}
