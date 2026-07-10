plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.rezzaghi.edgellm.engine.llamacpp"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        // 28+: the Android Vulkan loader gains the 1.1 symbols ggml needs
        minSdk = 28

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // Inference is unusable at -O0; optimize native code even in
                // debug variants (Kotlin/Java debugging is unaffected).
                arguments += "-DCMAKE_BUILD_TYPE=Release"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
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

    testImplementation("junit:junit:4.13.2")
}
