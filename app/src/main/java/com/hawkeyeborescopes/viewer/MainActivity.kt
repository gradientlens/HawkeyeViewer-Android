package com.hawkeyeborescopes.viewer

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hawkeyeborescopes.viewer.databinding.ActivityMainBinding
import com.jiangdg.usbcamera.UVCCameraHelper
import com.serenegiant.usb.widget.CameraViewInterface

class MainActivity : AppCompatActivity(), CameraViewInterface.Callback {

    private lateinit var binding: ActivityMainBinding
    private var cameraHelper: UVCCameraHelper? = null
    private var isRequest = false
    private var isPreview = false

    companion object {
        private const val TAG = "HawkeyeCamera"
        private const val REQUEST_PERMISSION = 1
    }

    private val deviceConnectListener = object : UVCCameraHelper.OnMyDevConnectListener {
        override fun onAttachDev(device: UsbDevice?) {
            Log.d(TAG, "USB device attached: ${device?.deviceName}")
            showStatus("USB device attached: ${device?.deviceName}")
            if (!isRequest) {
                isRequest = true
                Log.d(TAG, "Requesting USB permission")
                cameraHelper?.requestPermission(0)
            }
        }

        override fun onDettachDev(device: UsbDevice?) {
            Log.d(TAG, "USB device detached")
            showStatus("USB device detached")
            if (isRequest) {
                isRequest = false
                cameraHelper?.closeCamera()
            }
        }

        override fun onConnectDev(device: UsbDevice?, isConnected: Boolean) {
            if (!isConnected) {
                Log.e(TAG, "Failed to connect camera")
                showStatus("Failed to connect. Check resolution params")
                Toast.makeText(this@MainActivity, "Camera connection failed", Toast.LENGTH_LONG).show()
                isPreview = false
            } else {
                Log.d(TAG, "Camera connected successfully")
                isPreview = true
                showStatus("Camera connected successfully")
            }
        }

        override fun onDisConnectDev(device: UsbDevice?) {
            Log.d(TAG, "Camera disconnected")
            showStatus("Camera disconnected")
            isPreview = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setupUI()
            checkPermissions()
            initializeCamera()

            // Handle USB device attached intent
            if (intent?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                showStatus("USB device detected - Initializing...")
            }
        } catch (e: Exception) {
            showStatus("Error initializing: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun initializeCamera() {
        try {
            Log.d(TAG, "Initializing camera...")
            val cameraView = binding.cameraView as CameraViewInterface
            cameraView.setCallback(this)

            cameraHelper = UVCCameraHelper.getInstance()
            cameraHelper?.setDefaultFrameFormat(UVCCameraHelper.FRAME_FORMAT_MJPEG)
            cameraHelper?.initUSBMonitor(this, cameraView, deviceConnectListener)

            Log.d(TAG, "Camera initialized successfully")
            showStatus("Camera initialized - Connect USB camera")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing camera", e)
            showStatus("Error initializing camera: ${e.message}")
            Toast.makeText(this, "Camera initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupUI() {
        // Controls panel initially hidden
        binding.controlsPanel.visibility = View.GONE

        // Settings button toggles control panel
        binding.settingsButton.setOnClickListener {
            binding.controlsPanel.visibility = if (binding.controlsPanel.visibility == View.VISIBLE) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }

        // Start camera button
        binding.startButton.setOnClickListener {
            if (cameraHelper != null) {
                cameraHelper?.requestPermission(0)
                showStatus("Starting camera...")
            }
        }

        // Stop camera button
        binding.stopButton.setOnClickListener {
            cameraHelper?.closeCamera()
            isPreview = false
            showStatus("Camera stopped")
        }

        // Capture button
        binding.captureButton.setOnClickListener {
            if (isPreview) {
                captureImage()
            } else {
                showStatus("Start camera first")
            }
        }

        // Record button
        binding.recordButton.setOnClickListener {
            if (isPreview) {
                toggleRecording()
            } else {
                showStatus("Start camera first")
            }
        }

        // Brightness control
        binding.brightnessSeekBar.max = 100
        binding.brightnessSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (cameraHelper != null && cameraHelper!!.isCameraOpened) {
                    cameraHelper?.setModelValue(UVCCameraHelper.MODE_BRIGHTNESS, progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // Contrast control
        binding.contrastSeekBar.max = 100
        binding.contrastSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (cameraHelper != null && cameraHelper!!.isCameraOpened) {
                    cameraHelper?.setModelValue(UVCCameraHelper.MODE_CONTRAST, progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // Saturation control
        binding.saturationSeekBar.max = 100
        binding.saturationSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                // Saturation control not available in this library version
                // Could be added later with UVCCamera.PU_SATURATION if supported by camera
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }

        // Storage permissions for Android 12 and below
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        // Notification permission for Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_PERMISSION)
        }
    }

    private fun captureImage() {
        // TODO: Implement image capture using UVCCameraHelper
        showStatus("Image capture - Coming soon")
    }

    private fun toggleRecording() {
        // TODO: Implement video recording using UVCCameraHelper
        showStatus("Video recording - Coming soon")
    }

    private fun showStatus(message: String) {
        runOnUiThread {
            binding.statusText.text = message
            binding.statusText.visibility = View.VISIBLE
            binding.statusText.postDelayed({
                binding.statusText.visibility = View.GONE
            }, 3000)
        }
    }

    override fun onStart() {
        super.onStart()
        cameraHelper?.registerUSB()
    }

    override fun onStop() {
        super.onStop()
        cameraHelper?.unregisterUSB()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraHelper?.release()
    }

    // CameraViewInterface.Callback implementations
    override fun onSurfaceCreated(view: CameraViewInterface?, surface: Surface?) {
        // Surface is ready
    }

    override fun onSurfaceChanged(view: CameraViewInterface?, surface: Surface?, width: Int, height: Int) {
        // Surface size changed
    }

    override fun onSurfaceDestroy(view: CameraViewInterface?, surface: Surface?) {
        // Surface being destroyed
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_PERMISSION -> {
                val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (granted) {
                    Log.d(TAG, "All permissions granted")
                    showStatus("Permissions granted")
                } else {
                    Log.w(TAG, "Some permissions denied")
                    showStatus("Some permissions denied - app may not work correctly")
                    Toast.makeText(this, "Please grant all permissions for full functionality", Toast.LENGTH_LONG).show()
                }

                // Log individual permission results
                permissions.forEachIndexed { index, permission ->
                    val result = if (grantResults[index] == PackageManager.PERMISSION_GRANTED) "granted" else "denied"
                    Log.d(TAG, "Permission $permission: $result")
                }
            }
        }
    }
}
