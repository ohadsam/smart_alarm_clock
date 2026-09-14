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
        versionCode     = 11
        versionName     = "1.6.2"
        // Hilt's own runner, so @HiltAndroidTest instrumented tests get a real DI
        // graph on the device instead of the app's @HiltAndroidApp Application.
        testInstrumentationRunner = "com.smartring.app.HiltTestRunner"
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

    // Robolectric needs the merged manifest/resources on the unit-test classpath —
    // without this it can't resolve the app's AndroidManifest.xml (application class,
    // permissions) that some shadowed framework behavior depends on.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// Full exception detail (message + stack trace) straight into the CI console log —
// the default is just "ExceptionClass at File.kt:N" with no message, and the fuller
// detail otherwise only lives in the HTML/XML test report artifact, which isn't
// always reachable (e.g. an environment whose network policy blocks the artifact's
// storage host but not the Actions API/log itself).
tasks.withType<Test> {
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
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

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.androidx.test.core)

    // ── Instrumented tests: run on a real emulator in CI (see the "instrumented
    // tests" job in .github/workflows/build-apk.yml). These cover what Robolectric
    // deliberately can't: the real AlarmManager actually accepting an alarm-clock
    // registration, real SQLite, the real notification channel, and the real Compose
    // UI starting up through the real Hilt graph.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    // Supplies the empty Activity that createAndroidComposeRule/createComposeRule
    // hosts the composable under test in; without it they fail to launch at runtime.
    debugImplementation(libs.compose.ui.test.manifest)
}
