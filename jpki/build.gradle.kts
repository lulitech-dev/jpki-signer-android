plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.lulitech.jpkisigner.jpki"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Deliberately no PDFBox and no BouncyCastle here. This module speaks APDUs and
// exchanges only ByteArrays with :pdf — see PLAN.md §3.
dependencies {
    testImplementation(libs.junit)
}
