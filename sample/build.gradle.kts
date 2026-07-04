plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.lucas.edgellm.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.lucas.edgellm.sample"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(project(":edgellm-core"))
    runtimeOnly(project(":edgellm-engine-llamacpp"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
