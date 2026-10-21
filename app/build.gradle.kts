plugins {
    id("com.android.application")
}

android {
    namespace = "com.coolzyd.audioforwarder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.coolzyd.audioforwarder"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
