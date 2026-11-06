plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.dixit.wakeproof"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dixit.wakeproof"
        minSdk = 29
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    // Fixed personal signing key so each new build installs over the old one.
    signingConfigs {
        create("personal") {
            storeFile = file("wakeproof.keystore")
            storePassword = "wakeproof"
            keyAlias = "wakeproof"
            keyPassword = "wakeproof"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    // Face detection that runs on the phone (bundled model, works offline).
    implementation("com.google.mlkit:face-detection:16.1.7")
}
