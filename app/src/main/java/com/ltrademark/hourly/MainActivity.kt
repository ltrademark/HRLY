/*
 * HRLY, a simple hourly chime app for Android.
 * Copyright (C) 2025-2026 Ltrademark
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * The HRLY name, logo, and branding are not covered by this license.
 * See TRADEMARK.md.
 */
package com.ltrademark.hourly

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
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
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val notificationPermissionsCode = 101

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
                saveCustomTone(uri, "custom_tone_short", findViewById(R.id.txtShortToneName))
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
                saveCustomTone(uri, "custom_tone_long", findViewById(R.id.txtLongToneName))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        supportActionBar?.title = "HRLY Settings"

        // The layout pads the content down by actionBarSize so it clears the bar
        // under edge-to-edge (API 35+, where content draws behind the bar). On
        // older Android the standard action bar already reserves its space, so that
        // padding double-counts and leaves a gap under the toolbar. Zero it there.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val container = findViewById<View>(R.id.settingsContainer)
            container.setPadding(
                container.paddingLeft, 0, container.paddingRight, container.paddingBottom
            )
        }

        prefs = getSharedPreferences("hourly_prefs", MODE_PRIVATE)

        checkAndRequestPermissions()
        setupBatteryWarning()

        setupServiceToggle()
        setupFooterDebug()
        setupCustomSounds()
        setupQuietHours()
        setupSimpleMode()
        setupChimeMode()
        setupNotificationPref()
        setupTiming()
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
        // No runtime exact-alarm request needed: the manifest declares USE_EXACT_ALARM
        // (auto-granted) and SCHEDULE_EXACT_ALARM (default-granted on API 31-32), which
        // cover the setAlarmClock() the service uses. Do NOT call canScheduleExactAlarms()
        // here: that API only exists on API 31+ and crashed on older Android (#1).
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

    @SuppressLint("BatteryLife")
    private fun setupBatteryWarning() {
        val containerWarning = findViewById<LinearLayout>(R.id.containerBatteryWarning)
        val btnFix = findViewById<Button>(R.id.btnFixBattery)
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val prefs = getSharedPreferences("hourly_prefs", MODE_PRIVATE)

        fun updateWarningVisibility() {
            val isServiceEnabled = prefs.getBoolean("service_enabled", false)
            val isIgnored = powerManager.isIgnoringBatteryOptimizations(packageName)

            if (isServiceEnabled && !isIgnored) {
                containerWarning.visibility = View.VISIBLE
            } else {
                containerWarning.visibility = View.GONE
            }
        }

        updateWarningVisibility()

        btnFix.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:$packageName".toUri()
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = "package:$packageName".toUri()
                    }
                    startActivity(intent)
                    Toast.makeText(this, "Go to Battery > Unrestricted", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open settings", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupServiceToggle() {
        val switchService = findViewById<SwitchMaterial>(R.id.switchService)
        // All chime configuration lives in one container that is shown only while the
        // service is enabled; the inner sections manage their own expand/collapse.
        val containerSettings = findViewById<LinearLayout>(R.id.containerSettings)

        switchService.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit {
                putBoolean("service_enabled", isChecked)
            }

            updateVisualContainerState(containerSettings, isChecked)

            if (isChecked) {
                val intent = Intent(this, ChimeService::class.java)
                startForegroundService(intent)
                if (switchService.isPressed) Toast.makeText(this, "Hourly Chime Enabled", Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent(this, ChimeService::class.java)
                stopService(intent)
            }
        }

        val isServiceEnabled = prefs.getBoolean("service_enabled", false)
        switchService.isChecked = isServiceEnabled
        updateVisualContainerState(containerSettings, isServiceEnabled)
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

        restoreToneState("custom_tone_short", findViewById(R.id.txtShortToneName))
        restoreToneState("custom_tone_long", findViewById(R.id.txtLongToneName))

        findViewById<Button>(R.id.btnPickShort).setOnClickListener {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Short Tone")
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false) // We have a clear button, so hide Silent
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            }
            pickShortTone.launch(intent)
        }

        findViewById<Button>(R.id.btnPickLong).setOnClickListener {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Long Tone")
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            }
            pickLongTone.launch(intent)
        }

        findViewById<ImageView>(R.id.btnClearShort).setOnClickListener {
            clearCustomTone("custom_tone_short", findViewById(R.id.txtShortToneName))
        }
        findViewById<ImageView>(R.id.btnClearLong).setOnClickListener {
            clearCustomTone("custom_tone_long", findViewById(R.id.txtLongToneName))
        }

        findViewById<Button>(R.id.btnTrimShort).setOnClickListener { openCropDialog("short") }
        findViewById<Button>(R.id.btnTrimLong).setOnClickListener { openCropDialog("long") }
    }

    private fun saveCustomTone(uri: Uri, key: String, textView: TextView) {
        // Copy the picked audio into app-private storage while we still hold the
        // transient read grant from the picker. The background service later plays
        // this local copy, which avoids losing access to the content:// URI once the
        // app process restarts, the cause of the custom tone silently reverting to
        // the default (#2).
        val destFile = java.io.File(filesDir, key)
        val copied = try {
            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            } != null
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }

        if (!copied) {
            Toast.makeText(this, "Couldn't save that sound. Try another file.", Toast.LENGTH_LONG).show()
            return
        }

        // Prefer the ringtone's friendly title ("Soft Gong") over the raw file
        // DISPLAY_NAME ("sound_picker_track_31.ogg"); fall back to the filename.
        val name = RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: getFileName(uri)
            ?: getString(R.string.custom_audio)

        val type = if (key.endsWith("short")) "short" else "long"
        prefs.edit {
            putString(key, destFile.absolutePath)
            putString("${key}_name", name)
            // Reset any crop from a previously chosen file (#6).
            remove("crop_${type}_start")
            remove("crop_${type}_end")
        }

        textView.text = name
        setToneRowHasTone(key, true)
    }

    // Fresh row shows only "Choose"; once a tone is set, reveal Trim (left) and X (right)
    // and relabel the picker "Change" since it now swaps an existing tone.
    private fun setToneRowHasTone(key: String, hasTone: Boolean) {
        val short = key.endsWith("short")
        val vis = if (hasTone) View.VISIBLE else View.GONE
        findViewById<View>(if (short) R.id.btnTrimShort else R.id.btnTrimLong).visibility = vis
        findViewById<View>(if (short) R.id.btnClearShort else R.id.btnClearLong).visibility = vis
        findViewById<Button>(if (short) R.id.btnPickShort else R.id.btnPickLong)
            .setText(if (hasTone) R.string.change_sound else R.string.choose_sound)
    }

    private fun restoreToneState(key: String, textView: TextView) {
        val path = prefs.getString(key, null)
        if (path != null && java.io.File(path).exists()) {
            textView.text = prefs.getString("${key}_name", null) ?: getString(R.string.custom_audio)
            setToneRowHasTone(key, true)
        } else {
            // No tone set, or the stored copy is gone, so show the default.
            if (path != null) prefs.edit { remove(key); remove("${key}_name") }
            textView.text = getString(R.string.default_tone)
            setToneRowHasTone(key, false)
        }
    }

    private fun clearCustomTone(key: String, textView: TextView) {
        prefs.getString(key, null)?.let { path ->
            try { File(path).delete() } catch (_: Exception) {}
        }
        val type = if (key.endsWith("short")) "short" else "long"
        prefs.edit {
            remove(key)
            remove("${key}_name")
            remove("crop_${type}_start")
            remove("crop_${type}_end")
        }
        textView.text = getString(R.string.default_tone)
        setToneRowHasTone(key, false)
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

    private fun setupQuietHours() {
        val switchQuiet = findViewById<SwitchMaterial>(R.id.switchQuietHours)
        val containerPickers = findViewById<LinearLayout>(R.id.containerQuietPickers)
        val btnStart = findViewById<Button>(R.id.btnQuietStart)
        val btnEnd = findViewById<Button>(R.id.btnQuietEnd)

        val isEnabled = prefs.getBoolean("quiet_enabled", false)
        var startH = prefs.getInt("quiet_start_h", 22)
        var startM = prefs.getInt("quiet_start_m", 0)
        var endH = prefs.getInt("quiet_end_h", 7)
        var endM = prefs.getInt("quiet_end_m", 0)

        fun updateTimeButton(btn: Button, hour: Int, min: Int) {
            val calendar = java.util.Calendar.getInstance()
            calendar.set(java.util.Calendar.HOUR_OF_DAY, hour)
            calendar.set(java.util.Calendar.MINUTE, min)
            // Respect the system 12/24-hour clock setting instead of forcing 12h (#9).
            val format = android.text.format.DateFormat.getTimeFormat(this)
            btn.text = format.format(calendar.time)
        }

        switchQuiet.isChecked = isEnabled
        containerPickers.visibility = if (isEnabled) View.VISIBLE else View.GONE
        updateTimeButton(btnStart, startH, startM)
        updateTimeButton(btnEnd, endH, endM)

        switchQuiet.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("quiet_enabled", isChecked) }
            if (isChecked) {
                containerPickers.visibility = View.VISIBLE
                containerPickers.alpha = 0f
                containerPickers.animate().alpha(1f).setDuration(300).start()
            } else {
                containerPickers.visibility = View.GONE
            }
        }

        btnStart.setOnClickListener {
            android.app.TimePickerDialog(this, { _, h, m ->
                startH = h; startM = m
                prefs.edit { putInt("quiet_start_h", h); putInt("quiet_start_m", m) }
                updateTimeButton(btnStart, h, m)
            }, startH, startM, android.text.format.DateFormat.is24HourFormat(this)).show()
        }

        btnEnd.setOnClickListener {
            android.app.TimePickerDialog(this, { _, h, m ->
                endH = h; endM = m
                prefs.edit { putInt("quiet_end_h", h); putInt("quiet_end_m", m) }
                updateTimeButton(btnEnd, h, m)
            }, endH, endM, android.text.format.DateFormat.is24HourFormat(this)).show()
        }
    }

    private fun setupSimpleMode() {
        val sw = findViewById<SwitchMaterial>(R.id.switchSimpleMode)
        val enabled = prefs.getBoolean("simple_chime_enabled", false)
        sw.isChecked = enabled
        applySingleModeToSoundPickers(enabled)
        sw.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("simple_chime_enabled", isChecked) }
            applySingleModeToSoundPickers(isChecked)
        }
    }

    // In single-chime mode there is only one tone, so the custom-sound section shows a
    // single "Tone" picker instead of separate Short/Long pickers.
    private fun applySingleModeToSoundPickers(singleMode: Boolean) {
        findViewById<TextView>(R.id.txtShortToneLabel).setText(
            if (singleMode) R.string.tone else R.string.short_tone
        )
        findViewById<LinearLayout>(R.id.rowLongTone).visibility =
            if (singleMode) View.GONE else View.VISIBLE
    }

    private fun setupChimeMode() {
        val toggle = findViewById<MaterialButtonToggleGroup>(R.id.toggleChimeMode)
        val override = findViewById<SwitchMaterial>(R.id.switchOverrideSilent)

        // Resolve the current mode, migrating the pre-1.7 vibrate_enabled pref.
        val mode = prefs.getString("chime_mode", null)
            ?: if (prefs.getBoolean("vibrate_enabled", false)) "both" else "sound"

        toggle.check(
            when (mode) {
                "vibrate" -> R.id.btnModeVibrate
                "both" -> R.id.btnModeBoth
                else -> R.id.btnModeSound
            }
        )

        // The override only affects sound, so it is meaningless in vibrate-only mode.
        fun applyOverrideEnabled(currentMode: String) {
            override.isEnabled = currentMode != "vibrate"
        }

        override.isChecked = prefs.getBoolean("override_silent", false)
        applyOverrideEnabled(mode)

        toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newMode = when (checkedId) {
                R.id.btnModeVibrate -> "vibrate"
                R.id.btnModeBoth -> "both"
                else -> "sound"
            }
            prefs.edit { putString("chime_mode", newMode) }
            applyOverrideEnabled(newMode)
        }

        override.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("override_silent", isChecked) }
        }
    }

    private fun setupNotificationPref() {
        val sw = findViewById<SwitchMaterial>(R.id.switchHideNextChime)
        // Android floors foreground-service notifications and, before Android 12,
        // keeps them out of the collapsed/silent area, so full minimizing only
        // works on API 31+. Warn on older versions so it isn't taken for a bug.
        findViewById<TextView>(R.id.txtMinimizeNote).visibility =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) View.VISIBLE else View.GONE
        sw.isChecked = prefs.getBoolean("hide_next_chime", false)
        sw.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("hide_next_chime", isChecked) }
            // Re-post the ongoing notification so the change shows immediately.
            if (prefs.getBoolean("service_enabled", false)) {
                startForegroundService(Intent(this, ChimeService::class.java))
            }
        }
    }

    private fun setupTiming() {
        val sw = findViewById<SwitchMaterial>(R.id.switchTiming)
        val container = findViewById<LinearLayout>(R.id.containerTiming)
        val slider = findViewById<Slider>(R.id.sliderGap)
        val gapValue = findViewById<TextView>(R.id.txtGapValue)
        val resetBtn = findViewById<Button>(R.id.btnGapReset)
        val warning = findViewById<TextView>(R.id.txtGapWarning)

        fun showGap(ms: Int) { gapValue.text = getString(R.string.tone_gap_value, ms) }

        // Outside this band the gap is short enough to merge tones or long enough
        // to blur the hourly count, so the warning is shown.
        val warnMinMs = 50
        val warnMaxMs = 1000

        // Reset shows only once the gap differs from the default; the warning shows
        // only when the gap is short/long enough to hurt the hourly cue.
        fun updateHints(ms: Int) {
            resetBtn.visibility = if (ms == ChimeService.DEFAULT_TONE_GAP_MS) View.GONE else View.VISIBLE
            val extreme = ms < warnMinMs || ms > warnMaxMs
            warning.visibility = if (extreme) View.VISIBLE else View.GONE
        }

        slider.value = prefs.getInt("tone_gap_ms", ChimeService.DEFAULT_TONE_GAP_MS)
            .coerceIn(ChimeService.MIN_TONE_GAP_MS, ChimeService.MAX_TONE_GAP_MS).toFloat()
        showGap(slider.value.toInt())
        updateHints(slider.value.toInt())
        slider.setLabelFormatter { getString(R.string.tone_gap_value, it.toInt()) }
        slider.addOnChangeListener { _, value, _ ->
            prefs.edit { putInt("tone_gap_ms", value.toInt()) }
            showGap(value.toInt())
            updateHints(value.toInt())
        }

        resetBtn.setOnClickListener {
            slider.value = ChimeService.DEFAULT_TONE_GAP_MS
                .coerceIn(ChimeService.MIN_TONE_GAP_MS, ChimeService.MAX_TONE_GAP_MS).toFloat()
        }

        val enabled = prefs.getBoolean("timing_enabled", false)
        sw.isChecked = enabled
        container.visibility = if (enabled) View.VISIBLE else View.GONE
        sw.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("timing_enabled", isChecked) }
            updateVisualContainerState(container, isChecked)
        }
    }

    private fun formatMs(ms: Int) =
        String.format(java.util.Locale.getDefault(), "%.2f s", ms / 1000f)

    private fun openCropDialog(type: String) {
        val key = if (type == "short") "custom_tone_short" else "custom_tone_long"
        val file = prefs.getString(key, null)?.let { File(it) }
        if (file == null || !file.exists()) {
            Toast.makeText(this, getString(R.string.crop_no_custom), Toast.LENGTH_SHORT).show()
            return
        }

        val durationMs = try {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            val d = mp.duration
            mp.release()
            d
        } catch (e: Exception) {
            e.printStackTrace(); 0
        }
        if (durationMs <= 0) {
            Toast.makeText(this, getString(R.string.crop_no_custom), Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_crop, null)
        val waveform = dialogView.findViewById<WaveformTrimView>(R.id.waveform)
        val etStart = dialogView.findViewById<EditText>(R.id.etCropStart)
        val etEnd = dialogView.findViewById<EditText>(R.id.etCropEnd)
        waveform.minGapMs = ChimeService.MIN_CROP_MS

        dialogView.findViewById<TextView>(R.id.txtCropDuration).text =
            getString(R.string.crop_length, formatMs(durationMs))

        // Trim-length safeguards keyed to the count-the-hour methodology. The hard
        // floor (MIN_CROP_MS, enforced by the handle/input min-gap) can't be crossed;
        // these drive non-blocking warnings. Single-chime mode uses the short slot but
        // never counts, so it gets only the floor, no band/distinguishability rules.
        val singleChime = prefs.getBoolean("simple_chime_enabled", false)
        val applyCountRules = !(type == "short" && singleChime)
        val recLen = if (type == "short") ChimeService.REC_SHORT_MS else ChimeService.REC_LONG_MS
        val bandMin = if (type == "short") ChimeService.SHORT_BAND_MIN_MS else ChimeService.LONG_BAND_MIN_MS
        val bandMax = if (type == "short") ChimeService.SHORT_BAND_MAX_MS else ChimeService.LONG_BAND_MAX_MS
        // Effective length of the *other* tone (its crop if set, else its default) for
        // the "can you still tell long from short?" check.
        val otherType = if (type == "short") "long" else "short"
        val otherCropStart = prefs.getInt("crop_${otherType}_start", 0)
        val otherCropEnd = prefs.getInt("crop_${otherType}_end", 0)
        val otherLen = if (otherCropEnd > otherCropStart) otherCropEnd - otherCropStart
            else if (otherType == "short") ChimeService.REC_SHORT_MS else ChimeService.REC_LONG_MS

        val txtRecommended = dialogView.findViewById<TextView>(R.id.txtCropRecommended)
        val txtWarning = dialogView.findViewById<TextView>(R.id.txtCropWarning)
        if (applyCountRules) {
            waveform.recommendedLenMs = recLen
            txtRecommended.text = getString(R.string.crop_recommended, formatMs(recLen))
            txtRecommended.visibility = View.VISIBLE
        }
        fun refreshWarning() {
            val len = waveform.endMs - waveform.startMs
            val msg = when {
                !applyCountRules -> null
                type == "short" && len >= otherLen - ChimeService.DISTINCT_MARGIN_MS ->
                    getString(R.string.crop_warn_indistinct)
                type == "long" && len <= otherLen + ChimeService.DISTINCT_MARGIN_MS ->
                    getString(R.string.crop_warn_indistinct)
                len < bandMin || len > bandMax ->
                    getString(if (type == "short") R.string.crop_warn_short_band else R.string.crop_warn_long_band)
                else -> null
            }
            txtWarning.visibility = if (msg == null) View.GONE else View.VISIBLE
            if (msg != null) txtWarning.text = msg
        }

        val initStart = prefs.getInt("crop_${type}_start", 0).coerceIn(0, durationMs)
        val initEnd = prefs.getInt("crop_${type}_end", durationMs)
            .let { if (it <= 0) durationMs else it }.coerceIn(0, durationMs)

        fun secStr(ms: Int) = String.format(java.util.Locale.getDefault(), "%.2f", ms / 1000f)

        // Waveform starts empty (flat baseline) and fills once decoding finishes.
        waveform.setAudio(FloatArray(0), durationMs)
        waveform.setRange(initStart, initEnd)
        etStart.setText(secStr(waveform.startMs))
        etEnd.setText(secStr(waveform.endMs))
        refreshWarning()

        // Dragging a handle updates the number fields (unless that field is being typed in).
        waveform.onRangeChanged = { s, e ->
            if (!etStart.hasFocus()) etStart.setText(secStr(s))
            if (!etEnd.hasFocus()) etEnd.setText(secStr(e))
            refreshWarning()
        }

        // Typed value -> waveform. Clamp against the other end + min length, then
        // normalize both fields to what actually stuck.
        fun commitInputs() {
            val s = (etStart.text.toString().toFloatOrNull()?.let { (it * 1000).toInt() } ?: waveform.startMs)
                .coerceIn(0, durationMs)
            val e = (etEnd.text.toString().toFloatOrNull()?.let { (it * 1000).toInt() } ?: waveform.endMs)
                .coerceIn(0, durationMs)
            waveform.setRange(s, e)
            etStart.setText(secStr(waveform.startMs))
            etEnd.setText(secStr(waveform.endMs))
            refreshWarning()
        }
        val commitOnDone = { _: android.view.View?, actionId: Int ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { commitInputs(); true } else false
        }
        etStart.setOnEditorActionListener { _, id, _ -> commitOnDone(null, id) }
        etEnd.setOnEditorActionListener { _, id, _ -> commitOnDone(null, id) }
        etStart.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitInputs() }
        etEnd.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitInputs() }

        var previewPlayer: MediaPlayer? = null
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setOnDismissListener { previewPlayer?.release(); previewPlayer = null }
            .show()

        // Decode the waveform off the main thread and paint it in when ready.
        Thread {
            val amps = WaveformDecoder.decode(file.absolutePath)
            runOnUiThread { if (dialog.isShowing) waveform.setAudio(amps, durationMs) }
        }.start()

        dialogView.findViewById<Button>(R.id.btnCropPreview).setOnClickListener {
            val startMs = waveform.startMs
            val endMs = waveform.endMs
            if (endMs - startMs < ChimeService.MIN_CROP_MS) {
                Toast.makeText(this, getString(R.string.crop_too_short), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                previewPlayer?.release()
                previewPlayer = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    // Play on the same stream the chime actually uses so the preview
                    // matches reality: the alarm usage when the user opted to play
                    // through silent/vibrate (#11), otherwise the notification usage.
                    // Either way not the media stream, which would be silent for
                    // anyone with media volume down even though the chime is audible.
                    val usage = if (prefs.getBoolean("override_silent", false)) {
                        AudioAttributes.USAGE_ALARM
                    } else {
                        AudioAttributes.USAGE_NOTIFICATION
                    }
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    // Sample-accurate seek (default snaps to the nearest keyframe, which
                    // makes small start moves jump to the same spot).
                    setOnPreparedListener { it.seekTo(startMs.toLong(), MediaPlayer.SEEK_CLOSEST) }
                    setOnSeekCompleteListener {
                        it.start()
                        // Stop at the crop end, timed from actual playback start. Pause
                        // before release so we don't glitch/stutter by tearing the player
                        // down mid-buffer.
                        dialogView.postDelayed({
                            try {
                                previewPlayer?.let { p -> if (p.isPlaying) p.pause() }
                                previewPlayer?.release()
                            } catch (_: Exception) {}
                            previewPlayer = null
                        }, (endMs - startMs).toLong())
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        dialogView.findViewById<Button>(R.id.btnCropCancel).setOnClickListener { dialog.dismiss() }

        dialogView.findViewById<Button>(R.id.btnCropSave).setOnClickListener {
            commitInputs()
            val startMs = waveform.startMs
            val endMs = waveform.endMs
            if (endMs - startMs < ChimeService.MIN_CROP_MS) {
                Toast.makeText(this, getString(R.string.crop_too_short), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit {
                putInt("crop_${type}_start", startMs)
                putInt("crop_${type}_end", endMs)
            }
            dialog.dismiss()
        }
    }

    private fun setupFooterDebug() {
        val footerText = findViewById<TextView>(R.id.footerText)
        val debugLayout = findViewById<LinearLayout>(R.id.debugLayout)
        var tapCount = 0
        var isDebugUnlocked = false
        var currentToast: Toast? = null

        footerText.setOnClickListener {
            currentToast?.cancel()

            if (isDebugUnlocked) {
                currentToast = Toast.makeText(this, "Debug mode is already active", Toast.LENGTH_SHORT)
                currentToast?.show()
                return@setOnClickListener
            }

            tapCount++

            if (tapCount >= 5) {
                isDebugUnlocked = true
                debugLayout.visibility = View.VISIBLE

                currentToast = Toast.makeText(this, "Debug Mode Unlocked!", Toast.LENGTH_SHORT)
                currentToast?.show()

            } else if (tapCount > 2) {
                val tapsRemaining = 5 - tapCount
                currentToast = Toast.makeText(this, "You are $tapsRemaining steps away from being a developer", Toast.LENGTH_SHORT)
                currentToast?.show()
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

        findViewById<Button>(R.id.btnTestVibrate).setOnClickListener {
            val intent = Intent(this, ChimeService::class.java).apply {
                action = ChimeService.ACTION_TEST_VIBRATE
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

    @SuppressLint("SetTextI18n")
    private fun showAboutDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_about, null)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .show()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val txtVersion = dialogView.findViewById<TextView>(R.id.txtVersion)
        txtVersion.text = "Version ${BuildConfig.VERSION_NAME}\n2025-2026 © Ltrademark. Licensed under GPLv3."

        val btnOk = dialogView.findViewById<Button>(R.id.btnAboutOk)
        btnOk.setOnClickListener {
            dialog.dismiss()
        }

        val btnSource = dialogView.findViewById<Button>(R.id.btnAboutSource)
        btnSource.setOnClickListener {
            val browserIntent = Intent(Intent.ACTION_VIEW, "https://github.com/ltrademark/HRLY".toUri())
            startActivity(browserIntent)
        }
    }

    override fun onResume() {
        super.onResume()

        val isServiceEnabled = prefs.getBoolean("service_enabled", false)
        val switchService = findViewById<SwitchMaterial>(R.id.switchService)
        val containerSettings = findViewById<LinearLayout>(R.id.containerSettings)

        setupBatteryWarning()

        if (switchService.isChecked != isServiceEnabled) {
            switchService.isChecked = isServiceEnabled
            updateVisualContainerState(containerSettings, isServiceEnabled)
        }

        restoreToneState("custom_tone_short", findViewById(R.id.txtShortToneName))
        restoreToneState("custom_tone_long", findViewById(R.id.txtLongToneName))
    }
}
