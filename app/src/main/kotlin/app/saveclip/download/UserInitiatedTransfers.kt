package app.saveclip.download

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import java.io.File

object UserInitiatedTransfers {
    private const val PREFS = "saveclip_transfer_jobs"
    private const val NEXT = "next"
    const val EXTRA_TASK = "taskId"

    fun schedule(context: Context, taskId: String, wifiOnly: Boolean, bytesTotal: Long): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val jobId = assign(context, taskId)
        val extras = PersistableBundle()
        extras.putString(EXTRA_TASK, taskId)
        val network = if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY
        val unknown = JobInfo.NETWORK_BYTES_UNKNOWN.toLong()
        val downloadBytes = if (bytesTotal > 0L) bytesTotal else unknown
        val info = JobInfo.Builder(jobId, ComponentName(context, DownloadTransferService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(network)
            .setEstimatedNetworkBytes(downloadBytes, unknown)
            .setExtras(extras)
            .build()
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        return scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
    }

    fun cancel(context: Context, taskId: String) {
        val jobId = jobIdOf(context, taskId) ?: return
        context.getSystemService(JobScheduler::class.java)?.cancel(jobId)
    }

    fun forget(context: Context, taskId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(taskId).apply()
    }

    fun notificationId(context: Context, taskId: String): Int {
        val jobId = jobIdOf(context, taskId) ?: (taskId.hashCode() and 0xffff)
        return 20_000 + (jobId % 10_000)
    }

    fun readAttempt(context: Context, taskId: String): Int {
        val file = File(context.filesDir, "work/$taskId/job-attempts")
        return file.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0
    }

    fun bumpAttempt(context: Context, taskId: String) {
        val file = File(context.filesDir, "work/$taskId/job-attempts")
        file.parentFile?.mkdirs()
        val next = readAttempt(context, taskId) + 1
        file.writeText(next.toString())
    }

    private fun assign(context: Context, taskId: String): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getInt(taskId, -1)
        if (current > 0) return current
        synchronized(this) {
            val again = prefs.getInt(taskId, -1)
            if (again > 0) return again
            val next = prefs.getInt(NEXT, 1000)
            prefs.edit().putInt(taskId, next).putInt(NEXT, next + 1).commit()
            return next
        }
    }

    private fun jobIdOf(context: Context, taskId: String): Int? {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(taskId, -1)
        return value.takeIf { it > 0 }
    }
}
