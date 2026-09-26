package de.gunvald.muzei.apod

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import de.gunvald.muzei.apod.net.ApodEngine
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

class ApodArtProvider : MuzeiArtProvider() {

    override fun onLoadRequested(initial: Boolean) {
        val context = context ?: return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val immediateWork = OneTimeWorkRequestBuilder<ApodWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "ApodWorkerSync",
            ExistingWorkPolicy.REPLACE,
            immediateWork
        )

        val periodicWork = PeriodicWorkRequestBuilder<ApodWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "ApodWorkerPeriodic",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWork
        )
    }

    override fun openFile(artwork: Artwork): InputStream {
        val targetUri = artwork.persistentUri ?: throw IOException("Artwork URI is null")
        var request = Request.Builder()
            .url(targetUri.toString())
            .header("User-Agent", "Mozilla/5.0 (Android; Linux)")
            .build()

        var response = ApodEngine.client.newCall(request).execute()

        // Stream-level 403 fallback protection
        if (response.code == 403 && targetUri.toString().endsWith(".jpg", true)) {
            val fallbackUrl = targetUri.toString().replace(".jpg", "1024.jpg", true)
            request = Request.Builder()
                .url(fallbackUrl)
                .header("User-Agent", "Mozilla/5.0 (Android; Linux)")
                .build()
            response = ApodEngine.client.newCall(request).execute()
        }

        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code}: Failed to stream image payload")
        }

        return response.body?.byteStream() ?: throw IOException("Received empty response body")
    }
}
