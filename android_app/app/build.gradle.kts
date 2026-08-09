plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.openautomaticchessboard.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.openautomaticchessboard.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/NOTICE.md", "META-INF/LICENSE.md")
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("com.github.bhlangonijr:chesslib:1.3.7")
    testImplementation("junit:junit:4.13.2")
}

val bundledStockfish = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libstockfish.so")
tasks.register("verifyStockfish") {
    doLast {
        check(bundledStockfish.asFile.isFile) {
            "Stockfish is required for a functional release. Run android_app/download-stockfish.ps1 first."
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn("verifyStockfish") }
