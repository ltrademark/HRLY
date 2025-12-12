package com.ltrademark.hourly

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val notificationPermissionsCode = 101
    private var isDebugUnlocked = false

    // Launchers for Native system sounds
    private val pickShortTone = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val intent = result.data
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }

            if (uri != null) {
                saveCustomTone(uri, "custom_tone_short", findViewById(R.id.txtShortToneName), findViewById(R.id.btnClearShort))
            }
        }
    }

    private val pickLongTone = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val intent = result.data
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }

            if (uri != null) {
                saveCustomTone(uri, "custom_tone_long", findViewById(R.id.txtLongToneName), findViewById(R.id.btnClearLong))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        supportActionBar?.title = "HRLY Settings"

        prefs = getSharedPreferences("hourly_prefs", MODE_PRIVATE)

        checkAndRequestPermissions()
        requestBatteryUnrestricted()

        setupServiceToggle()
        setupVisualToggle()
        setupFooterDebug()
        setupCustomSounds()
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    notificationPermissionsCode
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(AlarmManager::class.java)
            if (!alarmManager.canScheduleExactAlarms()) {
                val intent = Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    "package:$packageName".toUri()
                )
                startActivity(intent)
                Toast.makeText(this, "Please allow 'Alarms & Reminders' for HRLY", Toast.LENGTH_LONG).show()
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryUnrestricted() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                "package:$packageName".toUri()
            )
            startActivity(intent)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == notificationPermissionsCode) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notifications allowed", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permission denied. Service status won\'t be visible.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupServiceToggle() {
        val switchService = findViewById<SwitchMaterial>(R.id.switchService)
        val containerVisual = findViewById<LinearLayout>(R.id.containerVisual)
        val containerCustomSoundsToggle = findViewById<LinearLayout>(R.id.containerCustomSoundsToggle)

        // Listener
        switchService.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit {
                putBoolean("service_enabled", isChecked)
            }

            // Show/Hide dependent options
            updateVisualContainerState(containerVisual, isChecked)
            updateVisualContainerState(containerCustomSoundsToggle, isChecked)

            if (isChecked) {
                val intent = Intent(this, ChimeService::class.java)
                startForegroundService(intent)
                if (switchService.isPressed) Toast.makeText(this, "Hourly Chime Enabled", Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent(this, ChimeService::class.java)
                stopService(intent)
            }
        }

        // Initial State
        val isServiceEnabled = prefs.getBoolean("service_enabled", false)
        switchService.isChecked = isServiceEnabled
        updateVisualContainerState(containerVisual, isServiceEnabled)
        updateVisualContainerState(containerCustomSoundsToggle, isServiceEnabled)
    }

    private fun updateVisualContainerState(container: View, isEnabled: Boolean) {
        if (isEnabled) {
            container.visibility = View.VISIBLE
            container.alpha = 0f
            container.animate().alpha(1f).setDuration(300).start()
        } else {
            container.visibility = View.GONE
        }
    }

    private fun setupCustomSounds() {
        val switchCustomSounds = findViewById<SwitchMaterial>(R.id.switchCustomSounds)
        val containerSoundPickers = findViewById<LinearLayout>(R.id.containerSoundPickers)

        val isCustomEnabled = prefs.getBoolean("custom_sounds_enabled", false)
        switchCustomSounds.isChecked = isCustomEnabled
        updateVisualContainerState(containerSoundPickers, isCustomEnabled)

        switchCustomSounds.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit {
                putBoolean("custom_sounds_enabled", isChecked)
            }
            updateVisualContainerState(containerSoundPickers, isChecked)
        }

        restoreToneState("custom_tone_short", findViewById(R.id.txtShortToneName), findViewById(R.id.btnClearShort))
        restoreToneState("custom_tone_long", findViewById(R.id.txtLongToneName), findViewById(R.id.btnClearLong))

        findViewById<Button>(R.id.btnPickShort).setOnClickListener {
            val currentUriString = prefs.getString("custom_tone_short", null)
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Short Tone")
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false) // We have a clear button, so hide Silent
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)

                // Pre-select the current tone if one exists
                if (currentUriString != null) {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                        currentUriString.toUri())
                }
            }
            pickShortTone.launch(intent)
        }

        findViewById<Button>(R.id.btnPickLong).setOnClickListener {
            val currentUriString = prefs.getString("custom_tone_long", null)
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Long Tone")
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)

                if (currentUriString != null) {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                        currentUriString.toUri())
                }
            }
            pickLongTone.launch(intent)
        }

        findViewById<ImageView>(R.id.btnClearShort).setOnClickListener {
            clearCustomTone("custom_tone_short", findViewById(R.id.txtShortToneName), it)
        }
        findViewById<ImageView>(R.id.btnClearLong).setOnClickListener {
            clearCustomTone("custom_tone_long", findViewById(R.id.txtLongToneName), it)
        }
    }

    private fun saveCustomTone(uri: Uri, key: String, textView: TextView, clearBtn: View) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // This is expected for system ringtones (content://media/internal/...), so we ignore it.
        }

        prefs.edit {
            putString(key, uri.toString())
        }

        val ringtone = RingtoneManager.getRingtone(this, uri)
        val name = ringtone?.getTitle(this) ?: "Custom Audio"

        textView.text = name
        clearBtn.visibility = View.VISIBLE
    }

    private fun restoreToneState(key: String, textView: TextView, clearBtn: View) {
        val uriString = prefs.getString(key, null)
        if (uriString != null) {
            val fileName = getFileName(uriString.toUri()) ?: getString(R.string.custom_audio)
            textView.text = fileName
            clearBtn.visibility = View.VISIBLE
        } else {
            textView.text = getString(R.string.default_tone)
            clearBtn.visibility = View.GONE
        }
    }

    private fun clearCustomTone(key: String, textView: TextView, clearBtn: View) {
        prefs.edit {
            remove(key)
        }
        textView.text = getString(R.string.default_tone)
        clearBtn.visibility = View.GONE
        Toast.makeText(this, getString(R.string.reset_to_default_tone), Toast.LENGTH_SHORT).show()
    }

    private fun getFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        }
        return uri.path?.let { path ->
            val cut = path.lastIndexOf('/')
            if (cut != -1) path.substring(cut + 1) else path
        }
    }

    private fun setupVisualToggle() {
        val switchVisual = findViewById<SwitchMaterial>(R.id.switchVisual)

        if (!Settings.canDrawOverlays(this)) {
            switchVisual.isChecked = false
            switchVisual.setOnClickListener {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
                startActivity(intent)
                Toast.makeText(this, "Please allow 'Display over other apps' first", Toast.LENGTH_LONG).show()
                switchVisual.isChecked = false
            }
        } else {
            switchVisual.isChecked = prefs.getBoolean("visual_enabled", false)
            switchVisual.setOnClickListener(null) // Clear the previous listener
            switchVisual.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit {
                    putBoolean("visual_enabled", isChecked)
                }
                val status = if (isChecked) "Enabled" else "Disabled"
                Toast.makeText(this, "Visual Pulse $status", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupFooterDebug() {
        val footerText = findViewById<TextView>(R.id.footerText)
        val debugLayout = findViewById<LinearLayout>(R.id.debugLayout)
        var tapCount = 0

        footerText.setOnClickListener {
            if (isDebugUnlocked) {
                Toast.makeText(this, "Debug mode is already active", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            tapCount++

            if (tapCount >= 5) {
                isDebugUnlocked = true
                debugLayout.visibility = View.VISIBLE
                Toast.makeText(this, "Debug Mode Unlocked!", Toast.LENGTH_SHORT).show()

            } else if (tapCount > 2) {
                val tapsRemaining = 5 - tapCount
                Toast.makeText(this, "You are $tapsRemaining steps away from being a developer", Toast.LENGTH_SHORT).show()
            }
        }

        // Debug Buttons
        findViewById<Button>(R.id.btnTest8).setOnClickListener {
            val intent = Intent(this, ChimeService::class.java).apply {
                action = ChimeService.ACTION_PLAY_CHIME
                putExtra(ChimeService.EXTRA_TEST_HOUR, 8)
            }
            startForegroundService(intent)
        }

        findViewById<Button>(R.id.btnTest12).setOnClickListener {
            val intent = Intent(this, ChimeService::class.java).apply {
                action = ChimeService.ACTION_PLAY_CHIME
                putExtra(ChimeService.EXTRA_TEST_HOUR, 12)
            }
            startForegroundService(intent)
        }

        findViewById<Button>(R.id.btnTestNow).setOnClickListener {
            val intent = Intent(this, ChimeService::class.java).apply {
                action = ChimeService.ACTION_PLAY_CHIME
            }
            startForegroundService(intent)
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAboutDialog() {
        val appVersion = BuildConfig.VERSION_NAME

        val aboutMessage = """
        HRLY (Hourly) is a minimalist hourly chime app.
        
        Designed to help you keep track of time 
        without checking your phone constantly.
        
        ---
        1 short tone = 1 hour
        1 long tone = 5 hours
        
        tones can compound
        ---
        
        Version $appVersion
        2026 © Ltrademark
        All rights reserved.
    """.trimIndent()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("About HRLY")
            .setMessage(aboutMessage)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .setNeutralButton("View Source") { _, _ ->
                val browserIntent = Intent(Intent.ACTION_VIEW, "https://github.com/ltrademark/HRLY".toUri())
                startActivity(browserIntent)
            }
            .show()
    }

    override fun onResume() {
        super.onResume()

        val isServiceEnabled = prefs.getBoolean("service_enabled", false)
        val switchService = findViewById<SwitchMaterial>(R.id.switchService)
        val containerVisual = findViewById<LinearLayout>(R.id.containerVisual)
        val containerCustomSoundsToggle = findViewById<LinearLayout>(R.id.containerCustomSoundsToggle)

        if (switchService.isChecked != isServiceEnabled) {
            switchService.isChecked = isServiceEnabled
            updateVisualContainerState(containerVisual, isServiceEnabled)
            updateVisualContainerState(containerCustomSoundsToggle, isServiceEnabled)
        }

        if (Settings.canDrawOverlays(this)) {
            setupVisualToggle()
        }

        // Refresh tone names on resume
        restoreToneState("custom_tone_short", findViewById(R.id.txtShortToneName), findViewById(R.id.btnClearShort))
        restoreToneState("custom_tone_long", findViewById(R.id.txtLongToneName), findViewById(R.id.btnClearLong))
    }
}
