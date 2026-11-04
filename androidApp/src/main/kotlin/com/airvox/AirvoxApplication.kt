package com.airvox

import android.app.Application
import com.airvox.auth.FirebaseGate

class AirvoxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseGate.start(
            context = this,
            apiKey = BuildConfig.FIREBASE_API_KEY,
            appId = BuildConfig.FIREBASE_APP_ID,
            projectId = BuildConfig.FIREBASE_PROJECT_ID,
            senderId = BuildConfig.FIREBASE_SENDER_ID,
        )
    }
}
