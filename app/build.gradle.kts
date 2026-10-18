plugins {
    alias(libs.plugins.android.application)
}

val releaseKeystore = providers.environmentVariable("FOSSMIRROR_KEYSTORE").orNull
val releasePassword = providers.environmentVariable("FOSSMIRROR_STORE_PASSWORD").orNull
val releaseAlias = providers.environmentVariable("FOSSMIRROR_KEY_ALIAS").orElse("fossmirrortv").get()
require((releaseKeystore == null) == (releasePassword == null)) {
    "Release signing needs both FOSSMIRROR_KEYSTORE and FOSSMIRROR_STORE_PASSWORD"
}

android {
    namespace = "com.sal7one.tvtestproject"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.sal7one.tvtestproject"
        minSdk = 28
        targetSdk = 36
        versionCode = 8
        versionName = "1.1.0-alpha.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystore != null && releasePassword != null) {
            create("privateRelease") {
                storeFile = file(releaseKeystore)
                storePassword = releasePassword
                keyAlias = releaseAlias
                keyPassword = releasePassword
            }
        }
    }
    buildTypes {
        release {
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("privateRelease")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        viewBinding = false
    }
    splits {
        abi {
            isEnable = providers.gradleProperty("splitApks").orNull == "true"
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
}

dependencies {
    implementation(libs.jmdns)
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
