import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Local, uncommitted configuration. See apps/android/README.md.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun localConfig(name: String, default: String = ""): String =
    (providers.gradleProperty(name).orNull ?: localProperties.getProperty(name) ?: default).trim()

fun String.quoted(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// A release build can have its own values (hcm.release.<name>), so one machine builds the debug
// app against a local backend and the release app against production. Without one it uses the shared value.
fun releaseConfig(name: String, default: String = ""): String =
    localConfig("hcm.release.$name").ifEmpty { localConfig("hcm.$name", default) }

// The key the release app is signed with. See apps/android/README.md, "Release build".
val releaseStoreFile = localConfig("hcm.release.storeFile")

android {
    namespace = "com.northphoenix.hairdresserclientmanager"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.northphoenix.hairdresserclientmanager"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.3.0"

        buildConfigField("String", "CLERK_PUBLISHABLE_KEY", localConfig("hcm.clerkPublishableKey").quoted())
        buildConfigField("String", "API_BASE_URL", localConfig("hcm.apiBaseUrl", "http://10.0.2.2:3000").quoted())
        buildConfigField("String", "WEB_BASE_URL", localConfig("hcm.webBaseUrl").quoted())
        buildConfigField("String", "DEV_AUTH_USER_ID", "\"\"")
    }

    signingConfigs {
        if (releaseStoreFile.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = localConfig("hcm.release.storePassword")
                keyAlias = localConfig("hcm.release.keyAlias")
                keyPassword = localConfig("hcm.release.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Debug-only sign-in bypass for the local dev backend. Never set for release.
            buildConfigField("String", "DEV_AUTH_USER_ID", localConfig("hcm.devAuthUserId").quoted())
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Unsigned when no release key is configured.
            signingConfig = signingConfigs.findByName("release")

            buildConfigField("String", "CLERK_PUBLISHABLE_KEY", releaseConfig("clerkPublishableKey").quoted())
            buildConfigField("String", "API_BASE_URL", releaseConfig("apiBaseUrl", "http://10.0.2.2:3000").quoted())
            buildConfigField("String", "WEB_BASE_URL", releaseConfig("webBaseUrl").quoted())
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // Only these two languages ship; drop the dozens of translations libraries bring along.
        localeFilters += listOf("en", "ru")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.clerk.api)
    implementation(libs.store)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
