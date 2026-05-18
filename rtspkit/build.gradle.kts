plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.nittbit.rtspkit"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    api(project(":rtspcore"))
    api(project(":rtspsignaling"))
    api(project(":rtsptransport"))
    api(project(":h264depacketizer"))
    api(project(":clocksync"))
    api(project(":videodecoder"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
