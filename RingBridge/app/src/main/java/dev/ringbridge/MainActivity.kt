package dev.ringbridge

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import dev.ringbridge.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val homeFragment     by lazy { HomeFragment() }
    private val vitalsFragment   by lazy { VitalsFragment() }
    private val activityFragment by lazy { ActivityFragment() }
    private val historyFragment  by lazy { HistoryFragment() }

    private var activeFragment: Fragment = homeFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        // Add all fragments hidden, show home
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().apply {
                add(R.id.fragmentContainer, historyFragment,  "history").hide(historyFragment)
                add(R.id.fragmentContainer, activityFragment, "activity").hide(activityFragment)
                add(R.id.fragmentContainer, vitalsFragment,   "vitals").hide(vitalsFragment)
                add(R.id.fragmentContainer, homeFragment,     "home")
            }.commit()
        } else {
            // Restore active fragment reference after process death
            val tag = savedInstanceState.getString(KEY_ACTIVE_TAB, "home")
            activeFragment = supportFragmentManager.findFragmentByTag(tag) ?: homeFragment
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            val target = when (item.itemId) {
                R.id.nav_home     -> homeFragment
                R.id.nav_vitals   -> vitalsFragment
                R.id.nav_activity -> activityFragment
                R.id.nav_history  -> historyFragment
                else              -> return@setOnItemSelectedListener false
            }
            if (target !== activeFragment) {
                supportFragmentManager.beginTransaction()
                    .hide(activeFragment)
                    .show(target)
                    .commit()
                activeFragment = target
            }
            true
        }

        promptBatteryOptimization()
    }

    // Drive the service's foreground state from the Activity lifecycle. The service
    // also listens for SCREEN_OFF / USER_PRESENT, but USER_PRESENT only fires on a
    // keyguard unlock — on phones with no secure lock (or when the app is opened while
    // already unlocked) it never fires, leaving the service stuck in "background" mode
    // and never pulling history (e.g. the morning sleep session). onResume is the
    // reliable "user is looking at the app, safe to sync" signal.
    override fun onResume() {
        super.onResume()
        RingService.setForeground(true)
    }

    override fun onStop() {
        super.onStop()
        // App no longer visible → let the ring return to autonomous low-power monitoring.
        RingService.setForeground(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val tag = when (activeFragment) {
            homeFragment     -> "home"
            vitalsFragment   -> "vitals"
            activityFragment -> "activity"
            historyFragment  -> "history"
            else             -> "home"
        }
        outState.putString(KEY_ACTIVE_TAB, tag)
    }

    // ── Options menu ──────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── Battery optimization ──────────────────────────────────────────────────

    private fun promptBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        val prefs = getSharedPreferences("ringbridge", MODE_PRIVATE)
        if (prefs.getBoolean("battery_opt_prompted", false)) return
        prefs.edit().putBoolean("battery_opt_prompted", true).apply()

        AlertDialog.Builder(this)
            .setTitle("Keep ring connected in background")
            .setMessage(
                "Android may stop RingBridge to save battery. " +
                "Tap \"Allow\" to disable battery optimization so the ring stays connected."
            )
            .setPositiveButton("Allow") { _, _ ->
                @SuppressLint("BatteryLife")
                val intent = Intent(
                    AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    companion object {
        private const val KEY_ACTIVE_TAB = "active_tab"
    }
}
