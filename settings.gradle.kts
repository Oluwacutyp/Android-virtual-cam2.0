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
include(":app")
