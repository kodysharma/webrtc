plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "desidev.videocall.service"
    compileSdk = 34

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    composeOptions {
        val kcev = libs.versions.kotlinCompilerExtensionVersion
        kotlinCompilerExtensionVersion = kcev.get()
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":turnclient"))
    implementation(platform(libs.androidx.compose.bom))

    // dependency bundle
    implementation(libs.bundles.webrtc.dep.bundle)

    coreLibraryDesugaring(libs.coreLibraryDesugaring)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

}