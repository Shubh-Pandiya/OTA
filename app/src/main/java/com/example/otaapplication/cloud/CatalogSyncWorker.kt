package com.example.otaapplication.cloud

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Background catalog sync, every 15 minutes (Android's minimum) while signed in and online. */
class CatalogSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        when (CloudSync.get(applicationContext).syncNow()) {
            SyncOutcome.OFFLINE, SyncOutcome.FAILED ->
                if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
            SyncOutcome.SUCCESS, SyncOutcome.SKIPPED, SyncOutcome.SIGNED_OUT -> Result.success()
        }

    companion object {
        const val UNIQUE_NAME = "catalog-sync"
        private const val MAX_RETRIES = 3

        /** Idempotent: keeps the existing schedule if one is already queued. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CatalogSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
