import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: same keystore.properties as the 1.x project (never committed):
//   storeFile=simpleplayer-release.jks   (absolute, or relative to keystore.properties)
//   storePassword=... keyAlias=... keyPassword=...
// Looked up in the project root, then the Gradle property simpleplayer.keystoreProperties,
// then the environment variable SIMPLEPLAYER_KEYSTORE_PROPERTIES.
val keystorePropertiesFile: File? = listOfNotNull(
    rootProject.file("keystore.properties"),
    (findProperty("simpleplayer.keystoreProperties") as String?)?.takeIf { it.isNotBlank() }?.let { File(it) },
    System.getenv("SIMPLEPLAYER_KEYSTORE_PROPERTIES")?.takeIf { it.isNotBlank() }?.let { File(it) }
).firstOrNull { it.isFile }

val keystoreProperties = Properties().apply {
    keystorePropertiesFile?.inputStream()?.use { load(it) }
}
val hasReleaseKey = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !keystoreProperties.getProperty(it).isNullOrBlank() }

// Test builds install next to the released app (their own data, no signing key needed):
//   gradlew assembleRelease -Ptestbuild
val testBuild = project.hasProperty("testbuild")

android {
    namespace = "com.akira.simpleplayer"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    // NDK used by cargo-ndk for the Rust core (Android Studio > SDK Manager > SDK Tools >
    // NDK (Side by side), this version)
    ndkVersion = "27.2.12479018"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = if (testBuild) "com.akira.simpleplayer.remake" else "com.akira.simpleplayer"
        minSdk = 26
        targetSdk = 37
        // Must stay above every Simple Player Android APK (its 1.4.0 shipped with 48),
        // since Resona installs over it with the same application ID.
        versionCode = 61
        // The in-app updater compares this with the GitHub Release tag.
        versionName = "1.0.1"
        resValue("string", "app_name", if (testBuild) "Resona 測試版" else "Resona")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        if (hasReleaseKey && !testBuild) {
            create("release") {
                val path = File(keystoreProperties.getProperty("storeFile"))
                storeFile = if (path.isAbsolute) path else File(keystorePropertiesFile!!.parentFile, path.path)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = when {
                testBuild -> signingConfigs.getByName("debug")
                hasReleaseKey -> signingConfigs.getByName("release")
                else -> null
            }
        }
    }

    buildFeatures { resValues = true }
    packaging { jniLibs { useLegacyPackaging = false } }

    // The release-time lint check crashes when Gradle runs on a newer JDK than lint knows
    // (it fails with just the JDK version, e.g. "25.0.4"). Run lint on its own instead.
    lint { checkReleaseBuilds = false }
}

// The Rust core (../core) is built into src/main/jniLibs by cargo-ndk before every build.
// One-time setup:
//   cargo install cargo-ndk
//   rustup target add aarch64-linux-android armv7-linux-androideabi
//   + the NDK version above, installed from Android Studio's SDK Manager
// Skip with -PskipCore when the libraries are already there.
// AGP 9 moved the SDK location to androidComponents (a lazy provider)
val sdkDir = androidComponents.sdkComponents.sdkDirectory
val ndkVer = android.ndkVersion

val buildCore = tasks.register<Exec>("buildCore") {
    workingDir = rootProject.file("core")
    commandLine(
        "cargo", "ndk", "-t", "arm64-v8a", "-t", "armeabi-v7a", "-P", "26",
        "-o", file("src/main/jniLibs").absolutePath, "build", "--release"
    )
    onlyIf { !project.hasProperty("skipCore") }
    doFirst {
        // cargo-ndk finds the NDK through these (Gradle knows where the SDK is). An NDK
        // counts as installed only with its source.properties (that is what cargo-ndk reads).
        fun valid(dir: File?) = dir != null && File(dir, "source.properties").isFile
        val fromEnv = System.getenv("ANDROID_NDK_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) }
        val ndk = listOfNotNull(
            fromEnv,
            File(sdkDir.get().asFile, "ndk/${ndkVer}")
        ).firstOrNull { valid(it) }
            ?: throw GradleException(
                "NDK ${ndkVer} is not installed (no source.properties in " +
                    "${File(sdkDir.get().asFile, "ndk/" + ndkVer)}" +
                    (fromEnv?.let { " nor in ANDROID_NDK_HOME=$it" } ?: "") + ").\n" +
                    "Install it: Android Studio > SDK Manager > SDK Tools > Show Package Details > " +
                    "NDK (Side by side) > ${ndkVer}. Or build with -PskipCore."
            )
        environment("ANDROID_NDK_HOME", ndk.absolutePath)
        environment("ANDROID_HOME", sdkDir.get().asFile.absolutePath)
        val probe = ProcessBuilder("cargo", "ndk", "--version").redirectErrorStream(true)
        val ok = runCatching { probe.start().waitFor() == 0 }.getOrDefault(false)
        if (!ok) {
            throw GradleException(
                "cargo-ndk is missing. Run once:\n" +
                    "  cargo install cargo-ndk\n" +
                    "  rustup target add aarch64-linux-android armv7-linux-androideabi\n" +
                    "or build with -PskipCore if app/src/main/jniLibs already has libspcore.so."
            )
        }
    }
}
tasks.named("preBuild") { dependsOn(buildCore) }

// checkReleaseBuilds alone does not stop the lint-vital task on every AGP version; it
// crashes on JDK 25 (see the lint block above), so switch it off outright.
tasks.matching { it.name.startsWith("lintVital") }.configureEach { enabled = false }

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // backdrop blur behind the mini player (Mica-like); plain translucent tint before Android 12
    implementation("dev.chrisbanes.haze:haze:1.2.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.core:core-ktx:1.19.1")
}
