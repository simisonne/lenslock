plugins {
    id("com.android.application")
}

android {
    namespace = "com.simon.lenslock"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.simon.lenslock"
        minSdk = 33
        targetSdk = 36
        versionCode = 8
        versionName = "1.6.1"
    }

    buildTypes {
        release {
            // Signed with the standard Android debug key, the same key every debug
            // build uses, so the release APK installs over a local build and keeps
            // working. No Play Store uploads with this key.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
