package app.saveclip.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.saveclip.AppContainer

class DownloadWorker(
    context: Context,
    params: WorkerParameters,
    private val container: AppContainer,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val notifier = DownloadNotifier(applicationContext)
        val session = DownloadSession(
            context = applicationContext,
            container = container,
            notifier = notifier,
            publish = { notification -> setForeground(notifier.asForeground(notification)) },
        )
        return when (session.run(inputData.getString(KEY_ID), runAttemptCount)) {
            SessionOutcome.Success, SessionOutcome.Skipped -> Result.success()
            SessionOutcome.Retry -> Result.retry()
            SessionOutcome.Failure -> Result.failure()
        }
    }

    companion object {
        const val KEY_ID = "taskId"
    }
}
