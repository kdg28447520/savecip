package app.saveclip.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import app.saveclip.MainActivity
import app.saveclip.R
import app.saveclip.core.download.TransferActions
import org.json.JSONObject

object HeaderCodec {
    fun encode(headers: Map<String, String>): String = JSONObject(headers).toString()

    fun decode(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val obj = JSONObject(raw)
        val out = linkedMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = obj.optString(key)
        }
        return out
    }
}

class DownloadNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            channel.description = context.getString(R.string.channel_desc)
            manager.createNotificationChannel(channel)
        }
    }

    fun progressNotification(title: String, taskId: String, fraction: Float?, detail: String): Notification {
        val open = activityIntent(taskId.hashCode() + 1, TransferActions.OPEN)
        val pause = broadcastIntent(taskId.hashCode() + 2, DownloadActionReceiver.ACTION_PAUSE, taskId)
        val cancel = broadcastIntent(taskId.hashCode() + 3, TransferActions.CANCEL, taskId)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(detail.ifBlank { context.getString(R.string.notif_running) })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.notif_pause), pause)
            .addAction(0, context.getString(R.string.notif_cancel), cancel)
            .addAction(0, context.getString(R.string.notif_open), open)
        if (fraction == null) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, (fraction * 100).toInt().coerceIn(0, 100), false)
        }
        return builder.build()
    }

    fun progressInfo(title: String, taskId: String, fraction: Float?, detail: String): ForegroundInfo {
        return asForeground(progressNotification(title, taskId, fraction, detail))
    }

    fun done(title: String) {
        notify(
            DONE_ID,
            base(title, context.getString(R.string.notif_done)).setOngoing(false).setAutoCancel(true).build(),
        )
    }

    fun failed(title: String, message: String) {
        notify(
            DONE_ID,
            base(title, message.ifBlank { context.getString(R.string.notif_failed) })
                .setOngoing(false)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun base(title: String, text: String): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    1,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
    }

    private fun notify(id: Int, notification: Notification) {
        manager.notify(id, notification)
    }

    private fun activityIntent(requestCode: Int, action: String): PendingIntent {
        return PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun broadcastIntent(requestCode: Int, action: String, taskId: String): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, DownloadActionReceiver::class.java).apply {
                this.action = action
                putExtra(DownloadActionReceiver.EXTRA_ID, taskId)
                setPackage(context.packageName)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun asForeground(notification: Notification): ForegroundInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PROGRESS_ID, notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "saveclip.downloads"
        const val PROGRESS_ID = 4101
        const val DONE_ID = 4102
    }
}
