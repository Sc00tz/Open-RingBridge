package dev.ringbridge

import android.content.Context

/** Thin wrapper around SharedPreferences — single source of truth for all user settings. */
object Settings {

    private const val PREFS = "ringbridge"

    // ── Server connection ─────────────────────────────────────────────────────

    fun serverUrl(ctx: Context)   = prefs(ctx).getString("server_url",   "") ?: ""
    fun deviceToken(ctx: Context) = prefs(ctx).getString("device_token", "") ?: ""
    fun deviceId(ctx: Context)    = prefs(ctx).getString("device_id",    "") ?: ""

    fun setServerUrl(ctx: Context,   v: String) = prefs(ctx).edit().putString("server_url",   v).apply()
    fun setDeviceToken(ctx: Context, v: String) = prefs(ctx).edit().putString("device_token", v).apply()
    fun setDeviceId(ctx: Context,    v: String) = prefs(ctx).edit().putString("device_id",    v).apply()

    /** True when the server is configured and the app has credentials. */
    fun isConfigured(ctx: Context) = serverUrl(ctx).isNotEmpty() && deviceToken(ctx).isNotEmpty()

    // ── Reconnect behaviour ───────────────────────────────────────────────────

    fun reconnectSilent(ctx: Context) = prefs(ctx).getBoolean("reconnect_silent", true)
    fun setReconnectSilent(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean("reconnect_silent", v).apply()

    // ── Sync policy ───────────────────────────────────────────────────────────

    /** When true, only sync to the server while on Wi-Fi. */
    fun wifiOnly(ctx: Context) = prefs(ctx).getBoolean("wifi_only", false)
    fun setWifiOnly(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean("wifi_only", v).apply()

    /**
     * How often to flush buffered readings to the server (milliseconds).
     * 0 = real-time (flush on every reading).
     */
    fun syncIntervalMs(ctx: Context) = prefs(ctx).getLong("sync_interval_ms", 0L)
    fun setSyncIntervalMs(ctx: Context, ms: Long) =
        prefs(ctx).edit().putLong("sync_interval_ms", ms).apply()

    // ── Goals ─────────────────────────────────────────────────────────────────

    fun stepsGoal(ctx: Context) = prefs(ctx).getInt("steps_goal", 10_000)
    fun setStepsGoal(ctx: Context, v: Int) = prefs(ctx).edit().putInt("steps_goal", v).apply()

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
