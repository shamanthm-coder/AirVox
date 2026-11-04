package com.airvox.auth

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

object FirebaseGate {
    @Volatile
    var ready: Boolean = false
        private set

    @Volatile
    var reason: String? = null
        private set

    fun start(
        context: Context,
        apiKey: String,
        appId: String,
        projectId: String,
        senderId: String,
    ) {
        val missing = listOf(
            "firebase.api.key" to apiKey,
            "firebase.app.id" to appId,
            "firebase.project.id" to projectId,
            "firebase.sender.id" to senderId,
        ).filter { it.second.isBlank() }.map { it.first }
        if (missing.isNotEmpty()) {
            ready = false
            reason = "Add ${missing.joinToString(", ")} to local.properties, or place google-services.json in androidApp, then rebuild."
            return
        }
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(
                    context,
                    FirebaseOptions.Builder()
                        .setApiKey(apiKey)
                        .setApplicationId(appId)
                        .setProjectId(projectId)
                        .setGcmSenderId(senderId)
                        .build(),
                )
            }
            ready = true
            reason = null
        } catch (error: Throwable) {
            ready = false
            reason = error.message ?: "Firebase could not start."
        }
    }
}
