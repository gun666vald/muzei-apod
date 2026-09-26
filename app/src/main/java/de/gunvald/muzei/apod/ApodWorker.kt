package de.gunvald.muzei.apod

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.gunvald.muzei.apod.net.ApodEngine
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.ProviderContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ApodWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ApodWorker"
        private const val AUTHORITY = "de.gunvald.muzei.apod"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val item = ApodEngine.resolveLatestPhoto() ?: return@withContext Result.retry()

            val providerClient = ProviderContract.getProviderClient(applicationContext, AUTHORITY)

            val currentArtwork = providerClient.lastAddedArtwork
            if (currentArtwork != null && currentArtwork.token == item.token) {
                Log.d(TAG, "Token matches current wallpaper (${item.token}). Skipping.")
                return@withContext Result.success()
            }

            val artwork = Artwork.Builder()
                .token(item.token)
                .title(item.title)
                .byline(item.author)
                .attribution(item.token)
                .persistentUri(item.imageUri)
                .webUri(item.webUri)
                .build()

            providerClient.setArtwork(artwork)
            Log.d(TAG, "Injected artwork into Muzei: ${item.title}")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Work execution failure", e)
            Result.retry()
        }
    }
}
