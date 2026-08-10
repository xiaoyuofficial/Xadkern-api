plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("kotlin-parcelize")
}

android {
    namespace = "xadkern.aidl"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        aidl = true
        buildConfig = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}


dependencies {
    implementation(libs.gson)
    implementation(libs.rikka.parcelablelist)
}

extra["publishLibrary"] = true