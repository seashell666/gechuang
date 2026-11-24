package com.seashell.gechuang.framework

import android.hardware.display.DisplayManager
import com.seashell.gechuang.logging.GechuangLog

object VirtualDisplayFlagsCompat {
    data class Resolved(val value: Int, val names: List<String>)

    fun resolveCandidates(): List<Resolved> {
        val fallbackValue = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        var value = fallbackValue
        val names = mutableListOf("OWN_CONTENT_ONLY", "PRESENTATION")

        listOf(
            "VIRTUAL_DISPLAY_FLAG_TRUSTED" to "TRUSTED",
            "VIRTUAL_DISPLAY_FLAG_OWN_FOCUS" to "OWN_FOCUS",
            "VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP" to "OWN_DISPLAY_GROUP",
        ).forEach { (fieldName, label) ->
            readFlag(fieldName)?.let { flag ->
                value = value or flag
                names += label
            }
        }

        val ownFocus = Resolved(value, names.toList())
        // The IME runs in another app process. A private virtual display is
        // invisible to it and InputMethodService crashes while creating its
        // token context ("Display must not be null"). PUBLIC keeps the display
        // addressable so the keyboard can be hosted inside the same canvas.
        val publicFlag = readFlag("VIRTUAL_DISPLAY_FLAG_PUBLIC") ?: 0x1
        value = value or publicFlag
        names += "PUBLIC"
        // Slots render previews and receive selections through display 0. Keep
        // their local focus without letting a moved editor take the global IME
        // focus from MAIN during an exchange.
        if ("OWN_FOCUS" in names) {
            readFlag("VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED")?.let { flag ->
                value = value or flag
                names += "STEAL_TOP_FOCUS_DISABLED"
            }
        }
        val preferred = Resolved(value, names.toList())
        val fallback = Resolved(
            fallbackValue,
            listOf("OWN_CONTENT_ONLY", "PRESENTATION"),
        )
        return listOf(preferred, ownFocus, fallback).distinctBy { it.value }.also { candidates ->
            GechuangLog.info(
                "virtual_display_flags",
                "Resolved candidates=${candidates.joinToString { "${it.value}:${it.names}" }}",
            )
        }
    }

    private fun readFlag(name: String): Int? = runCatching {
        DisplayManager::class.java.getDeclaredField(name).apply { isAccessible = true }.getInt(null)
    }.onFailure { error ->
        GechuangLog.debug("virtual_display_flags", "Hidden flag unavailable name=$name error=${error.javaClass.simpleName}")
    }.getOrNull()
}
