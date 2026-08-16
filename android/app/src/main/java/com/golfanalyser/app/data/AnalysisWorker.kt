package com.golfanalyser.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

class AnalysisWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val runId = inputData.getString(KEY_RUN_ID) ?: return Result.failure()
        val workContext = currentCoroutineContext()
        return try {
            AnalysisRepository(applicationContext).runReservedAnalysis(runId) {
                isStopped || !workContext.isActive
            }
            Result.success()
        } catch (_: kotlinx.coroutines.CancellationException) {
            Result.failure(Data.Builder().putString("reason", "cancelled").build())
        } catch (throwable: Throwable) {
            Result.failure(Data.Builder().putString("reason", throwable.message).build())
        }
    }

    companion object {
        const val KEY_RUN_ID = "run_id"
        const val TAG_ANALYSIS = "golf_analysis"
        fun uniqueName(runId: String) = "golf_analysis_$runId"
    }
}

class AnalysisWorkCoordinator(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun enqueue(runId: String) {
        val request = OneTimeWorkRequestBuilder<AnalysisWorker>()
            .setInputData(Data.Builder().putString(AnalysisWorker.KEY_RUN_ID, runId).build())
            .addTag(AnalysisWorker.TAG_ANALYSIS)
            .addTag(runId)
            .build()
        workManager.enqueueUniqueWork(
            AnalysisWorker.uniqueName(runId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(runId: String) {
        workManager.cancelUniqueWork(AnalysisWorker.uniqueName(runId))
    }
}
