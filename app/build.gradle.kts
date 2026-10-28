plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing key comes from the build environment (GitHub Actions secrets). Without it, the default debug key is used.
val keystorePath: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "com.spaimic.adbtool"
    compileSdk = 34

    signingConfigs {
        getByName("debug") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.spaimic.adbtool"
        minSdk = 21
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
