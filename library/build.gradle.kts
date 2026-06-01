plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.jenny.litertlm"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
}

dependencies {
    // LiteRT LM SDK (api so app module can access)
    api("com.google.ai.edge.litertlm:litertlm-android:0.12.0")

    // Ktor Server (api so app module can access)
    api("io.ktor:ktor-server-netty:2.3.12")
    api("io.ktor:ktor-server-content-negotiation:2.3.12")
    api("io.ktor:ktor-serialization-gson:2.3.12")
    api("io.ktor:ktor-server-cors:2.3.12")
    api("io.ktor:ktor-server-status-pages:2.3.12")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Serialization
    api("com.google.code.gson:gson:2.10.1")

    // AndroidX Core + Notifications (for Foreground Service)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
}
