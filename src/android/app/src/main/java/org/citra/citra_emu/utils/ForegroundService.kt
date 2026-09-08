// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import org.citra.citra_emu.R
import org.citra.citra_emu.activities.EmulationActivity

/**
 * A foreground service that exists only to hold a persistent notification while a game is
 * running. Android does not kill a process that owns a foreground service under ordinary memory
 * pressure or when the user backgrounds the app, so the emulation thread (paused by
 * [org.citra.citra_emu.fragments.EmulationFragment.onPause]) keeps its guest RAM, JIT cache and
 * GPU state and resumes instantly when the user comes back.
 *
 * The service holds no wake lock and does no work of its own; the screen stays on through
 * `android:keepScreenOn` on the emulation layouts, exactly as in the yuzu-lineage emulators this
 * design mirrors.
 */
class ForegroundService : Service() {
    private fun showRunningNotification() {
        // Tapping the notification returns to the running game. FLAG_ACTIVITY_CLEAR_TOP together
        // with FLAG_ACTIVITY_SINGLE_TOP routes the intent to the existing EmulationActivity via
        // onNewIntent instead of stacking a second instance on top of it; the action tells
        // onNewIntent that this is not a game launch, so the running emulation is left untouched.
        val returnIntent = Intent(this, EmulationActivity::class.java).apply {
            action = ACTION_RETURN_TO_GAME
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            returnIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification =
            NotificationCompat.Builder(this, getString(R.string.app_notification_channel_id))
                .setSmallIcon(R.drawable.ic_stat_notification_logo)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.app_notification_running))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setVibrate(null)
                .setSound(null)
                .setContentIntent(contentIntent)
                .build()
        // Android 14 requires the service type to be passed here as well as declared in the
        // manifest; ServiceCompat drops the argument on older releases.
        ServiceCompat.startForeground(
            this,
            EMULATION_RUNNING_NOTIFICATION,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        showRunningNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-posting on every start is what makes the notification appear when the
        // POST_NOTIFICATIONS permission is granted after the service is already running.
        showRunningNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(EMULATION_RUNNING_NOTIFICATION)
        super.onDestroy()
    }

    companion object {
        const val EMULATION_RUNNING_NOTIFICATION = 0x1000

        /** Action of the intent the notification sends to [EmulationActivity]. */
        const val ACTION_RETURN_TO_GAME = "org.citra.citra_emu.RETURN_TO_GAME"

        /**
         * Starts the service, or re-posts its notification if it is already running. Must be
         * called while the app is in the foreground (an Activity lifecycle callback qualifies);
         * Android 12+ refuses foreground service starts from the background, and the service is
         * best-effort, so that refusal is logged rather than allowed to take emulation down.
         */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ForegroundService::class.java))
            } catch (e: IllegalStateException) {
                Log.error("[ForegroundService] Could not start foreground service: ${e.message}")
            }
        }

        /**
         * Stops the service and removes its notification. Safe to call when the service is not
         * running, and from any context: unlike delivering a "stop" action through
         * startForegroundService, stopService never starts anything.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, ForegroundService::class.java))
        }
    }
}
