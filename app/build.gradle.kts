plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace   = "com.smartring.app"
    compileSdk  = 34

    defaultConfig {
        applicationId   = "com.smartring.app"
        minSdk          = 26
        targetSdk       = 34
        versionCode     = 3
        versionName     = "1.2.0"
    }

    // A single committed keystore, reused for both build types, so every APK built
    // from this repo (any machine, any CI run) shares one signing identity. Without
    // this, each CI run's debug build gets a fresh ephemeral debug key and the
    // unconfigured release build is unsigned — neither installs as an update over a
    // previous build, forcing an uninstall/reinstall (and losing all alarms) every
    // time. There's no Play Store relationship to protect here, so a plaintext
    // password committed alongside the keystore is the right tradeoff for a
    // side-loaded personal app: it buys update compatibility, not secrecy.
    signingConfigs {
        create("shared") {
            storeFile     = file("smartring.keystore")
            storePassword = "smartring123"
            keyAlias      = "smartring"
            keyPassword   = "smartring123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            signingConfig     = signingConfigs.getByName("shared")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable        = true
            signingConfig        = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Room schema export – must be top-level
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.coroutines.android)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
}
