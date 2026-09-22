// VCam Studio — root build configuration.
// All versions are pinned in gradle/libs.versions.toml (blueprint §B.10:
// "tiny pinned stack, single version catalog").
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
