# VCam Studio release rules (minify disabled in Phase 1; rules kept for later).
# Keep GLSL string-built classes and engine entry points intact:
-keep class com.vcamstudio.engine.** { *; }
-dontwarn org.jetbrains.annotations.**
