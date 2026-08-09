import java.security.MessageDigest
import java.util.Properties

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

}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("androidx.customview:customview:1.2.0")
    implementation("com.github.bhlangonijr:chesslib:1.3.7")
    testImplementation("junit:junit:4.13.2")
}

val bundledStockfish = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libstockfish.so")
val stockfishChecksums = Properties().apply {
    rootProject.file("stockfish-checksums.properties").inputStream().use(::load)
}
val expectedStockfishSha256 = stockfishChecksums.getProperty("binarySha256").uppercase()
tasks.register("verifyStockfish") {
    doLast {
        check(bundledStockfish.asFile.isFile) {
            "Stockfish is required for a functional release. Run android_app/download-stockfish.ps1 first."
        }
        val messageDigest = MessageDigest.getInstance("SHA-256")
        bundledStockfish.asFile.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                messageDigest.update(buffer, 0, count)
            }
        }
        val digest = messageDigest.digest().joinToString("") { "%02X".format(it) }
        check(digest == expectedStockfishSha256) {
            "Stockfish checksum mismatch: expected $expectedStockfishSha256, found $digest"
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn("verifyStockfish") }

val generatedNoticeAssets = layout.buildDirectory.dir("generated/third-party-notices")
android.sourceSets["main"].assets.srcDir(generatedNoticeAssets)
val copyThirdPartyNotices by tasks.registering(Copy::class) {
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
    from(rootProject.file("third_party")) { into("third_party") }
    into(generatedNoticeAssets)
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(copyThirdPartyNotices) }
