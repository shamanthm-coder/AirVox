package com.airvox

import platform.UIKit.UIDevice

actual fun platformLabel(): String {
    val device = UIDevice.currentDevice
    return "${device.systemName} ${device.systemVersion}"
}
