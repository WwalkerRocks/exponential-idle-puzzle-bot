plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.wwalkerrocks.puzzlebot"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.wwalkerrocks.puzzlebot"
        minSdk = 30 // AccessibilityService.takeScreenshot needs Android 11+
        targetSdk = 35
        versionCode = 6
        versionName = "1.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideloaded personal app: sign release with the local debug key so it installs directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.all {
            it.maxHeapSize = "2g"
            it.systemProperty("regenTemplates", System.getProperty("regenTemplates") ?: "false")
            it.systemProperty("templateOut", file("src/main/java/io/github/wwalkerrocks/puzzlebot/core/DigitTemplateData.kt").absolutePath)
            it.testLogging { events("passed", "failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
