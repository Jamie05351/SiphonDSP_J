// Generates the app's Baseline Profile (app/src/main/baseline-prof.txt) on a device or emulator:
//   ./gradlew :baselineprofile:connectedBenchmarkAndroidTest -Psiphon.extraAbis=x86_64
// (the ABI only for an x86_64 emulator). CI runs it in baseline-profile.yml; a manual run there
// commits the profile as app/src/main/baseline-prof.txt on the branch baseline-profile/update.
plugins {
    id("com.android.test")
}

android {
    namespace = "app.siphondsp.baselineprofile"
    compileSdk = AndroidConfig.compileSdk

    defaultConfig {
        minSdk = AndroidConfig.minSdk
        targetSdk = AndroidConfig.targetSdk
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // Matches the app's "benchmark" build type (release, not shrunk).
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

androidComponents {
    // Only the benchmark variant: the debug one would drive a debuggable app, which profiles wrong.
    beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" }
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
}
