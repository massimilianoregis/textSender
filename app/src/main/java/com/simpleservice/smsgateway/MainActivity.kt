package com.simpleservice.smsgateway

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var sourceUrl: EditText
    private lateinit var callbackUrl: EditText
    private lateinit var jwt: EditText
    private lateinit var pollSeconds: EditText
    private lateinit var manualMode: RadioButton
    private lateinit var automaticMode: RadioButton
    private lateinit var status: TextView
    private lateinit var messageList: LinearLayout
    private lateinit var startBackgroundButton: Button
    private lateinit var stopBackgroundButton: Button
    private lateinit var reviewButton: Button

    private val http = HttpSmsClient()
    private var currentItems: List<SmsItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sourceUrl = findViewById(R.id.sourceUrl)
        callbackUrl = findViewById(R.id.callbackUrl)
        jwt = findViewById(R.id.jwt)
        pollSeconds = findViewById(R.id.pollSeconds)
        manualMode = findViewById(R.id.manualMode)
        automaticMode = findViewById(R.id.automaticMode)
        status = findViewById(R.id.status)
        messageList = findViewById(R.id.messageList)
        startBackgroundButton = findViewById(R.id.startBackgroundButton)
        stopBackgroundButton = findViewById(R.id.stopBackgroundButton)
        reviewButton = findViewById(R.id.reviewButton)

        loadConfig()
        refreshModeUi()
        renderBackgroundStatus()

        manualMode.setOnCheckedChangeListener { _, checked -> if (checked) refreshModeUi() }
        automaticMode.setOnCheckedChangeListener { _, checked -> if (checked) refreshModeUi() }

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            if (saveConfig()) status.text = "Configuration saved"
        }

        findViewById<Button>(R.id.fetchButton).setOnClickListener {
            if (!ensureSmsPermission()) return@setOnClickListener
            if (!saveConfig()) return@setOnClickListener
            fetchAndDisplay()
        }

        reviewButton.setOnClickListener {
            if (!ensureSmsPermission()) return@setOnClickListener
            if (currentItems.isEmpty()) {
                status.text = "Fetch the list first"
            } else {
                reviewOneByOne(0)
            }
        }

        startBackgroundButton.setOnClickListener {
            if (!ensureSmsPermission()) return@setOnClickListener
            if (!saveConfig()) return@setOnClickListener
            val config = GatewayPreferences.load(this)
            if (config.mode != GatewayMode.AUTOMATIC) {
                status.text = "Select Automatic mode first"
                return@setOnClickListener
            }
            startBackground()
        }

        stopBackgroundButton.setOnClickListener {
            GatewayPreferences.setBackgroundEnabled(this, false)
            stopService(Intent(this, GatewayForegroundService::class.java))
            renderBackgroundStatus()
        }
    }

    private fun loadConfig() {
        val config = GatewayPreferences.load(this)
        sourceUrl.setText(config.sourceUrl)
        callbackUrl.setText(config.callbackUrl)
        jwt.setText(config.jwt)
        pollSeconds.setText(config.pollSeconds.toString())
        manualMode.isChecked = config.mode == GatewayMode.MANUAL
        automaticMode.isChecked = config.mode == GatewayMode.AUTOMATIC
    }

    private fun readConfig(): GatewayConfig {
        val seconds = pollSeconds.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 30
        return GatewayConfig(
            sourceUrl = sourceUrl.text.toString(),
            callbackUrl = callbackUrl.text.toString(),
            jwt = jwt.text.toString(),
            pollSeconds = seconds,
            mode = if (automaticMode.isChecked) GatewayMode.AUTOMATIC else GatewayMode.MANUAL
        )
    }

    private fun saveConfig(): Boolean {
        val config = readConfig()
        if (!config.valid) {
            status.text = "Enter valid URLs, a JWT and polling seconds >= 1"
            return false
        }
        GatewayPreferences.save(this, config)
        if (config.mode == GatewayMode.MANUAL && GatewayPreferences.isBackgroundEnabled(this)) {
            GatewayPreferences.setBackgroundEnabled(this, false)
            stopService(Intent(this, GatewayForegroundService::class.java))
        }
        return true
    }

    private fun fetchAndDisplay() {
        val config = GatewayPreferences.load(this)
        status.text = "Loading JSON…"
        Thread {
            try {
                val items = http.fetch(config.sourceUrl, config.jwt)
                runOnUiThread {
                    currentItems = items
                    renderItems(items)
                    status.text = "${items.size} SMS loaded"
                }
            } catch (error: Exception) {
                runOnUiThread {
                    currentItems = emptyList()
                    renderItems(emptyList())
                    status.text = "Load failed: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }.start()
    }

    private fun renderItems(items: List<SmsItem>) {
        messageList.removeAllViews()
        if (items.isEmpty()) {
            messageList.addView(TextView(this).apply { text = "No SMS in the JSON array" })
            return
        }
        items.forEachIndexed { index, item ->
            messageList.addView(TextView(this).apply {
                text = "${index + 1}. ${item.id}\nTo: ${item.to}\n${item.text}"
                setPadding(0, 14, 0, 14)
            })
        }
    }

    private fun reviewOneByOne(index: Int) {
        if (index >= currentItems.size) {
            status.text = "Review complete"
            return
        }
        val item = currentItems[index]
        AlertDialog.Builder(this)
            .setTitle("SMS ${index + 1} of ${currentItems.size}")
            .setMessage("ID: ${item.id}\nTo: ${item.to}\n\n${item.text}")
            .setPositiveButton("Send") { _, _ -> sendManual(item, index) }
            .setNeutralButton("Skip") { _, _ -> reviewOneByOne(index + 1) }
            .setNegativeButton("Stop", null)
            .show()
    }

    private fun sendManual(item: SmsItem, index: Int) {
        val config = GatewayPreferences.load(this)
        status.text = "Sending ${item.id}…"
        Thread {
            val result = SmsSendCoordinator.sendAndWait(this, item)
            val callbackError = try {
                http.report(config.callbackUrl, config.jwt, item, result)
                null
            } catch (error: Exception) {
                error.message ?: error.javaClass.simpleName
            }
            runOnUiThread {
                status.text = buildString {
                    append(item.id)
                    append(if (result.ok) " → OK" else " → KO: ${result.error}")
                    if (callbackError != null) append(" · callback failed: $callbackError")
                }
                reviewOneByOne(index + 1)
            }
        }.start()
    }

    private fun refreshModeUi() {
        val manual = manualMode.isChecked
        findViewById<Button>(R.id.fetchButton).visibility = if (manual) View.VISIBLE else View.GONE
        reviewButton.visibility = if (manual) View.VISIBLE else View.GONE
        messageList.visibility = if (manual) View.VISIBLE else View.GONE
        startBackgroundButton.visibility = if (manual) View.GONE else View.VISIBLE
        stopBackgroundButton.visibility = if (manual) View.GONE else View.VISIBLE
    }

    private fun ensureSmsPermission(): Boolean {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.SEND_SMS
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), PERMISSION_REQUEST)
            status.text = "Grant SMS permission, then press the action again"
            return false
        }
        return true
    }

    private fun startBackground() {
        GatewayPreferences.setBackgroundEnabled(this, true)
        ContextCompat.startForegroundService(this, Intent(this, GatewayForegroundService::class.java))
        renderBackgroundStatus()
    }

    private fun renderBackgroundStatus() {
        status.text = if (GatewayPreferences.isBackgroundEnabled(this)) {
            "Automatic polling enabled"
        } else {
            "Automatic polling stopped"
        }
    }

    companion object {
        private const val PERMISSION_REQUEST = 100
    }
}
