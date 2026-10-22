package io.github.mangi.eta.data.repository

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Opt-in periodic WebDAV sync. Android controls the actual run time (minimum interval 15 min). */
internal class EtaWebDavSyncJobService : JobService() {
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        activeJob = workScope.launch {
            val enabled = runCatching { SettingsDataStore.autoWebDavSyncEnabled() }.getOrDefault(false)
            if (enabled && EtaWebDavSyncRepository.config().configured) {
                // Best effort; conflicts are surfaced when the user opens the Memory screen.
                runCatching { EtaWebDavSyncRepository.sync() }
            }
            if (isActive) jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        activeJob?.cancel()
        activeJob = null
        return true
    }

    override fun onDestroy() {
        workScope.cancel()
        super.onDestroy()
    }
}

internal object EtaWebDavSyncScheduler {
    private const val JOB_ID = 0x45544153
    private const val PERIOD_MS = 15 * 60 * 1000L

    fun configure(context: Context, enabled: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!enabled) {
            scheduler.cancel(JOB_ID)
            return
        }
        val job = JobInfo.Builder(
            JOB_ID, ComponentName(context, EtaWebDavSyncJobService::class.java),
        ).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(PERIOD_MS)
            .setPersisted(false)
            .build()
        check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) {
            "Android JobScheduler rejected WebDAV sync job"
        }
    }
}
