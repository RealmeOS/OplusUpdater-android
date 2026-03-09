package com.houvven.oplusupdater.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import com.houvven.oplusupdater.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object UrlDecryptUtil {

    suspend fun resolveUrl(originalUrl: String): String = withContext(Dispatchers.IO) {
        if (!originalUrl.contains("downloadCheck")) {
            return@withContext originalUrl
        }

        var currentUrl = URL(originalUrl)
        var redirectCount = 0
        val maxRedirects = 10

        while (redirectCount++ < maxRedirects) {
            val conn = (currentUrl.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 15000
                requestMethod = "GET"

                val headers = buildHeaders(currentUrl)
                headers.forEach { (key, value) ->
                    setRequestProperty(key, value)
                }
            }

            try {
                val code = conn.responseCode
                when {
                    code in 300..399 -> {
                        val location = conn.getHeaderField("Location")
                            ?: throw IOException("Redirect without Location header")

                        val nextUrl = URL(currentUrl, location)
                        conn.disconnect()

                        if (!location.contains("downloadCheck")) {
                            return@withContext location
                        }

                        currentUrl = nextUrl
                    }

                    code == 200 -> {
                        val finalUrl = conn.url.toString()
                        conn.disconnect()
                        return@withContext finalUrl
                    }

                    else -> {
                        conn.disconnect()
                        throw IOException("Unexpected response code: $code")
                    }
                }
            } catch (e: Exception) {
                conn.disconnect()
                throw e
            }
        }

        throw IOException("Too many redirects")
    }

    private fun buildHeaders(url: URL): Map<String, String> {
        val id = extractIdFromUrl(url)

        return buildMap {
            put(
                "language", getSystemProperty("persist.sys.locale")
                    ?: Locale.getDefault().toString()
            )
            put("androidVersion", "Android ${Build.VERSION.RELEASE}")
            put("colorOSVersion", buildColorOSVersion())
            getSystemProperty("ro.build.version.ota")?.let {
                put("otaVersion", it)
            }
            put("model", getSystemProperty("ro.product.name") ?: Build.MODEL)
            put("mode", getSystemProperty("sys.ota.test") ?: "0")
            getSystemProperty("ro.build.oplus_nv_id")?.let {
                put("nvCarrier", it)
            }
            put("brand", Build.BRAND)
            getSystemProperty("ro.oplus.image.my_stock.type")?.let {
                put("osType", it)
            }
            val operator = getSystemProperty("persist.sys.channel.info")
                ?: getSystemProperty("ro.oplus.pipeline.carrier")
                ?: "default"
            put("operator", operator)
            getSystemProperty("ro.separate.soft")?.let {
                put("prjNum", it)
            }
            if (id.isNotEmpty()) {
                put("id", id)
            }
            put("ts", System.currentTimeMillis().toString())
            put("userId", "oplus-ota|16000023")
        }
    }

    private fun extractIdFromUrl(url: URL): String {
        val query = url.query ?: return ""
        return query.split("&")
            .firstOrNull { it.startsWith("g=") }
            ?.substringAfter("g=")
            ?: ""
    }

    private fun buildColorOSVersion(): String {
        val version = getSystemProperty("ro.build.version.oplusrom") ?: ""
        return "ColorOS${version.replace("V", "")}"
    }

    @SuppressLint("PrivateApi")
    private fun getSystemProperty(key: String, default: String? = null): String? {
        return try {
            val systemProperties = Class.forName("android.os.SystemProperties")
            val getMethod =
                systemProperties.getMethod("get", String::class.java, String::class.java)
            val result = getMethod.invoke(null, key, default ?: "") as? String
            result?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            default
        }
    }

    fun extractExpiresTimestamp(url: String): Long? {
        return try {
            val uri = URL(url)
            uri.query?.split("&")
                ?.firstOrNull { it.startsWith("Expires=") }
                ?.substringAfter("Expires=")
                ?.toLongOrNull()
        } catch (e: Exception) {
            null
        }
    }

    fun formatRemainingTime(expiresTimestamp: Long, context: Context): String {
        val now = System.currentTimeMillis() / 1000
        val remaining = expiresTimestamp - now

        if (remaining <= 0) {
            return context.getString(R.string.url_expired)
        }

        val hours = remaining / 3600
        val minutes = (remaining % 3600) / 60
        val seconds = remaining % 60

        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }
}