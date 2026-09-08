// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu

import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import org.citra.citra_emu.utils.DirectoryInitialization
import org.citra.citra_emu.utils.DocumentsTree
import org.citra.citra_emu.utils.GraphicsUtil
import org.citra.citra_emu.utils.Log
import org.citra.citra_emu.utils.MemoryUtil
import org.citra.citra_emu.utils.PermissionsHandler

class CitraApplication : Application() {
    private fun createNotificationChannel() {
        with(getSystemService(NotificationManager::class.java)) {
            // General notification
            val name: CharSequence = getString(R.string.app_notification_channel_name)
            val description = getString(R.string.app_notification_channel_description)
            val generalChannel = NotificationChannel(
                getString(R.string.app_notification_channel_id),
                name,
                NotificationManager.IMPORTANCE_LOW
            )
            generalChannel.description = description
            generalChannel.setSound(null, null)
            generalChannel.vibrationPattern = null
            createNotificationChannel(generalChannel)

            // CIA Install notifications
            val ciaChannel = NotificationChannel(
                getString(R.string.cia_install_notification_channel_id),
                getString(R.string.cia_install_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            ciaChannel.description =
                getString(R.string.cia_install_notification_channel_description)
            ciaChannel.setSound(null, null)
            ciaChannel.vibrationPattern = null
            createNotificationChannel(ciaChannel)
        }
    }

    override fun onCreate() {
        super.onCreate()
        application = this
        documentsTree = DocumentsTree()
        if (PermissionsHandler.hasWriteAccess(applicationContext)) {
            DirectoryInitialization.start()
        }

        NativeLibrary.logDeviceInfo()
        logDeviceInfo()
        createNotificationChannel()
        NativeLibrary.playTimeManagerInit()
    }

    /**
     * Android asks for memory back here. Everything at or above
     * [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN] is reported once the app has left the
     * foreground (UI_HIDDEN, then BACKGROUND, MODERATE and COMPLETE as the process moves up
     * the low-memory killer's list), and a paused emulation can give up its cached GPU
     * surfaces and free heap pages there, which is what keeps a backgrounded game alive on an
     * 8 GB device. The levels below it (RUNNING_MODERATE, _LOW, _CRITICAL) arrive while the
     * game is on screen and are deliberately ignored: the release costs a hitch when the
     * surfaces are uploaded again, which is not worth paying mid-frame.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            Log.info("Trimming memory at level $level")
            NativeLibrary.trimMemory()
        }
    }

    fun logDeviceInfo() {
        Log.info("Device Manufacturer - ${Build.MANUFACTURER}")
        Log.info("Device Model - ${Build.MODEL}")
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
            Log.info("SoC Manufacturer - ${Build.SOC_MANUFACTURER}")
            Log.info("SoC Model - ${Build.SOC_MODEL}")
        }
        Log.info("Total System Memory - ${MemoryUtil.getDeviceRAM()}")
        Log.info("OpenGL ES Renderer - ${GraphicsUtil.openGLRendererString}")
    }

    companion object {
        private var application: CitraApplication? = null

        val appContext: Context get() = application!!.applicationContext

        @SuppressLint("StaticFieldLeak")
        lateinit var documentsTree: DocumentsTree
    }
}
