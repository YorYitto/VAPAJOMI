// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    id("com.google.gms.google-services") version "4.4.4" apply false
}

// Redirige build raíz fuera de OneDrive
layout.buildDirectory.set(
    file("${System.getenv("LOCALAPPDATA")}/GradleBuilds/VAPAJOMI/root")
)