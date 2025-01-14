package com.firefighterremote

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.firefighterremote.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var mqttClient: MqttClient

    private var forwardPressed = false
    private var backwardPressed = false
    private var leftPressed = false
    private var rightPressed = false

    private val brokerUrl = "tcp://broker.hivemq.com:1883"
    private val controlTopic = "rc/control"
    private val statusTopic = "rc/status"
    private val batteryTopic = "rc/baterailevel"
    private val speedTopic = "rc/speed"
    private val waterTopic = "rc/waterlevel"
    private val sprinklerTopic = "rc/pompa"
    private val sirenTopic = "rc/strobo"
    private val reverseTopic = "rc/notification"
    private val cameraIpTopic = "rc/CameraIp"

    private var camIP: String = ""  // Store only the base IP

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize view binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Fullscreen setup
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowInsetsController = window.insetsController
            windowInsetsController?.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
            windowInsetsController?.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }

        // Show loading indicator while trying to connect to MQTT
        binding.loadingIndicator.visibility = View.VISIBLE  // Show Progress Bar
        binding.buttonLayout.visibility = View.GONE  // Hide buttons initially
        binding.toolbar.visibility = View.GONE  // Hide toolbar initially
        binding.btnServoLeft.visibility = View.GONE  // Hide left servo button initially
        binding.btnServoRight.visibility = View.GONE  // Hide right servo button initially

        // Configure WebView
        configureWebView()

        // Set onTouchListeners for each button
        setupButtonListeners()

        // Initialize MQTT client
        initializeMqttClient()
        setupToolbar()
    }

    private fun configureWebView() {
        binding.webView.apply {
            settings.javaScriptEnabled = true  // Enable JavaScript if necessary
            settings.loadWithOverviewMode = true  // Ensures the content fits the screen
            settings.setSupportZoom(false)  // Disable zoom (if not required)
            settings.useWideViewPort = true  // Use wide viewport to handle larger content
            settings.mediaPlaybackRequiresUserGesture = false // To autoplay media (optional)

            // Prevent scrolling in WebView
            isScrollContainer = false
            setOnTouchListener { _, _ -> true }  // Disable touch to avoid scrolling

            // Set WebViewClient to handle loading within WebView
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    Log.d("WebView", "Page loading started: $url")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d("WebView", "Page loading finished: $url")
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    Log.e("WebView", "Error loading page: ${error?.description}")
                }
            }

            if (camIP.isNotEmpty()) {
                Log.d("WebView", "Loading initial URL: http://$camIP:81/stream")
                loadUrl("http://$camIP:81/stream")  // Load the camera stream URL
            }
        }
    }

    private fun setupButtonListeners() {
        binding.btnForward.setOnTouchListener { _, event -> handleButtonTouch(event, "forward"); true }
        binding.btnBackward.setOnTouchListener { _, event -> handleButtonTouch(event, "backward"); true }
        binding.btnLeft.setOnTouchListener { _, event -> handleButtonTouch(event, "left"); true }
        binding.btnRight.setOnTouchListener { _, event -> handleButtonTouch(event, "right"); true }
        binding.btnSprinkler.setOnTouchListener { _, event ->
            handleSprinklerButton(event); true
        }
        binding.btnSiren.setOnTouchListener { _, event -> handleSirenButton(event); true }
        binding.btnServoLeft.setOnTouchListener { _, event ->
            handleServoButton(event, "kiri"); true
        }
        binding.btnServoRight.setOnTouchListener { _, event ->
            handleServoButton(event, "kanan"); true
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun initializeMqttClient() {
        // Connect to MQTT in a background thread
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                mqttClient = MqttClient(brokerUrl, MqttClient.generateClientId(), null)
                val options = MqttConnectOptions().apply {
                    isAutomaticReconnect = true
                    isCleanSession = true
                }

                mqttClient.connect(options)

                // Publish "remote connected" message
                mqttClient.publish(statusTopic, MqttMessage("remote connected".toByteArray()))
                Log.d("MQTT", "Published 'remote connected' to $statusTopic")

                // Subscribe to all necessary topics
                mqttClient.subscribe(batteryTopic)
                mqttClient.subscribe(waterTopic)
                mqttClient.subscribe(speedTopic)
                mqttClient.subscribe(reverseTopic)
                mqttClient.subscribe(cameraIpTopic)  // Subscribe to Camera IP topic
                Log.d("MQTT", "Subscribed to topics")

                mqttClient.setCallback(object : MqttCallback {
                    override fun messageArrived(topic: String, message: MqttMessage) {
                        Log.d("MQTT", "Message arrived: Topic=$topic, Message=${message.toString()}")
                        when (topic) {
                            batteryTopic -> handleBatteryMessage(message)
                            waterTopic -> handleWaterMessage(message)
                            speedTopic -> handleSpeedMessage(message)
                            reverseTopic -> handleReverseNotification(message)
                            cameraIpTopic -> handleCameraIpMessage(message) // Handle Camera IP
                        }
                    }

                    override fun connectionLost(cause: Throwable?) {
                        Log.e("MQTT", "Connection lost: ${cause?.message}")
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                withContext(Dispatchers.Main) {
                    binding.loadingIndicator.visibility = View.GONE
                    binding.buttonLayout.visibility = View.VISIBLE
                    binding.toolbar.visibility = View.VISIBLE
                    binding.btnServoLeft.visibility = View.VISIBLE
                    binding.btnServoRight.visibility = View.VISIBLE
                    enableButtons(true)
                    Toast.makeText(this@MainActivity, "Connected to MQTT Broker", Toast.LENGTH_SHORT).show()
                }

            } catch (e: MqttException) {
                withContext(Dispatchers.Main) {
                    binding.loadingIndicator.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Failed to connect to broker", Toast.LENGTH_LONG).show()
                    enableButtons(false)
                }
                Log.e("MQTT", "Failed to connect: ${e.message}", e)
            }
        }
    }

    private fun handleCameraIpMessage(message: MqttMessage) {
        val newCamIp = message.toString().trim()
        if (newCamIp.isNotEmpty() && newCamIp != camIP) {  // Check if IP is different
            camIP = newCamIp  // Update the base IP
            Log.d("MQTT", "New Camera IP: $camIP:81/stream")

            // Update WebView to show the new camera stream
            runOnUiThread {
                Log.d("WebView", "Loading URL: http://$camIP:81/stream")
                binding.webView.loadUrl("http://$camIP:81/stream")  // Reload WebView with new camera URL
            }
        } else {
            Log.d("MQTT", "Received Camera IP is same as current. No update needed.")
        }
    }

    private fun handleBatteryMessage(message: MqttMessage) {
        val batteryLevel = message.toString().toIntOrNull()
        if (batteryLevel == null) {
            Log.e("MQTT", "Invalid battery level: ${message.toString()}")
            return
        }

        runOnUiThread {
            Log.d("MQTT", "Updating battery UI: $batteryLevel%")
            binding.batteryLevelText.text = "$batteryLevel%"
            val batteryIcon = when {
                batteryLevel >= 75 -> R.drawable.control_battery_full
                batteryLevel >= 50 -> R.drawable.control_battery_75
                batteryLevel >= 25 -> R.drawable.control_battery_50
                batteryLevel >= 1 -> R.drawable.control_battery_25
                else -> R.drawable.control_battery_1
            }
            binding.batteryIcon.setImageResource(batteryIcon)
        }
    }

    private fun handleWaterMessage(message: MqttMessage) {
        val waterLevel = message.toString().toIntOrNull()
        if (waterLevel == null) {
            Log.e("MQTT", "Invalid water level: ${message.toString()}")
            return
        }

        runOnUiThread {
            Log.d("MQTT", "Updating water UI: $waterLevel%")
            binding.waterLevelText.text = "$waterLevel%"
            val waterIcon = when {
                waterLevel >= 80 -> R.drawable.control_water_full
                waterLevel >= 50 -> R.drawable.control_water_50
                else -> R.drawable.control_water_1
            }
            binding.waterIcon.setImageResource(waterIcon)
        }
    }

    private fun handleSpeedMessage(message: MqttMessage) {
        val speed = message.toString().toIntOrNull()
        if (speed == null) {
            Log.e("MQTT", "Invalid speed: ${message.toString()}")
            return
        }

        runOnUiThread {
            Log.d("MQTT", "Updating speed UI: $speed km/h")
            binding.speedText.text = "$speed km/h"
        }
    }

    private fun handleReverseNotification(message: MqttMessage) {
        val notification = message.toString().trim() // Trim any extra whitespace
        runOnUiThread {
            binding.ultrasonicWarning.visibility = if (notification.equals("WARNING", ignoreCase = true)) {
                View.VISIBLE
            } else {
                View.GONE // Hide the warning for "NO WARNING" or other messages
            }
        }
    }

    private fun handleSprinklerButton(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> sendMqttMessage(sprinklerTopic, "pompaHidup")
            MotionEvent.ACTION_UP -> sendMqttMessage(sprinklerTopic, "pompaMati")
        }
    }

    private fun handleSirenButton(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> sendMqttMessage(sirenTopic, "stroboHidup")
            MotionEvent.ACTION_UP -> sendMqttMessage(sirenTopic, "stroboMati")
        }
    }

    private fun handleServoButton(event: MotionEvent, direction: String) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> sendMqttMessage(sprinklerTopic, direction)
            MotionEvent.ACTION_UP -> sendMqttMessage(sprinklerTopic, "stop")
        }
    }

    private fun sendMqttMessage(topic: String, payload: String) {
        if (mqttClient.isConnected) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    mqttClient.publish(topic, MqttMessage(payload.toByteArray()))
                    Log.d("MQTT", "Published '$payload' to $topic")
                } catch (e: MqttException) {
                    Log.e("MQTT", "Failed to publish message: ${e.message}", e)
                }
            }
        } else {
            runOnUiThread {
                Toast.makeText(this, "MQTT not connected", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleButtonTouch(event: MotionEvent, direction: String) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                when (direction) {
                    "forward" -> forwardPressed = true
                    "backward" -> backwardPressed = true
                    "left" -> leftPressed = true
                    "right" -> rightPressed = true
                }
                sendCommand()
            }
            MotionEvent.ACTION_UP -> {
                when (direction) {
                    "forward" -> forwardPressed = false
                    "backward" -> backwardPressed = false
                    "left" -> leftPressed = false
                    "right" -> rightPressed = false
                }
                sendCommand()
            }
        }
    }

    private fun sendCommand() {
        if (mqttClient.isConnected) {
            val command = when {
                forwardPressed && leftPressed -> "forward_left"
                forwardPressed && rightPressed -> "forward_right"
                backwardPressed && leftPressed -> "backward_left"
                backwardPressed && rightPressed -> "backward_right"
                forwardPressed -> "forward"
                backwardPressed -> "backward"
                leftPressed -> "left"
                rightPressed -> "right"
                else -> "stop"
            }

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    mqttClient.publish(controlTopic, MqttMessage(command.toByteArray()))
                    Log.d("MQTT", "Published command: '$command' to $controlTopic")
                } catch (e: MqttException) {
                    Log.e("MQTT", "Failed to publish command: ${e.message}", e)
                }
            }
        } else {
            runOnUiThread {
                Toast.makeText(this, "MQTT not connected", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enableButtons(isEnabled: Boolean) {
        binding.btnForward.isEnabled = isEnabled
        binding.btnBackward.isEnabled = isEnabled
        binding.btnLeft.isEnabled = isEnabled
        binding.btnRight.isEnabled = isEnabled
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (::mqttClient.isInitialized && mqttClient.isConnected) {
                    mqttClient.disconnect()
                    Log.d("MQTT", "Disconnected from MQTT broker")
                }
            } catch (e: MqttException) {
                Log.e("MQTT", "Failed to disconnect: ${e.message}", e)
            }
        }
    }
}