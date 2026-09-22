plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    // Phase 1: lint reports collected but non-blocking; enforced at the Phase 1 exit gate.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    namespace = "com.vcamstudio.engine.output"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
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
    implementation(project(":core-common")) // coroutines flows via core-common api
    testImplementation(libs.junit)
}
