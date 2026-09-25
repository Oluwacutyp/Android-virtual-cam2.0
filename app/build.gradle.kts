plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    // Phase 1: lint reports collected but non-blocking; enforced at the Phase 1 exit gate.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    namespace = "com.vcamstudio.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vcamstudio.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-phase1"

        // Round-35 (owner fix 1): ORT's AAR may push the debug dex over the
        // 64K method limit; without multidex mergeDex can hang/OOM instead
        // of erroring. Costs nothing when not needed.
        multiDexEnabled = true

        ndk {
            // Round-32c (owner): arm64-v8a ONLY. The r32b hang was
            // :app:packageDebug zipping ~400 MB of UNSTRIPPED natives
            // (stripDebugDebugSymbols fell back — no NDK strip tool on the
            // runner; 4 ABIs x ~100 MB). One ABI cuts that ~4x. The S22
            // Ultra is arm64-v8a; re-add armeabi-v7a only if a 32-bit
            // device enters scope.
            abiFilters.clear()
            abiFilters += "arm64-v8a"
        }
    }

    packaging {
        jniLibs {
            // Round-35 (owner fix 3): the r34 log shows x86_64 libs STILL in
            // merged_native_libs despite the arm64 abiFilter — the vendored
            // files(...) AAR bypasses ndk.abiFilters, so ~400 MB of
            // unstripped natives (4 ABIs of ORT) fed packageDebug again.
            // excludes apply at merge time regardless of dependency type.
            useLegacyPackaging = true
            excludes += listOf(
                "lib/x86/**",
                "lib/x86_64/**",
                "lib/mips/**",
                "lib/mips64/**",
            )
            // Round-33: ORT ships pre-stripped; the no-NDK runner warned and
            // repackaged as-is. keepDebugSymbols skips the futile strip pass.
            keepDebugSymbols += setOf(
                "**/libonnxruntime.so",
                "**/libonnxruntime4j_jni.so",
            )
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Round-33 (owner mandate 1): vendored ORT AAR when present (CI fetches it
// from the GitHub release; local builds fall back to Maven). files(...) is
// the modern form — no flatDir.
val ortAar = file("../libs/ort.aar")

dependencies {
    if (ortAar.exists()) {
        implementation(files(ortAar))
    } else {
        implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    }
    implementation(project(":core-common"))
    implementation(project(":engine-render"))
    implementation(project(":engine-capture"))
    implementation(libs.camerax.view)
    implementation(project(":engine-media"))
    implementation(project(":engine-audio"))
    implementation(project(":engine-output"))
    implementation(project(":engine-ai-core"))
    implementation(project(":engine-ai-face"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.datastore.preferences)
    implementation(libs.timber)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    testImplementation(libs.junit)
}
