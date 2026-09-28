plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    namespace = "com.vcamstudio.engine.transport"
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
    // r53 mandate: core-common ONLY (no engine-render/capture/output/audio).
    api(project(":core-common"))
    testImplementation(libs.junit)
}
