/*
 * CubeCut - cuts a speedcubing session video into one clip per solve.
 * Copyright (C) 2026 DadCuber
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, version 3 of the License,
 * with the additional terms in NOTICE.md (GPL section 7): the author credit must stay in the app's
 * About window, and changed versions must be marked as changed.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program (the
 * LICENSE file). If not, see <https://www.gnu.org/licenses/>.
 */
package com.example.cubecut

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * A "foreground service": it does no work itself. Its only job is to show a notification while Find solves is running, which tells
 * Android "this app is busy, please don't put it to sleep" - so the work keeps going when you switch to another app.
 * The progress bar is set with update(context, percent, 100).
 * The actual work still happens in MainActivity (runFindSolves).
 *
 * Android asks what kind of work it is. Reading a video is "media processing" (the right label, which Google Play checks), but that
 * label only exists from Android 15; on Android 11 to 14 it is "data sync" (the closest one there). Both must be listed for the
 * service in AndroidManifest.xml, each with its own permission. From Android 15 either kind may run about 6 hours a day at most;
 * when that runs out Android calls onTimeout, and the service stops itself (Find solves then only keeps going while CubeCut is open).
 */
class AnalysisService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannels(this)
        try {
            val notification = buildProgressNotification(this, 0, 0)
            val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(NOTIFICATION_ID, notification, type)
            running = true
        } catch (e: Exception) {
            // if Android refuses, Find solves still works - it just won't survive in the background
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // Android 15+: the day's time for background work is used up. The service must stop within seconds, or Android closes the app.
    override fun onTimeout(startId: Int, fgsType: Int) {
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val PROGRESS_CHANNEL = "cubecut_progress"
        private const val DONE_CHANNEL = "cubecut_done"
        private const val NOTIFICATION_ID = 1
        private const val DONE_NOTIFICATION_ID = 2

        @Volatile
        private var running = false

        private fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(PROGRESS_CHANNEL, "Find solves progress", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(DONE_CHANNEL, "Find solves finished", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        private fun openAppIntent(context: Context): PendingIntent {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
            return PendingIntent.getActivity(
                context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        private fun buildProgressNotification(context: Context, done: Int, total: Int) =
            NotificationCompat.Builder(context, PROGRESS_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Finding solves")
                .setContentText(if (total > 0) "Reading the timer: ${done * 100 / total}%" else "Starting...")
                .setProgress(total, done, total == 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(openAppIntent(context))
                .build()

        /** Call right before the work starts. */
        fun start(context: Context) {
            try {
                ensureChannels(context)
                ContextCompat.startForegroundService(context, Intent(context, AnalysisService::class.java))
            } catch (e: Exception) {
                // not allowed right now - carry on without it
            }
        }

        /** Call as the work progresses: updates the progress bar in the notification. */
        fun update(context: Context, done: Int, total: Int) {
            if (!running) return
            try {
                context.getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, buildProgressNotification(context, done, total))
            } catch (e: Exception) {
            }
        }

        /** Call when the work is over (finished, failed or cancelled). */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, AnalysisService::class.java))
            } catch (e: Exception) {
            }
        }

        /** A normal notification saying the work is finished (tap it to open CubeCut). */
        fun notifyDone(context: Context, text: String) {
            try {
                ensureChannels(context)
                val n = NotificationCompat.Builder(context, DONE_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle("Find solves finished")
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(openAppIntent(context))
                    .build()
                context.getSystemService(NotificationManager::class.java)?.notify(DONE_NOTIFICATION_ID, n)
            } catch (e: Exception) {
            }
        }
    }
}