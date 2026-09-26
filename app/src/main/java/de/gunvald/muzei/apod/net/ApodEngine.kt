package de.gunvald.muzei.apod.net

import android.net.Uri
import android.util.Log
import android.util.Xml
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class ApodPayload(
    val token: String,
    val title: String,
    val author: String,
    val description: String,
    val imageUri: Uri,
    val webUri: Uri
)

object ApodEngine {
    private const val TAG = "ApodEngine"
    private const val RSS_URL = "https://science.nasa.gov/feed/apod-basic/"
    private const val OLD_BASE = "https://apod.nasa.gov/apod/"
    private const val OLD_ARCHIVE = "https://apod.nasa.gov/apod/archivepix.html"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun resolveLatestPhoto(): ApodPayload? {
        // Priority 1: Modern NASA Science CDN / RSS Feed
        try {
            val payload = fetchFromScienceRss()
            if (payload != null) return payload
        } catch (e: Exception) {
            Log.w(TAG, "NASA Science RSS failed (${e.message}). Falling back to legacy archive.", e)
        }

        // Priority 2: Legacy archive scraper with 403-Forbidden recovery
        return fetchFromLegacyArchive()
    }

    private fun fetchFromScienceRss(): ApodPayload? {
        val req = Request.Builder()
            .url(RSS_URL)
            .header("User-Agent", "Mozilla/5.0 (Android; Linux)")
            .build()

        val xmlString = client.newCall(req).execute().use { it.body?.string() }
            ?: throw IOException("Empty RSS response")

        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xmlString))

        var eventType = parser.eventType
        var currentTitle = ""
        var currentLink = ""
        var currentImg = ""
        var currentGuid = ""

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (name) {
                        "item" -> {
                            currentTitle = ""
                            currentLink = ""
                            currentImg = ""
                            currentGuid = ""
                        }
                        "title" -> currentTitle = parser.nextText().trim()
                        "link" -> currentLink = parser.nextText().trim()
                        "guid" -> currentGuid = parser.nextText().trim()
                        "enclosure" -> {
                            val type = parser.getAttributeValue(null, "type") ?: ""
                            val url = parser.getAttributeValue(null, "url") ?: ""
                            if (type.startsWith("image/") || url.contains(".jpg", true) || url.contains(".png", true)) {
                                currentImg = url
                            }
                        }
                        "description" -> {
                            if (currentImg.isBlank()) {
                                val desc = parser.nextText()
                                val imgMatcher = Pattern.compile("src=[\"'](https://assets\\.science\\.nasa\\.gov/[^\"']+)[\"']").matcher(desc)
                                if (imgMatcher.find()) {
                                    currentImg = imgMatcher.group(1) ?: ""
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (name == "item" && currentImg.isNotBlank()) {
                        val highResCdnUrl = optimizeCdnUrl(currentImg)
                        Log.d(TAG, "RSS Match: $currentTitle -> $highResCdnUrl")

                        return ApodPayload(
                            token = if (currentGuid.isNotBlank()) currentGuid else currentTitle,
                            title = currentTitle,
                            author = "NASA Science",
                            description = "Astronomy Picture of the Day",
                            imageUri = Uri.parse(highResCdnUrl),
                            webUri = Uri.parse(if (currentLink.isNotBlank()) currentLink else "https://science.nasa.gov/apod/")
                        )
                    }
                }
            }
            eventType = parser.next()
        }
        return null
    }

    private fun fetchFromLegacyArchive(): ApodPayload? {
        return try {
            val archiveReq = Request.Builder()
                .url(OLD_ARCHIVE)
                .header("User-Agent", "Mozilla/5.0 (Android; Linux)")
                .build()

            val archiveHtml = client.newCall(archiveReq).execute().use { it.body?.string() } ?: return null

            val pageMatcher = Pattern.compile("href=\"(ap\\d{6}\\.html)\"", Pattern.CASE_INSENSITIVE).matcher(archiveHtml)
            val candidatePages = mutableListOf<String>()
            while (pageMatcher.find() && candidatePages.size < 7) {
                candidatePages.add(pageMatcher.group(1)!!)
            }

            for (page in candidatePages) {
                val pageReq = Request.Builder()
                    .url(OLD_BASE + page)
                    .header("User-Agent", "Mozilla/5.0 (Android; Linux)")
                    .build()

                val pageHtml = client.newCall(pageReq).execute().use { it.body?.string() } ?: continue

                val fullResMatcher = Pattern.compile("<a\\s+href=\"(image/[^\"]+\\.(?:jpg|jpeg|png))\"", Pattern.CASE_INSENSITIVE).matcher(pageHtml)
                val previewMatcher = Pattern.compile("<img\\s+src=\"(image/[^\"]+\\.(?:jpg|jpeg|png))\"", Pattern.CASE_INSENSITIVE).matcher(pageHtml)

                var chosenUrl: String? = null

                if (fullResMatcher.find()) {
                    val candidate = OLD_BASE + fullResMatcher.group(1)
                    if (isUrlAccessible(candidate)) {
                        chosenUrl = candidate
                    }
                }

                // If master file is 403 Forbidden, automatically fall back to 1024px preview
                if (chosenUrl == null && previewMatcher.find()) {
                    chosenUrl = OLD_BASE + previewMatcher.group(1)
                    Log.w(TAG, "Master full-res returned 403; recovered with preview: $chosenUrl")
                }

                if (chosenUrl != null) {
                    val titleMatcher = Pattern.compile("<b>\\s*([^<]+)\\s*</b>", Pattern.CASE_INSENSITIVE).matcher(pageHtml)
                    val title = if (titleMatcher.find()) titleMatcher.group(1)?.trim() ?: "NASA APOD" else "NASA APOD"

                    return ApodPayload(
                        token = page,
                        title = title,
                        author = "NASA APOD",
                        description = "Astronomy Picture of the Day",
                        imageUri = Uri.parse(chosenUrl),
                        webUri = Uri.parse(OLD_BASE + page)
                    )
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Legacy archive fallback failed", e)
            null
        }
    }

    private fun optimizeCdnUrl(url: String): String {
        return if (url.contains("assets.science.nasa.gov/dynamicimage")) {
            url.replace(Regex("w=\\d+"), "w=3840")
               .replace(Regex("h=\\d+"), "h=2160")
        } else {
            url
        }
    }

    private fun isUrlAccessible(url: String): Boolean {
        return try {
            val req = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "Mozilla/5.0 (Android; Linux)")
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }
}
