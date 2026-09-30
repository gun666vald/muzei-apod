package de.gunvald.muzei.apod.net

import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
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
    private const val JSON_PRIMARY_URL = "https://science.nasa.gov/wp-json/wp/v2/apod-basic?page=1&per_page=5"
    private const val JSON_BACKUP_URL = "https://science.nasa.gov/wp-json/wp/v2/apod-basic"
    private const val WEB_LANDING_URL = "https://science.nasa.gov/apod/"

    val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun resolveLatestPhoto(): ApodPayload? {
        // Strategy 1: Official REST API with 5-day sliding window (Fastest, zero scraping fragility)
        try {
            val payload = fetchFromScienceJson(JSON_PRIMARY_URL)
            if (payload != null) return payload
        } catch (e: Exception) {
            Log.w(TAG, "Primary JSON API failed (${e.message}). Trying backup JSON route...", e)
        }

        // Strategy 2: Unpaginated REST endpoint
        try {
            val payload = fetchFromScienceJson(JSON_BACKUP_URL)
            if (payload != null) return payload
        } catch (e: Exception) {
            Log.w(TAG, "Backup JSON API failed (${e.message}). Falling back to dynamic HTML parsing.", e)
        }

        // Strategy 3: Dynamic HTML parsing (JSON-LD -> Hero Anchor -> OpenGraph)
        return fetchFromDynamicHtml(WEB_LANDING_URL)
    }

    private fun fetchFromScienceJson(url: String): ApodPayload? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android; Linux)")
            .header("Accept", "application/json")
            .build()

        val jsonString = client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            res.body?.string()
        } ?: throw IOException("Empty response body")

        val array = JSONArray(jsonString)
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val mediaType = item.optString("media_type", "image")

            // Automatically step back past video / iframe days
            if (mediaType != "image") continue

            val hdUrl = item.optString("hdurl")
            val fallbackUrl = item.optString("url")
            val rawTargetUrl = if (hdUrl.isNotBlank()) hdUrl else fallbackUrl

            if (rawTargetUrl.isNotBlank()) {
                val title = cleanHtml(item.optString("title", "Astronomy Picture of the Day"))
                val date = item.optString("date", "")
                val permalink = item.optString("permalink", WEB_LANDING_URL)
                val explanation = cleanHtml(item.optString("explanation", ""))
                val rawCopyright = item.optString("copyright", item.optString("credit", "NASA Science"))
                val author = cleanHtml(rawCopyright).ifBlank { "NASA Science" }

                val normalizedUrl = normalizeUrl(rawTargetUrl)
                val highResCdnUrl = optimizeCdnUrl(normalizedUrl)

                Log.d(TAG, "Resolved APOD ($date): $title -> $highResCdnUrl")

                return ApodPayload(
                    token = if (date.isNotBlank()) date else permalink,
                    title = title,
                    author = author,
                    description = explanation,
                    imageUri = Uri.parse(highResCdnUrl),
                    webUri = Uri.parse(permalink)
                )
            }
        }
        return null
    }

    private fun fetchFromDynamicHtml(url: String): ApodPayload? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; Linux)")
                .build()

            val html = client.newCall(req).execute().use { it.body?.string() } ?: return null

            // 1. Check JSON-LD Schema: "primaryImageOfPage":{"@id":"..."} or "ImageObject"
            var candidateUrl: String? = null
            val jsonLdMatcher = Pattern.compile(
                "\"@type\"\\s*:\\s*\"ImageObject\"[^\"]*\"url\"\\s*:\\s*\"([^\"]+)\"",
                Pattern.CASE_INSENSITIVE
            ).matcher(html)
            if (jsonLdMatcher.find()) {
                candidateUrl = jsonLdMatcher.group(1)
            }

            // 2. Check Hero <figure class="...hds-media-inner..."> <a href="...">
            if (candidateUrl == null) {
                val heroAnchorMatcher = Pattern.compile(
                    "<figure[^>]*class=[\"'][^\"']*hds-media-inner[^\"']*[\"'][^>]*>\\s*<a\\s+href=[\"']([^\"']+)[\"']",
                    Pattern.CASE_INSENSITIVE
                ).matcher(html)
                if (heroAnchorMatcher.find()) {
                    candidateUrl = heroAnchorMatcher.group(1)
                }
            }

            // 3. Check OpenGraph <meta property="og:image" content="...">
            if (candidateUrl == null) {
                val ogMatcher = Pattern.compile(
                    "<meta\\s+property=[\"']og:image[\"']\\s+content=[\"']([^\"']+)[\"']",
                    Pattern.CASE_INSENSITIVE
                ).matcher(html)
                if (ogMatcher.find()) {
                    candidateUrl = ogMatcher.group(1)
                }
            }

            if (candidateUrl == null) return null

            // Extract Title from <h1 class="...display-48..."> or <title>
            val titleMatcher = Pattern.compile("<h1[^>]*>([^<]+)</h1>", Pattern.CASE_INSENSITIVE).matcher(html)
            val title = if (titleMatcher.find()) cleanHtml(titleMatcher.group(1) ?: "APOD") else "APOD"

            val normalizedUrl = normalizeUrl(candidateUrl)
            val highResUrl = optimizeCdnUrl(normalizedUrl)

            Log.d(TAG, "HTML Extractor Match: $title -> $highResUrl")

            ApodPayload(
                token = System.currentTimeMillis().toString(),
                title = title,
                author = "NASA Science",
                description = "NASA Astronomy Picture of the Day",
                imageUri = Uri.parse(highResUrl),
                webUri = Uri.parse(url)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Dynamic HTML extraction failed", e)
            null
        }
    }

    /**
     * Decodes HTML entities in URLs (&#038; and &amp; -> &)
     */
    private fun normalizeUrl(rawUrl: String): String {
        return rawUrl.replace("&#038;", "&")
            .replace("&amp;", "&")
            .trim()
    }

    /**
     * Optimizes NASA Akamai/Cloudflare dynamic image parameters to 4K UHD bounds
     */
    private fun optimizeCdnUrl(url: String): String {
        return if (url.contains("assets.science.nasa.gov/dynamicimage")) {
            url.replace(Regex("w=\\d+"), "w=3840")
               .replace(Regex("h=\\d+"), "h=2160")
        } else {
            url
        }
    }

    private fun cleanHtml(text: String): String {
        return text.replace(Regex("<[^>]*>"), "")
            .replace("&#038;", "&")
            .replace("&amp;", "&")
            .replace("&#8211;", "–")
            .replace("&#8212;", "—")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .trim()
    }
}
