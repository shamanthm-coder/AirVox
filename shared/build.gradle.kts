import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// iPhone reads its sign-in settings from local.properties, like Android does.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val iosFirebaseApiKey = localProperties.getProperty("firebase.api.key")?.trim().orEmpty()
// The simulator reaches this computer at localhost; 10.0.2.2 only works on the Android emulator.
val iosApiUrl = localProperties.getProperty("airvox.ios.api.url")?.trim()?.takeIf { it.isNotEmpty() }
    ?: localProperties.getProperty("airvox.api.url")?.trim()?.takeIf { it.isNotEmpty() && "10.0.2.2" !in it }
    ?: "http://localhost:8080"
val iosConfigDir = layout.buildDirectory.dir("generated/iosConfig/kotlin")
val writeIosConfig by tasks.registering {
    val key = iosFirebaseApiKey
    val url = iosApiUrl
    inputs.property("key", key)
    inputs.property("url", url)
    outputs.dir(iosConfigDir)
    doLast {
        fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val file = iosConfigDir.get().file("com/airvox/IosConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package com.airvox\n\n" +
                "// Generated from local.properties by shared/build.gradle.kts. Not committed.\n" +
                "internal object IosConfig {\n" +
                "    const val FIREBASE_API_KEY = ${quoted(key)}\n" +
                "    const val AIRVOX_API_URL = ${quoted(url)}\n" +
                "}\n",
        )
    }
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    android {
        namespace = "com.airvox.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        androidResources {
            enable = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:1.12.2")
            implementation("org.jetbrains.compose.foundation:foundation:1.12.2")
            implementation("org.jetbrains.compose.material3:material3:1.9.0")
            implementation("org.jetbrains.compose.ui:ui:1.12.2")
            implementation("org.jetbrains.compose.components:components-resources:1.12.2")
        }
        iosMain {
            kotlin.srcDir(writeIosConfig)
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.kotlinx.coroutines.get()}")
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.airvox.generated.resources"
}
