plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.lulitech.jpkisigner.pdf"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // PDFBox parsing/signing touches a few android.* stubs that are not mocked in
    // plain JVM unit tests. Returning defaults keeps the M0 software-key signing
    // test running with no device and no Robolectric.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// --- BouncyCastle hygiene (DESIGN.md §5.2) -----------------------------------
// bcprov / bcpkix / bcutil publish the same org.bouncycastle.* packages across
// their jdk15on, jdk15to18 and jdk18on variants. Mixing families gives duplicate
// classes or silent version skew, so: rewrite any other family onto jdk15to18,
// then pin each artifact to its own latest patch.
//
// Pin per artifact, NOT to one shared version string — bcutil 1.85 duplicates a
// bcprov class and breaks checkDuplicateClasses; 1.85.1 fixes it. See the note
// in libs.versions.toml and DESIGN.md §5.2.
dependencies {
    modules {
        listOf("bcprov", "bcpkix", "bcutil").forEach { artifact ->
            listOf("jdk15on", "jdk18on").forEach { family ->
                module("org.bouncycastle:$artifact-$family") {
                    replacedBy(
                        "org.bouncycastle:$artifact-jdk15to18",
                        "single BouncyCastle family — DESIGN.md §5.2",
                    )
                }
            }
        }
    }
}

// Scope the pinning to our own compile/runtime classpaths only. Applying it to
// every configuration also hits AGP's internal tooling configurations (e.g.
// androidLintTool), which legitimately resolve the jdk18on family — and our
// jdk15to18 patch versions do not exist there.
configurations.matching {
    it.name.endsWith("CompileClasspath") || it.name.endsWith("RuntimeClasspath")
}.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.bouncycastle") {
            val pinned = when (requested.name.substringBefore('-')) {
                "bcprov" -> libs.versions.bcprov.get()
                "bcpkix" -> libs.versions.bcpkix.get()
                "bcutil" -> libs.versions.bcutil.get()
                else -> null
            }
            if (pinned != null) {
                useVersion(pinned)
                because("pinned BouncyCastle artifact version — DESIGN.md §5.2")
            }
        }
    }
}

dependencies {
    api(libs.pdfbox.android)

    // Used as an ASN.1 / CMS structure library only. We never register a JCE
    // provider — see DESIGN.md §5.1.
    implementation(libs.bc.prov)
    implementation(libs.bc.pkix)
    implementation(libs.bc.util)

    testImplementation(libs.junit)
}
