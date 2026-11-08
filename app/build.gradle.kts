import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version: GitHub Actions passes it in (from the tag or version.properties); local builds read version.properties.
val versionFile = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
val appVersionName = System.getenv("VERSION_NAME") ?: versionFile.getProperty("VERSION_NAME") ?: "0.1.0"
val appVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: appVersionName.substringBefore('-').split('.')
    .let { p -> (p.getOrNull(0)?.toIntOrNull() ?: 0) * 10000 + (p.getOrNull(1)?.toIntOrNull() ?: 0) * 100 + (p.getOrNull(2)?.toIntOrNull() ?: 0) }

// Signing key for releases. GitHub Actions: environment variables from the repository's secrets.
// Your own machine: keystore.properties next to settings.gradle.kts (made by tools/make-signing-key.sh, never committed).
// Neither there: release builds come out unsigned and local builds keep using the debug key.
val keyProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() } ?: keyProps.getProperty(prop)
val keystorePath = signingValue("SIGNING_KEYSTORE", "storeFile")
val hasReleaseKey = keystorePath != null && file(keystorePath).isFile

android {
    namespace = "com.eclipse.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eclipse.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = file(keystorePath!!)
            storePassword = signingValue("SIGNING_STORE_PASSWORD", "storePassword")
            keyAlias = signingValue("SIGNING_KEY_ALIAS", "keyAlias")
            keyPassword = signingValue("SIGNING_KEY_PASSWORD", "keyPassword")
        }
    }

    buildTypes {
        // The build script makes the "debug" APK, so the speed-ups go here too: R8 shrinks and optimises the code,
        // and the app isn't marked debuggable (debuggable apps run noticeably slower).
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // with your release key on this machine, your own builds are signed like the GitHub releases, so
            // Obtainium's updates install over them (and they over Obtainium's)
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // jcifs-ng brings Bouncy Castle and SLF4J, which carry duplicate signature / licence files
        resources.excludes += setOf(
            "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")      // SVG pictures (theme logos, icons)
    implementation("io.coil-kt:coil-gif:2.7.0")      // animated GIF / WebP backgrounds
    implementation("com.caverock:androidsvg-aar:1.4") // turns theme SVGs into pictures when a theme is imported
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10") // copies games to your RomM library over a network share (SMB)
    implementation("androidx.profileinstaller:profileinstaller:1.4.1") // installs the baseline profile (precompiled hot code)
}
