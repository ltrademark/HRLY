package com.ltrademark.hourly

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val notificationPermissionsCode = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        supportActionBar?.title = "HRLY Settings"


        prefs = getSharedPreferences("hourly_prefs", Context.MODE_PRIVATE)

        checkAndRequestPermissions()

        setupServiceToggle()
        setupVisualToggle()
        setupFooterDebug()
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
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == notificationPermissionsCode) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notifications allowed", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permission denied. Service status won't be visible.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupServiceToggle() {
        val switchService = findViewById<SwitchMaterial>(R.id.switchService)
        val containerVisual = findViewById<LinearLayout>(R.id.containerVisual)

        switchService.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("service_enabled", isChecked).apply()
            updateVisualContainerState(containerVisual, isChecked)

            if (isChecked) {
                val intent = Intent(this, ChimeService::class.java)
                startForegroundService(intent)
                if (switchService.isPressed) {
                    Toast.makeText(this, "Hourly Chime Enabled", Toast.LENGTH_SHORT).show()
                }
            } else {
                val intent = Intent(this, ChimeService::class.java)
                stopService(intent)
            }
        }

        val isServiceEnabled = prefs.getBoolean("service_enabled", false)
        switchService.isChecked = isServiceEnabled
        updateVisualContainerState(containerVisual, isServiceEnabled)
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

    private fun setupVisualToggle() {
        val switchVisual = findViewById<SwitchMaterial>(R.id.switchVisual)

        if (!Settings.canDrawOverlays(this)) {
            switchVisual.isChecked = false
            switchVisual.setOnClickListener {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
                Toast.makeText(this, "Please allow 'Display over other apps' first", Toast.LENGTH_LONG).show()
                switchVisual.isChecked = false
            }
        } else {
            switchVisual.isChecked = prefs.getBoolean("visual_enabled", false)
            switchVisual.setOnClickListener(null) // Clear the previous listener
            switchVisual.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("visual_enabled", isChecked).apply()
                val status = if (isChecked) "Enabled" else "Disabled"
                Toast.makeText(this, "Visual Pulse $status", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupFooterDebug() {
        val footerText = findViewById<TextView>(R.id.footerText)
        val debugLayout = findViewById<LinearLayout>(R.id.debugLayout)
        var tapCount: Int = 0
        var isDebugUnlocked: Boolean = false

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
        val aboutMessage = """
        HRLY (Hourly) is a minimalist hourly chime app.
        
        Designed to help you keep track of time 
        without checking your phone constantly.
        
        ---
        1 short tone = 1 hour
        1 long tone = 5 hours
        
        tones can compound
        ---
        
        Version 1.0
        2026 © Ltrademark
        All rights reserved.
    """.trimIndent()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("About HRLY")
            .setMessage(aboutMessage)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .setNeutralButton("View Source") { _, _ ->
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ltrademark/HRLY"))
                startActivity(browserIntent)
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) {
            setupVisualToggle()
        }
    }
}