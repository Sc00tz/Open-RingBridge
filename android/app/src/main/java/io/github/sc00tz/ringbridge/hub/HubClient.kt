package io.github.sc00tz.ringbridge.hub

import android.content.Context
import android.os.Build
import io.github.sc00tz.ringbridge.data.Store
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HubError(message: String) : Exception(message)

/** The optional self-hosted hub. The app works fully without one; with one, it uploads what it reads. */
class HubClient(context: Context) {
    private val prefs = context.getSharedPreferences("hub", Context.MODE_PRIVATE)

    val url: String? get() = prefs.getString("url", null)
    val username: String? get() = prefs.getString("username", null)
    private val token: String? get() = prefs.getString("token", null)
    val signedIn get() = url != null && token != null
    var lastUpload: Long
        get() = prefs.getLong("last_upload", 0)
        private set(v) = prefs.edit().putLong("last_upload", v).apply()
    var lastError: String?
        get() = prefs.getString("last_error", null)
        private set(v) = prefs.edit().putString("last_error", v).apply()

    private fun normalize(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) throw HubError("Enter the hub's address.")
        return if ("://" in trimmed) trimmed else "https://$trimmed"
    }

    private fun request(base: String, path: String, method: String, body: JSONObject?, bearer: String?): JSONObject {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            bearer?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401 && bearer != null) {
                prefs.edit().remove("token").apply()
                throw HubError("The hub signed this phone out. Sign in again.")
            }
            if (code !in 200..299) throw HubError(text.ifBlank { "The hub answered $code." })
            return JSONObject(text.ifBlank { "{}" })
        } catch (e: IOException) {
            throw HubError("Couldn't reach the hub: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    fun signIn(address: String, username: String, password: String) {
        val base = normalize(address)
        if (base.startsWith("http://") && !isPrivate(base)) {
            throw HubError("Use https:// for a hub on the internet, so your password isn't sent in the clear.")
        }
        val reply = request(base, "/api/v1/login", "POST",
            JSONObject().put("username", username.trim()).put("password", password).put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}"), null)
        prefs.edit().putString("url", base).putString("username", username.trim()).putString("token", reply.getString("token"))
            .remove("last_error").apply()
    }

    fun signOut() = prefs.edit().remove("token").remove("username").apply()

    /** Send everything queued, oldest first. Stops at the first failure and keeps the rest for next time. */
    fun flush(store: Store): Int {
        val base = url ?: return 0
        val t = token ?: return 0
        var sent = 0
        try {
            for ((id, path, body) in store.pending()) {
                request(base, path, "POST", JSONObject(body), t)
                store.uploaded(id)
                sent++
            }
            lastError = null
            if (sent > 0) lastUpload = Store.now()
        } catch (e: HubError) {
            lastError = e.message
            throw e
        }
        return sent
    }

    private fun isPrivate(base: String): Boolean {
        val host = URL(base).host
        return host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa") ||
            host.startsWith("10.") || host.startsWith("192.168.") || Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host) ||
            Regex("^100\\.(6[4-9]|[7-9]\\d|1[01]\\d|12[0-7])\\.").containsMatchIn(host)  // Tailscale
    }
}
