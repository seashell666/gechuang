package com.seashell.gechuang.safety

import com.seashell.gechuang.logging.GechuangLog
import java.io.File

object EmergencyDisable {
    private val disableFile = File("/data/adb/nextep/disable")

    fun isRequested(): Boolean = try {
        disableFile.exists().also { disabled ->
            if (disabled) GechuangLog.warn("safety", "Emergency disable file is present")
        }
    } catch (error: SecurityException) {
        GechuangLog.warn("safety", "Emergency disable file could not be inspected", error)
        false
    }
}
