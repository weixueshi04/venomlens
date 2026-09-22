package com.insta360.kmpsdk.demo.service

import android.app.Notification
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
import com.insta360.kmpsdk.demo.MainActivity
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.service.CameraSessionForegroundService.Companion.ACTION_STOP
import timber.log.Timber

/**
 * 应用进程存活期间常驻的前台服务，降低进入系统界面（如文件选择器）时进程被收紧导致相机连接中断的概率。
 */
class CameraSessionForegroundService : Service() {
    private var foregroundActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Timber.d("CameraSessionForegroundService onCreate")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            teardownAndStop()
            return START_NOT_STICKY
        }
        ensureChannel()
        startForegroundWithType(buildNotification())
        foregroundActive = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeForegroundIfNeeded()
        Timber.d("CameraSessionForegroundService onDestroy")
        super.onDestroy()
    }

    private fun teardownAndStop() {
        removeForegroundIfNeeded()
        stopSelf()
    }

    private fun removeForegroundIfNeeded() {
        if (!foregroundActive) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        foregroundActive = false
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.fg_service_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                setShowBadge(false)
                description = getString(R.string.fg_service_channel_description)
            }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val b =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_camera_connection)
                .setContentTitle(getString(R.string.fg_service_notification_title))
                .setContentText(getString(R.string.fg_service_notification_text))
                .setContentIntent(openApp)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            b.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "camera_session_keep_alive"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_STOP =
            "com.insta360.kmpsdk.demo.action.STOP_CAMERA_SESSION_FOREGROUND"

        fun start(context: Context) {
            val intent = Intent(context, CameraSessionForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * 结束前台会话并移除通知。
         * 优先向运行中的实例派发 [ACTION_STOP]（先 [stopForeground] 再 [stopSelf]），避免仅
         * [Context.stopService] 在部分机型/版本上前台通知残留或停止不生效的问题。
         */
        fun stop(context: Context) {
            val cls = CameraSessionForegroundService::class.java
            val stopIntent =
                Intent(context, cls).apply {
                    action = ACTION_STOP
                }
            try {
                context.startService(stopIntent)
            } catch (e: IllegalStateException) {
                Timber.w(e, "stop: startService(ACTION_STOP) not allowed, fallback stopService")
                context.stopService(Intent(context, cls))
            }
        }
    }
}
