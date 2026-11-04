import org.gradle.api.tasks.JavaExec

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.airvox.server.ServerKt")
}

tasks.named<JavaExec>("run") {
    val projectId = (findProperty("firebaseProjectId") as String?) ?: System.getenv("FIREBASE_PROJECT_ID")
    val port = (findProperty("airvoxPort") as String?) ?: System.getenv("PORT")
    val data = (findProperty("airvoxData") as String?) ?: System.getenv("AIRVOX_DATA")
    if (!projectId.isNullOrBlank()) environment("FIREBASE_PROJECT_ID", projectId)
    if (!port.isNullOrBlank()) environment("PORT", port)
    if (!data.isNullOrBlank()) environment("AIRVOX_DATA", data)
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.cors)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.ktor.server.test.host)
}
