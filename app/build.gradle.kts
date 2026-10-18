plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.subburner.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.subburner.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // مكتبة FFmpeg منشورة لمعالجات arm64 بس (معظم موبايلات اليومين دول)
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // FFmpeg (نسخة متصانة من ffmpeg-kit، فيها libass للترجمة و x264 للضغط)
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:6.0.3")

    // مكتبة لازمة لـ FFmpeg وقت التشغيل (من غيرها التطبيق بيقفل أول ما تدوس Start)
    implementation("com.arthenica:smart-exception-java:0.2.1")
}
