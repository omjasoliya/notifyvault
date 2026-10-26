plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes a stable signing key through these variables so that updates install over older builds.
// Without them the build falls back to the random debug key.
val keystoreFile: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "io.github.omjasoliya.notifyvault"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.omjasoliya.notifyvault"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("vault") {
                storeFile = file(keystoreFile)
                storeType = "pkcs12"
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("vault")
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
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
