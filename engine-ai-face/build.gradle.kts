plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    namespace = "com.vcamstudio.engine.aiface"
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

// Round-33: the ORT dependency is declared in THIS module (not :app), so the
// vendored-AAR conditional must live here too or CI would still hit Maven.
// :app carries the identical files(...) so the AAR classes are packaged into
// the APK (file deps are not transitive from library modules).
val ortAar = file("../libs/ort.aar")

dependencies {
    if (ortAar.exists()) {
        implementation(files(ortAar))
    } else {
        implementation(libs.onnxruntime.android)
    }
    implementation(project(":core-common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.camerax.core)
    implementation(libs.coroutines.core)
    implementation(libs.timber)
    testImplementation(libs.junit)
}
