package dev.ringbridge

import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.ringbridge.databinding.ActivitySettingsBinding
import org.json.JSONException
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    // Sync interval options: label → milliseconds
    private val syncOptions = listOf(
        "Real-time"  to 0L,
        "5 minutes"  to 5  * 60_000L,
        "10 minutes" to 10 * 60_000L,
        "15 minutes" to 15 * 60_000L,
        "30 minutes" to 30 * 60_000L,
        "1 hour"     to 60 * 60_000L,
    )

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val content = result.contents ?: return@registerForActivityResult
        applyQrPayload(content)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Settings"

        // Server fields
        binding.etServerUrl.setText(Settings.serverUrl(this))
        binding.etDeviceToken.setText(Settings.deviceToken(this))
        binding.tvDeviceId.text = Settings.deviceId(this).ifEmpty { "(not set)" }

        // Goals
        binding.etStepsGoal.setText(Settings.stepsGoal(this).toString())

        // Behavior switches
        binding.switchSilentReconnect.isChecked = Settings.reconnectSilent(this)
        binding.switchWifiOnly.isChecked = Settings.wifiOnly(this)

        // Sync interval dropdown
        val labels = syncOptions.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels)
        binding.dropSyncInterval.setAdapter(adapter)

        val currentMs = Settings.syncIntervalMs(this)
        val currentIndex = syncOptions.indexOfFirst { it.second == currentMs }.coerceAtLeast(0)
        binding.dropSyncInterval.setText(labels[currentIndex], false)

        binding.btnScanQr.setOnClickListener {
            scanLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Scan the QR code from the BioLocal admin panel")
                    .setBeepEnabled(true)
                    .setBarcodeImageEnabled(false)
            )
        }

        binding.btnSave.setOnClickListener { save() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /** True if [url] is a well-formed http(s) URL with a host. */
    private fun isValidServerUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    }

    private fun applyQrPayload(raw: String) {
        try {
            val json   = JSONObject(raw)
            val server = json.optString("server", "")
            val token  = json.optString("token",  "")
            val id     = json.optString("device_id", "")

            if (server.isEmpty() || token.isEmpty()) {
                Toast.makeText(this, "Invalid QR code — missing server or token", Toast.LENGTH_LONG).show()
                return
            }
            if (!isValidServerUrl(server)) {
                Toast.makeText(this, "Invalid QR code — server must be a http(s) URL", Toast.LENGTH_LONG).show()
                return
            }

            binding.etServerUrl.setText(server)
            binding.etDeviceToken.setText(token)
            binding.tvDeviceId.text = id.ifEmpty { "(not set)" }

            Toast.makeText(this, "QR scanned — tap Save to apply", Toast.LENGTH_SHORT).show()
        } catch (e: JSONException) {
            Toast.makeText(this, "Could not read QR code: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun save() {
        val url   = binding.etServerUrl.text.toString().trim()
        val token = binding.etDeviceToken.text.toString().trim()

        if (url.isEmpty()) { binding.etServerUrl.error = "Required"; return }
        if (!isValidServerUrl(url)) { binding.etServerUrl.error = "Must be a http(s) URL"; return }
        if (token.isEmpty()) { binding.etDeviceToken.error = "Required"; return }

        Settings.setServerUrl(this, url)
        Settings.setDeviceToken(this, token)

        val displayedId = binding.tvDeviceId.text.toString()
        if (displayedId != "(not set)") Settings.setDeviceId(this, displayedId)

        val stepsGoal = binding.etStepsGoal.text.toString().toIntOrNull() ?: 10_000
        Settings.setStepsGoal(this, stepsGoal.coerceAtLeast(1))

        Settings.setReconnectSilent(this, binding.switchSilentReconnect.isChecked)
        Settings.setWifiOnly(this, binding.switchWifiOnly.isChecked)

        // Save sync interval
        val selectedLabel = binding.dropSyncInterval.text.toString()
        val intervalMs = syncOptions.firstOrNull { it.first == selectedLabel }?.second ?: 0L
        Settings.setSyncIntervalMs(this, intervalMs)

        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
