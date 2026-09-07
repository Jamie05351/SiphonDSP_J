plugins {
    id("com.android.library")
}

android {
    compileSdk = AndroidConfig.compileSdk

    defaultConfig {
        minSdk = 23
        lint.targetSdk = AndroidConfig.targetSdk

        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    namespace = "com.amrdeveloper.codeview"
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
}
