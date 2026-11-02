package com.airvox

actual fun platformLabel(): String = "Android ${android.os.Build.VERSION.RELEASE}"
