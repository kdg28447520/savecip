package app.saveclip.download

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import app.saveclip.R
import app.saveclip.SaveClipApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class DownloadTransferService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<Int, Job>()
    private val paramsJobStop = ConcurrentHashMap<Int, AtomicBoolean>()

    override fun onStartJob(params: JobParameters): Boolean {
        val taskId = params.extras.getString(UserInitiatedTransfers.EXTRA_TASK)
        if (taskId.isNullOrBlank()) {
            jobFinished(params, false)
            return false
        }
        val app = application as? SaveClipApp ?: run {
            jobFinished(params, false)
            return false
        }
        val notifier = DownloadNotifier(this)
        val notificationId = UserInitiatedTransfers.notificationId(this, taskId)
        setNotification(
            params,
            notificationId,
            notifier.progressNotification(
                getString(R.string.notif_preparing),
                taskId,
                null,
                getString(R.string.notif_preparing),
            ),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
        val stopped = AtomicBoolean(false)
        paramsJobStop[params.jobId] = stopped
        val job = scope.launch {
            val session = DownloadSession(
                context = applicationContext,
                container = app.container,
                notifier = notifier,
                publish = { notification ->
                    if (!stopped.get()) {
                        setNotification(
                            params,
                            notificationId,
                            notification,
                            JOB_END_NOTIFICATION_POLICY_REMOVE,
                        )
                    }
                },
            )
            val outcome = try {
                session.run(taskId, UserInitiatedTransfers.readAttempt(applicationContext, taskId))
            } catch (cancelled: CancellationException) {
                return@launch
            }
            if (!stopped.compareAndSet(false, true)) return@launch
            when (outcome) {
                SessionOutcome.Retry -> {
                    UserInitiatedTransfers.bumpAttempt(applicationContext, taskId)
                    jobFinished(params, true)
                }
                else -> jobFinished(params, false)
            }
        }
        running[params.jobId] = job
        job.invokeOnCompletion {
            running.remove(params.jobId, job)
            paramsJobStop.remove(params.jobId, stopped)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        paramsJobStop.remove(params.jobId)?.set(true)
        running.remove(params.jobId)?.cancel()
        val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) params.stopReason else 0
        return reason == JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY ||
            reason == JobParameters.STOP_REASON_TIMEOUT ||
            reason == JobParameters.STOP_REASON_PREEMPT ||
            reason == JobParameters.STOP_REASON_QUOTA ||
            reason == JobParameters.STOP_REASON_DEVICE_STATE
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
