import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Version lives in /version.properties so scripts/release.sh can bump it.
val versionProps = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }

// Release signing key stays outside the repo. Point DONG_SIGNING at a properties file with
// storeFile, storePassword, keyAlias, keyPassword (default: ~/.config/dong/signing.properties).
val signingProps = (System.getenv("DONG_SIGNING") ?: "${System.getProperty("user.home")}/.config/dong/signing.properties")
    .let(::file).takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "com.github.opscalehub.dong"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.github.opscalehub.dong"
        minSdk = 26
        targetSdk = 35
        versionCode = versionProps.getProperty("VERSION_CODE").toInt()
        versionName = versionProps.getProperty("VERSION_NAME")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Persian-only UI: drop the ~90 locales of library strings.
        resourceConfigurations += listOf("fa")
        // Update check (update/Updater.kt). Release builds check on start; debug builds only when
        // -PupdateUrl=... is given, so tests never touch the network.
        val updateUrl = (findProperty("updateUrl") as String?)
        // Comma-separated, tried in order: the Pages address after the Dong → Dang rename, then the old one.
        val updateUrls = updateUrl ?: "https://opscalehub.github.io/Dang/release.json,https://opscalehub.github.io/Dong/release.json"
        buildConfigField("String", "UPDATE_URLS", "\"$updateUrls\"")
        buildConfigField("boolean", "UPDATE_AUTO_DEBUG", "${updateUrl != null}")
    }

    signingConfigs {
        if (signingProps != null) create("release") {
            storeFile = file(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    sourceSets {
        // Exported Room schemas are read by MigrationTestHelper in Robolectric tests, which see the
        // merged debug assets. Debug builds only; release APKs do not contain them.
        getByName("debug").assets.srcDir("$projectDir/schemas")
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.maxHeapSize = "2g" }
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    debugImplementation(libs.androidx.ui.test.manifest)
}
