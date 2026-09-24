plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.vcamstudio.probe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vcamstudio.probe"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// DELIBERATELY no dependency on any engine module (round-19 mandate 1):
// the probe must not share a single line of studio rendering code.
dependencies {
}
