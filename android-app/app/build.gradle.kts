plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.JavaExec

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.aifieldcam.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aifieldcam.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        val backendHost = localProperties.getProperty("backend.host", "").trim()
        buildConfigField("String", "BACKEND_HOST", "\"$backendHost\"")
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.withType<Test> {
    maxParallelForks = 1
}

/** Windows 上 Gradle Test Worker 偶发 ClassNotFound 时的备用入口 */
tasks.register<JavaExec>("runUnitTestsInline") {
    group = "verification"
    description = "在单 JVM 内运行 JVM 单元测试（绕过 Gradle Test Worker）"
    dependsOn("compileDebugUnitTestKotlin", "compileDebugKotlin")
    val testClassesDir = layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest")
    classpath = files(testClassesDir) + configurations.getByName("debugUnitTestRuntimeClasspath")
    mainClass.set("org.junit.runner.JUnitCore")
    args(
        "com.aifieldcam.app.platform.MediaInteractionPolicyTest",
        "com.aifieldcam.app.platform.RecorderKeyRouteTest",
        "com.aifieldcam.app.platform.RecordingForegroundHoldTest",
        "com.aifieldcam.app.demo.DemoScenariosTest",
        "com.aifieldcam.app.data.OfficerProfileTest",
        "com.aifieldcam.app.util.MediaStorageLocatorTest",
    )
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.5")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    testImplementation("junit:junit:4.13.2")
}
