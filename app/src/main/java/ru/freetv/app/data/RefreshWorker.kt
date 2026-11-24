package ru.freetv.app.data

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

/**
 * Раз в сутки обновляет плейлист и EPG. Ручное обновление вызывает те же методы из UI.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val prefs = ru.freetv.app.util.Prefs(applicationContext)
            val repository = ChannelRepository(applicationContext)
            val catalog = repository.load(forceRefresh = true)
            CatalogStore.set(catalog)
            Epg.refresh(applicationContext, catalog.allChannels, force = true)
            Result.success()
        } catch (error: Exception) {
            Result.retry()
        }
    }
}

object RefreshScheduler {

    private const val WORK_NAME = "antontv-daily-refresh"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
