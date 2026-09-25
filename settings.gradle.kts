pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Round-32 (owner "CI HANG ON ASSEMBLE DEBUG"): mirrors first —
        // Maven Central throttled/timed out fetching the ~65 MB ONNX
        // Runtime AAR on two consecutive CI runs. Mirrors cost nothing
        // when fast; Gradle falls through to the canonical repos on a 404.
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        google()
        mavenCentral()
    }
}

rootProject.name = "VCamStudio"

// ---------------------------------------------------------------------------
// Phase 1 module set (locked per docs/BLUEPRINT.md — Phase 1 contains no AI,
// no face swap, no voice cloning, no injection).
//
// Later-phase modules (engine-ai-face, engine-ai-audio, vcam-module,
// tools-dev) are ADDED TOGETHER WITH their phase, never before —
// no empty shells, no stub modules (blueprint §F "fake builds" doctrine).
// ---------------------------------------------------------------------------
include(":core-common")
include(":engine-render")
include(":engine-capture")
include(":engine-media")
include(":engine-audio")
include(":engine-output")
// Phase 2 (architecture doc §C "Become Anyone"): AI core lands together
// with its phase — engine-ai-core (model manager) + engine-ai-face (SCRFD
// detection this round; swap/tracking in later rounds).
include(":engine-ai-core")
include(":engine-ai-face")
include(":app")
// Round-20 mandate 1: standalone minimal wedge probe. Separate APK, ZERO
// dependency on any studio engine module — decisive isolation either way.
include(":probe-wedge")
