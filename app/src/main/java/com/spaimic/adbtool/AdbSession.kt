package com.spaimic.adbtool

import java.util.concurrent.atomic.AtomicBoolean

/** The ADB connection shared by every screen. Only one operation may use it at a time. */
object AdbSession {
    @Volatile var conn: AdbConnection? = null
    @Volatile var model: String = "device"
    val busy = AtomicBoolean(false)

    fun reason(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("Disconnected") || m.contains("device gone") ||
                m.contains("USB write failed") || m.contains("USB read failed") -> "Device Unplugged!!"
            m.contains("Timed out") -> "Device stopped responding"
            else -> m
        }
    }
}
