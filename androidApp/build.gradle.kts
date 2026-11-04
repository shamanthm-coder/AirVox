import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val supabaseUrl = localProperties.getProperty("supabase.url").orEmpty()
val supabaseAnonKey = localProperties.getProperty("supabase.anon.key").orEmpty()
val googleServicesFile = listOf(
    file("google-services.json"),
    rootProject.file("res/google-services.json"),
    rootProject.file("res/google-services (2).json"),
).firstOrNull { it.exists() } ?: file("google-services.json")
val googleServices = readGoogleServices(googleServicesFile)

fun prefer(property: String, fallback: String?): String {
    val local = localProperties.getProperty(property)?.trim().orEmpty()
    if (local.isNotEmpty()) return local
    return fallback?.trim().orEmpty()
}

val firebaseApiKey = prefer("firebase.api.key", googleServices["apiKey"])
val firebaseAppId = prefer("firebase.app.id", googleServices["appId"])
val firebaseProjectId = prefer("firebase.project.id", googleServices["projectId"])
val firebaseSenderId = prefer("firebase.sender.id", googleServices["senderId"])
val firebaseWebClientId = prefer("firebase.web.client.id", googleServices["webClientId"])
val airvoxApiUrl = prefer("airvox.api.url", "http://10.0.2.2:8080")

fun javaString(value: String): String {
    return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

dependencies {
    implementation(projects.shared)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.maplibre)
    implementation(libs.okhttp)
    implementation(libs.play.services.nearby)
    implementation(libs.play.services.auth)
    implementation(libs.stream.webrtc)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

android {
    namespace = "com.airvox"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        applicationId = "com.airvox"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "SUPABASE_URL", javaString(supabaseUrl))
        buildConfigField("String", "SUPABASE_ANON_KEY", javaString(supabaseAnonKey))
        buildConfigField("String", "FIREBASE_API_KEY", javaString(firebaseApiKey))
        buildConfigField("String", "FIREBASE_APP_ID", javaString(firebaseAppId))
        buildConfigField("String", "FIREBASE_PROJECT_ID", javaString(firebaseProjectId))
        buildConfigField("String", "FIREBASE_SENDER_ID", javaString(firebaseSenderId))
        buildConfigField("String", "FIREBASE_WEB_CLIENT_ID", javaString(firebaseWebClientId))
        buildConfigField("String", "AIRVOX_API_URL", javaString(airvoxApiUrl))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

fun readGoogleServices(services: java.io.File): Map<String, String> {
    if (!services.exists()) return emptyMap()
    val root = groovy.json.JsonSlurper().parse(services) as Map<*, *>
    val project = root["project_info"] as? Map<*, *> ?: return emptyMap()
    val clients = root["client"] as? List<*> ?: return emptyMap()
    val client = clients.firstOrNull { item ->
        val info = (item as? Map<*, *>)?.get("client_info") as? Map<*, *> ?: return@firstOrNull false
        val androidInfo = info["android_client_info"] as? Map<*, *> ?: return@firstOrNull false
        androidInfo["package_name"] == "com.airvox"
    } as? Map<*, *> ?: return emptyMap()
    val info = client["client_info"] as? Map<*, *> ?: return emptyMap()
    val apiKey = ((client["api_key"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("current_key") as? String
        ?: return emptyMap()
    val appId = info["mobilesdk_app_id"] as? String ?: return emptyMap()
    val projectId = project["project_id"] as? String ?: return emptyMap()
    var webClientId = ""
    for (item in client["oauth_client"] as? List<*> ?: emptyList<Any>()) {
        val oauth = item as? Map<*, *> ?: continue
        if ((oauth["client_type"] as? Number)?.toInt() == 3) {
            webClientId = oauth["client_id"] as? String ?: ""
            break
        }
    }
    return mapOf(
        "apiKey" to apiKey,
        "appId" to appId,
        "projectId" to projectId,
        "senderId" to project["project_number"]?.toString().orEmpty(),
        "webClientId" to webClientId,
    )
}
