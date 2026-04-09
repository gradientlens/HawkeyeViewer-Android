package com.hawkeyeborescopes.viewer

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hawkeyeborescopes.viewer.databinding.ActivityMainBinding
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraActivity
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : CameraActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isRecording = false
    private var currentRecordingPath: String? = null
    private var isSettingsPanelOpen = false
    private var buttonHelper: UsbButtonHelper? = null
    private var mTextureView: AspectRatioTextureView? = null
    private var wasCameraOpen = false
    @Volatile private var hardwareCaptureInFlight = false
    @Volatile private var hardwareButtonCaptureInFlight = false
    @Volatile private var hardwareCaptureStartedAtMs = 0L
    @Volatile private var latestPreviewBytes: ByteArray? = null
    @Volatile private var latestPreviewWidth = 0
    @Volatile private var latestPreviewHeight = 0
    @Volatile private var latestPreviewFormatName = ""
    @Volatile private var latestPreviewTimestampMs = 0L
    @Volatile private var hasLoggedPreviewFrame = false

    private val previewDataCallback = object : IPreviewDataCallBack {
        override fun onPreviewData(data: ByteArray?, width: Int, height: Int, format: IPreviewDataCallBack.DataFormat) {
            if (data == null) {
                return
            }
            if (!hasLoggedPreviewFrame) {
                hasLoggedPreviewFrame = true
                writeLog("previewDataCallback: first frame format=${format.name} size=${data.size} ${width}x${height}")
            }
            latestPreviewBytes = data.copyOf()
            latestPreviewWidth = width
            latestPreviewHeight = height
            latestPreviewFormatName = format.name
            latestPreviewTimestampMs = System.currentTimeMillis()
        }
    }

    companion object {
        private const val TAG = "HawkeyeCamera"
        private const val REQUEST_PERMISSION = 1
        private const val HARDWARE_CAPTURE_INITIAL_DELAY_MS = 0L
        private const val HARDWARE_CAPTURE_RETRY_DELAY_MS = 150L
        private const val HARDWARE_CAPTURE_MAX_ATTEMPTS = 5
        private const val MIN_VALID_HARDWARE_CAPTURE_BYTES = 20_000L
        private const val MAX_PREVIEW_FRAME_AGE_MS = 1_000L
        private const val CAPTURE_FEEDBACK_DURATION_MS = 350L
        private const val MAX_VISIBLE_HARDWARE_CAPTURE_AGE_MS = 2_000L
    }

    private fun writeLog(message: String) {
        try {
            Log.d(TAG, message)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val logFile = File(getExternalFilesDir(null), "hawkeye_debug.log")
            FileWriter(logFile, true).use { writer ->
                writer.appendLine("[$timestamp] $message")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log", e)
        }
    }

    // ========================
    // CameraActivity overrides
    // ========================

    override fun getRootView(layoutInflater: LayoutInflater): View? {
        binding = ActivityMainBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun getCameraView(): IAspectRatio {
        return AspectRatioTextureView(this).also {
            mTextureView = it
        }
    }

    override fun getCameraViewContainer(): ViewGroup {
        return binding.cameraContainer
    }

    override fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(720)
            .setPreviewHeight(720)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .setAudioSource(
                if (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK
                    == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION)
                    CameraRequest.AudioSource.NONE
                else CameraRequest.AudioSource.SOURCE_AUTO
            )
            .setAspectRatioShow(false)
            .setCaptureRawImage(false)
            .setRawPreviewData(false)
            .create()
    }

    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                writeLog("Camera OPENED")
                wasCameraOpen = true
                showStatus("Camera ready")

                // Re-apply shader adjustments from current slider values
                pushShaderAdjustments()

                // Re-apply mirror/flip transform (needed after resume)
                updateTransform()
                addPreviewDataCallBack(previewDataCallback)

                // Log storage location
                if (isUsingRemovableStorage()) {
                    writeLog("Storage: USB removable drive detected")
                    showStatus("Camera ready — saving to USB")
                }

                // Start button listener for physical borescope buttons
                buttonHelper?.stop()
                buttonHelper = null
                val camera = self as? CameraUVC
                if (camera == null) {
                    writeLog("UsbButtonHelper: current camera does not expose CameraUVC bridge hooks")
                } else {
                    buttonHelper = UsbButtonHelper(
                        camera = camera,
                        usbDevice = self.device,
                        onCapturePressed = { runOnUiThread { triggerHardwareCapture(fromHardwareButton = true) } },
                        onRecordPressed = { runOnUiThread { toggleRecording() } },
                        writeLog = { msg2 -> writeLog(msg2) }
                    )
                    if (buttonHelper?.start() != true) {
                        writeLog("UsbButtonHelper: failed to start hardware bridge")
                    }
                }
            }
            ICameraStateCallBack.State.CLOSED -> {
                writeLog("Camera CLOSED")
                showStatus("Camera disconnected")
                removePreviewDataCallBack(previewDataCallback)
                clearLatestPreviewFrame()
                hardwareCaptureInFlight = false
                hardwareButtonCaptureInFlight = false
                hardwareCaptureStartedAtMs = 0L
                buttonHelper?.stop()
                buttonHelper = null
            }
            ICameraStateCallBack.State.ERROR -> {
                writeLog("Camera ERROR: $msg")
                showStatus("Camera error: $msg")
                removePreviewDataCallBack(previewDataCallback)
                clearLatestPreviewFrame()
                hardwareCaptureInFlight = false
                hardwareButtonCaptureInFlight = false
                hardwareCaptureStartedAtMs = 0L
                buttonHelper?.stop()
                buttonHelper = null
            }
        }
    }

    // =====================
    // Lifecycle & UI setup
    // =====================

    override fun initView() {
        super.initView()
        val sessionId = SimpleDateFormat("HHmmss", Locale.US).format(Date())
        writeLog("=== APP STARTING (libausbc 3.3.3) session=$sessionId ===")

        try {
            checkPermissions()
            setupUI()
            writeLog("UI setup complete, waiting for camera...")
            showStatus("Ready - Connect USB camera")
        } catch (e: Exception) {
            writeLog("FATAL ERROR in initView: ${e.message}")
            writeLog("Stack trace: ${e.stackTraceToString()}")
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onStop() {
        writeLog("onStop: cleaning up camera client")
        // Save adjustment settings before stopping (D-pad doesn't trigger onStopTrackingTouch)
        saveImageControls()
        // Explicitly tear down the camera client BEFORE the surface listener
        // fires onSurfaceTextureDestroyed. This ensures clean state for resume.
        // Double-call from onSurfaceTextureDestroyed is safe (no-op when client is null).
        unRegisterMultiCamera()
        super.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Close the adjust panel instead of exiting the app
        if (isSettingsPanelOpen) {
            isSettingsPanelOpen = false
            binding.controlsPanel.visibility = View.GONE
            binding.settingsIcon.setColorFilter(
                ContextCompat.getColor(this, R.color.text_secondary))
            // Move focus to the adjust button so the user is back where they started
            binding.settingsButton.requestFocus()
            return
        }
        super.onBackPressed()
    }

    override fun onStart() {
        super.onStart()
        // On resume (not first launch), re-register the camera client.
        // On first launch, mTextureView isn't available yet — initData()'s
        // surface listener handles that case via onSurfaceTextureAvailable.
        if (wasCameraOpen && mTextureView?.isAvailable == true) {
            writeLog("onStart: resuming — re-registering camera")
            registerMultiCamera()
        }
    }

    override fun clear() {
        try {
            // Stop button listener
            buttonHelper?.stop()
            buttonHelper = null
            removePreviewDataCallBack(previewDataCallback)
            clearLatestPreviewFrame()
            hardwareCaptureInFlight = false
            hardwareButtonCaptureInFlight = false
            hardwareCaptureStartedAtMs = 0L

            // Stop recording if active
            if (isRecording) {
                captureVideoStop()
                isRecording = false
            }
        } catch (e: Exception) {
            writeLog("Error in clear: ${e.message}")
        }
        super.clear()
    }

    private fun setupUI() {
        // Controls panel initially hidden
        binding.controlsPanel.visibility = View.GONE

        // Header button click listeners
        setupHeaderButtons()

        // Settings panel controls
        setupCameraControls()
        setupImageControls()
        setupTransformControls()
        setupCollapsibleSections()
    }

    private fun setupHeaderButtons() {
        // Capture/Still button
        binding.captureButton.setOnClickListener {
            writeLog("Capture button pressed")
            if (isRecording) {
                writeLog("Capture button: using preview-frame still path during recording")
                triggerHardwareCapture(fromHardwareButton = false)
            } else {
                doCapture()
            }
        }

        // Record/Video button
        binding.recordButton.setOnClickListener {
            writeLog("Record button pressed")
            toggleRecording()
        }

        // Gallery button - opens media browser
        binding.galleryButton.setOnClickListener {
            writeLog("Gallery button pressed")
            val intent = Intent(this, GalleryActivity::class.java)
            // Show media from the preferred storage location
            val storageBase = getPreferredStorageBase()
            intent.putExtra(GalleryActivity.EXTRA_STORAGE_DIR, storageBase.absolutePath)
            intent.putExtra(GalleryActivity.EXTRA_STORAGE_LABEL, getStorageLabel())
            startActivity(intent)
        }

        // Settings/Adjust button - toggles panel
        binding.settingsButton.setOnClickListener {
            isSettingsPanelOpen = !isSettingsPanelOpen
            binding.controlsPanel.visibility = if (isSettingsPanelOpen) View.VISIBLE else View.GONE

            // Update icon color to show active state
            val color = if (isSettingsPanelOpen) {
                ContextCompat.getColor(this, R.color.hawkeye_primary)
            } else {
                ContextCompat.getColor(this, R.color.text_secondary)
            }
            binding.settingsIcon.setColorFilter(color)

            writeLog("Settings panel visibility changed to: $isSettingsPanelOpen")
        }
    }

    private fun setupCameraControls() {
        // Start camera button - request permission to trigger camera open
        binding.startButton.setOnClickListener {
            writeLog("Start button pressed, requesting device list...")
            val devices = getDeviceList()
            if (devices.isNullOrEmpty()) {
                showStatus("No USB camera found")
                writeLog("No USB devices found")
            } else {
                writeLog("Found ${devices.size} USB devices, requesting permission for first...")
                requestPermission(devices[0])
                showStatus("Starting camera...")
            }
        }

        // Stop camera button
        binding.stopButton.setOnClickListener {
            try {
                // Stop recording first if active
                if (isRecording) {
                    captureVideoStop()
                    isRecording = false
                    updateRecordingUI(false)
                }
                closeCamera()
                wasCameraOpen = false
                showStatus("Camera stopped")
            } catch (e: Exception) {
                writeLog("Error stopping camera: ${e.message}")
            }
        }
    }

    // =====================
    // Software Image Adjustments (GPU shader-based)
    // Slider 0-100 mapped to shader uniform ranges
    // =====================

    // Current shader values (kept in sync with sliders)
    private var shaderBrightness = 1.0f   // 0.0-2.0, 1.0=normal
    private var shaderContrast = 1.0f     // 0.0-2.0, 1.0=normal
    private var shaderSaturation = 1.0f   // 0.0-2.0, 1.0=normal
    private var shaderHue = 0.0f          // -PI to +PI radians, 0=normal
    private var shaderGamma = 1.0f        // 0.2-3.0, 1.0=normal
    private var shaderSharpness = 1.0f    // 0.0-2.0, 1.0=moderate (slider 50)

    private val adjustPrefs by lazy {
        getSharedPreferences("image_adjustments", MODE_PRIVATE)
    }

    private fun setupImageControls() {
        // All sliders: 50 = normal/default for each control
        setupShaderSeekBar(binding.brightnessSeekBar, binding.brightnessValue, "brightness", binding.brightnessRow, binding.brightnessLabel)
        setupShaderSeekBar(binding.contrastSeekBar, binding.contrastValue, "contrast", binding.contrastRow, binding.contrastLabel)
        setupShaderSeekBar(binding.saturationSeekBar, binding.saturationValue, "saturation", binding.saturationRow, binding.saturationLabel)
        setupShaderSeekBar(binding.hueSeekBar, binding.hueValue, "hue", binding.hueRow, binding.hueLabel)
        setupShaderSeekBar(binding.gammaSeekBar, binding.gammaValue, "gamma", binding.gammaRow, binding.gammaLabel)
        setupShaderSeekBar(binding.sharpnessSeekBar, binding.sharpnessValue, "sharpness", binding.sharpnessRow, binding.sharpnessLabel)

        // Defaults button
        binding.defaultsButton.setOnClickListener {
            resetImageControlsToDefaults()
        }

        // Restore last saved adjustments (or sync defaults to shader on first launch)
        restoreImageControls()
        // Always push current values to shader so field defaults and slider defaults match
        pushShaderAdjustments()
    }

    private fun setupShaderSeekBar(seekBar: SeekBar, valueText: android.widget.TextView, name: String,
                                    row: android.view.View, label: android.widget.TextView) {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                valueText.text = progress.toString()
                if (fromUser) {
                    applyShaderAdjustment(name, progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveImageControls()
            }
        })
        // Prevent D-pad left/right from moving focus when seekbar is at min/max
        seekBar.setOnKeyListener { v, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                val sb = v as SeekBar
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT && sb.progress <= 0) {
                    true // consume — don't move focus
                } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && sb.progress >= sb.max) {
                    true // consume — don't move focus
                } else false
            } else false
        }
        // Highlight row when seekbar gets D-pad focus (TV remote navigation)
        seekBar.setOnFocusChangeListener { _, hasFocus ->
            row.isSelected = hasFocus
            label.setTextColor(resources.getColor(
                if (hasFocus) R.color.hawkeye_primary else R.color.text_secondary, theme))
        }
    }

    private fun applyShaderAdjustment(name: String, sliderValue: Int) {
        // All sliders: 0-100 where 50 = no change (1.0x)
        when (name) {
            "brightness" -> shaderBrightness = sliderValue / 50.0f           // 0→0.0, 50→1.0, 100→2.0
            "contrast" -> shaderContrast = sliderValue / 50.0f               // 0→0.0, 50→1.0, 100→2.0
            "saturation" -> shaderSaturation = sliderValue / 50.0f           // 0→0.0, 50→1.0, 100→2.0
            "hue" -> shaderHue = ((sliderValue - 50) / 50.0f) * Math.PI.toFloat()  // 0→-π, 50→0, 100→+π
            "gamma" -> {
                // Piecewise: 0→0.2, 50→1.0, 100→3.0
                shaderGamma = if (sliderValue <= 50) {
                    0.2f + (sliderValue / 50.0f) * 0.8f   // 0→0.2, 50→1.0
                } else {
                    1.0f + ((sliderValue - 50) / 50.0f) * 2.0f  // 50→1.0, 100→3.0
                }
            }
            "sharpness" -> {
                // 0→0.0 (none), 50→1.0 (moderate), 100→2.0 (strong)
                shaderSharpness = sliderValue / 50.0f
            }
        }
        pushShaderAdjustments()
    }

    private fun pushShaderAdjustments() {
        setImageAdjustments(shaderBrightness, shaderContrast, shaderSaturation, shaderHue, shaderGamma, shaderSharpness)
        // Update the still capture applier so captureImageInternal produces WYSIWYG stills
        updateCaptureAdjustmentApplier()
    }

    private fun updateCaptureAdjustmentApplier() {
        val b = shaderBrightness
        val c = shaderContrast
        val s = shaderSaturation
        val g = shaderGamma
        val mirror = binding.mirrorCheckBox.isChecked
        val flip = binding.flipCheckBox.isChecked
        val needsColor = Math.abs(b - 1.0f) > 0.01f || Math.abs(c - 1.0f) > 0.01f ||
            Math.abs(s - 1.0f) > 0.01f || Math.abs(g - 1.0f) > 0.01f
        // If all defaults and no transform, remove applier
        if (!needsColor && !mirror && !flip) {
            setImageAdjustmentApplier(null)
            return
        }
        setImageAdjustmentApplier(object : com.jiangdg.ausbc.MultiCameraClient.ICamera.ImageAdjustmentApplier {
            override fun applyToFile(path: String) {
                applyStillAdjustments(path)
            }
        })
    }

    private fun saveImageControls() {
        adjustPrefs.edit()
            .putInt("brightness", binding.brightnessSeekBar.progress)
            .putInt("contrast", binding.contrastSeekBar.progress)
            .putInt("saturation", binding.saturationSeekBar.progress)
            .putInt("hue", binding.hueSeekBar.progress)
            .putInt("gamma", binding.gammaSeekBar.progress)
            .putInt("sharpness", binding.sharpnessSeekBar.progress)
            .putBoolean("mirror", binding.mirrorCheckBox.isChecked)
            .putBoolean("flip", binding.flipCheckBox.isChecked)
            .apply()
    }

    private fun restoreImageControls() {
        val hasSaved = adjustPrefs.contains("brightness")
        if (!hasSaved) return  // First launch — use XML defaults (all 50)

        val b = adjustPrefs.getInt("brightness", 50)
        val c = adjustPrefs.getInt("contrast", 50)
        val s = adjustPrefs.getInt("saturation", 50)
        val h = adjustPrefs.getInt("hue", 50)
        val g = adjustPrefs.getInt("gamma", 50)
        val sh = adjustPrefs.getInt("sharpness", 50)
        val savedMirror = adjustPrefs.getBoolean("mirror", false)
        val savedFlip = adjustPrefs.getBoolean("flip", false)

        binding.brightnessSeekBar.progress = b
        binding.contrastSeekBar.progress = c
        binding.saturationSeekBar.progress = s
        binding.hueSeekBar.progress = h
        binding.gammaSeekBar.progress = g
        binding.sharpnessSeekBar.progress = sh

        binding.brightnessValue.text = b.toString()
        binding.contrastValue.text = c.toString()
        binding.saturationValue.text = s.toString()
        binding.hueValue.text = h.toString()
        binding.gammaValue.text = g.toString()
        binding.sharpnessValue.text = sh.toString()
        binding.mirrorCheckBox.isChecked = savedMirror
        binding.flipCheckBox.isChecked = savedFlip

        // Apply restored values to shader
        applyShaderAdjustment("brightness", b)
        applyShaderAdjustment("contrast", c)
        applyShaderAdjustment("saturation", s)
        applyShaderAdjustment("hue", h)
        applyShaderAdjustment("gamma", g)
        applyShaderAdjustment("sharpness", sh)
    }

    private fun resetImageControlsToDefaults() {
        // Reset all sliders to 50 (1.0x / no change)
        binding.brightnessSeekBar.progress = 50
        binding.contrastSeekBar.progress = 50
        binding.saturationSeekBar.progress = 50
        binding.hueSeekBar.progress = 50
        binding.gammaSeekBar.progress = 50
        binding.sharpnessSeekBar.progress = 50

        // Reset shader values
        shaderBrightness = 1.0f
        shaderContrast = 1.0f
        shaderSaturation = 1.0f
        shaderHue = 0.0f
        shaderGamma = 1.0f
        shaderSharpness = 1.0f
        pushShaderAdjustments()

        // Update value labels
        binding.brightnessValue.text = "50"
        binding.contrastValue.text = "50"
        binding.saturationValue.text = "50"
        binding.hueValue.text = "50"
        binding.gammaValue.text = "50"
        binding.sharpnessValue.text = "50"

        // Save defaults
        saveImageControls()

        showStatus("Reset to defaults")
    }

    private fun setupTransformControls() {
        // Mirror checkbox (horizontal flip)
        binding.mirrorCheckBox.setOnCheckedChangeListener { _, isChecked ->
            writeLog("Mirror: $isChecked")
            saveImageControls()
            updateTransform()
            updateCaptureAdjustmentApplier()
        }

        // Flip checkbox (vertical flip)
        binding.flipCheckBox.setOnCheckedChangeListener { _, isChecked ->
            writeLog("Flip: $isChecked")
            saveImageControls()
            updateTransform()
            updateCaptureAdjustmentApplier()
        }
    }

    private fun updateTransform() {
        if (!isCameraOpened()) return
        val mirror = binding.mirrorCheckBox.isChecked
        val flip = binding.flipCheckBox.isChecked
        val rotateType = when {
            mirror && flip -> RotateType.ANGLE_180      // both = 180° rotation
            mirror -> RotateType.FLIP_LEFT_RIGHT         // horizontal mirror
            flip -> RotateType.FLIP_UP_DOWN              // vertical flip
            else -> RotateType.ANGLE_0                   // no transform
        }
        writeLog("Setting transform: $rotateType (mirror=$mirror, flip=$flip)")
        setRotateType(rotateType)
    }

    private fun setupCollapsibleSections() {
        // Camera section
        binding.cameraSectionHeader.setOnClickListener {
            toggleSection(binding.cameraSectionContent, binding.cameraSectionChevron)
        }

        // Image section
        binding.imageSectionHeader.setOnClickListener {
            toggleSection(binding.imageSectionContent, binding.imageSectionChevron)
        }

        // Transform section
        binding.transformSectionHeader.setOnClickListener {
            toggleSection(binding.transformSectionContent, binding.transformSectionChevron)
        }
    }

    private fun toggleSection(content: View, chevron: ImageView) {
        if (content.visibility == View.VISIBLE) {
            content.visibility = View.GONE
            chevron.rotation = -90f
        } else {
            content.visibility = View.VISIBLE
            chevron.rotation = 0f
        }
    }

    private fun updateRecordingUI(recording: Boolean) {
        runOnUiThread {
            val color = if (recording) {
                ContextCompat.getColor(this, R.color.hawkeye_primary)
            } else {
                ContextCompat.getColor(this, R.color.text_secondary)
            }
            binding.recordIcon.setColorFilter(color)
            binding.recordLabel.text = if (recording) "STOP" else getString(R.string.btn_video)
            binding.recordLabel.setTextColor(color)
        }
    }

    // =====================
    // Permissions
    // =====================

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
                    writeLog("All permissions granted")
                    showStatus("Permissions granted")
                } else {
                    writeLog("Some permissions denied")
                    showStatus("Some permissions denied - app may not work correctly")
                    Toast.makeText(this, "Please grant all permissions for full functionality", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // =====================
    // Capture & Recording
    // =====================

    /**
     * Returns the best available storage base directory.
     * If a removable USB drive is available, uses it; otherwise uses internal storage.
     * getExternalFilesDirs(null) returns [internal, usb1, usb2, ...].
     */
    private fun getPreferredStorageBase(): File {
        val dirs = getExternalFilesDirs(null)
        // dirs[0] is always internal; dirs[1+] are removable (USB drives, SD cards)
        // Check for a writable removable location
        if (dirs.size > 1) {
            for (i in 1 until dirs.size) {
                val dir = dirs[i]
                if (dir != null && dir.canWrite()) {
                    writeLog("Using removable storage: ${dir.absolutePath}")
                    return dir
                }
            }
        }
        return getExternalFilesDir(null) ?: filesDir
    }

    /** Check if we're currently saving to removable (USB) storage */
    private fun isUsingRemovableStorage(): Boolean {
        val dirs = getExternalFilesDirs(null)
        if (dirs.size > 1) {
            for (i in 1 until dirs.size) {
                val dir = dirs[i]
                if (dir != null && dir.canWrite()) return true
            }
        }
        return false
    }

    /** Label for the current storage location */
    private fun getStorageLabel(): String {
        return if (isUsingRemovableStorage()) "USB" else "Internal"
    }

    private fun getAppPicturesDir(): File {
        val dir = File(getPreferredStorageBase(), "Pictures")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getAppMoviesDir(): File {
        val dir = File(getPreferredStorageBase(), "Movies")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Get the internal storage base (always internal, for gallery browsing) */
    private fun getInternalStorageBase(): File {
        return getExternalFilesDir(null) ?: filesDir
    }

    /**
     * Capture a still image. Named doCapture() to avoid shadowing
     * the parent CameraActivity.captureImage(callback, path) method.
     */
private fun triggerHardwareCapture(fromHardwareButton: Boolean) {
    writeLog("triggerHardwareCapture: scheduled")
    hardwareCaptureInFlight = true
    hardwareButtonCaptureInFlight = fromHardwareButton
    hardwareCaptureStartedAtMs = System.currentTimeMillis()
    showHardwareCaptureTriggeredFeedback()
    binding.captureButton.postDelayed({
        captureHardwareStill(attempt = 0)
        }, HARDWARE_CAPTURE_INITIAL_DELAY_MS)
    }

    private fun captureHardwareStill(attempt: Int) {
        val ready = isCameraOpened()
        writeLog("triggerHardwareCapture: attempt=${attempt + 1} ready=$ready")
        // Try TextureView bitmap first — it already has GL shader adjustments baked in
        // so brightness/contrast/etc. match the preview exactly (no software re-processing)
        if (capturePreviewBitmap(announceStart = false)) {
            return
        }
        // Fallback to camera capture with software adjustments
        if (attempt + 1 < HARDWARE_CAPTURE_MAX_ATTEMPTS) {
            binding.captureButton.postDelayed({
                captureHardwareStill(attempt + 1)
            }, HARDWARE_CAPTURE_RETRY_DELAY_MS)
            return
        }
        doCapture(skipReadyCheck = true)
    }

    private fun captureCachedPreviewFrame(announceStart: Boolean = true): Boolean {
        val bytes = latestPreviewBytes
        val width = latestPreviewWidth
        val height = latestPreviewHeight
        val formatName = latestPreviewFormatName
        val ageMs = System.currentTimeMillis() - latestPreviewTimestampMs
        if (bytes == null || width <= 0 || height <= 0 || ageMs > MAX_PREVIEW_FRAME_AGE_MS) {
            writeLog("captureCachedPreviewFrame: no fresh preview frame available (format=$formatName age=${ageMs}ms)")
            return false
        }

        val imagePath = File(
            getAppPicturesDir(),
            "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        ).absolutePath

        return try {
            writeLog("captureCachedPreviewFrame: saving cached $formatName frame to $imagePath")
            if (announceStart) {
                showStatus("Capturing...")
                flashCaptureIcon()
            }

            savePreviewFrameAsJpeg(bytes, width, height, formatName, imagePath)
            val file = File(imagePath)
            if (!file.exists() || file.length() < MIN_VALID_HARDWARE_CAPTURE_BYTES) {
                writeLog("captureCachedPreviewFrame: image too small (${file.length()} bytes)")
                file.delete()
                return false
            }
            applyStillAdjustments(imagePath)
            writeLog("captureCachedPreviewFrame: onComplete - path=$imagePath")
            showStillCapturedFeedback()
            notifyMediaScannerAsync(imagePath)
            true
        } catch (e: Exception) {
            writeLog("captureCachedPreviewFrame: EXCEPTION - ${e.message}")
            writeLog("captureCachedPreviewFrame: stack - ${e.stackTraceToString()}")
            false
        }
    }

    private fun savePreviewFrameAsJpeg(
        bytes: ByteArray,
        width: Int,
        height: Int,
        formatName: String,
        imagePath: String
    ) {
        when (formatName.uppercase(Locale.US)) {
            "RGBA" -> {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
                    FileOutputStream(imagePath).use { output ->
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)) {
                            throw IllegalStateException("Bitmap compression failed")
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            "NV21" -> saveYuvPreviewFrame(bytes, width, height, ImageFormat.NV21, imagePath)
            "YUY2", "YUYV" -> saveYuvPreviewFrame(bytes, width, height, ImageFormat.YUY2, imagePath)
            "MJPEG", "JPEG" -> {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IllegalStateException("Bitmap decode failed for $formatName")
                try {
                    FileOutputStream(imagePath).use { output ->
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)) {
                            throw IllegalStateException("Bitmap compression failed")
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            else -> throw IllegalStateException("Unsupported preview format $formatName")
        }
    }

    private fun saveYuvPreviewFrame(
        bytes: ByteArray,
        width: Int,
        height: Int,
        imageFormat: Int,
        imagePath: String
    ) {
        val yuvImage = YuvImage(bytes, imageFormat, width, height, null)
        FileOutputStream(imagePath).use { output ->
            if (!yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, output)) {
                throw IllegalStateException("YUV compression failed")
            }
        }
    }

    private fun capturePreviewBitmap(announceStart: Boolean = true): Boolean {
        var bitmap = mTextureView?.bitmap
        if (bitmap == null) {
            writeLog("capturePreviewBitmap: texture view bitmap unavailable")
            return false
        }

        val imagePath = File(
            getAppPicturesDir(),
            "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        ).absolutePath

        return try {
            writeLog("capturePreviewBitmap: saving to $imagePath")
            if (announceStart) {
                showStatus("Capturing...")
                flashCaptureIcon()
            }

            // TextureView bitmap already has GL shader adjustments (WYSIWYG)
            // but GL transform (mirror/flip) may not be reflected, so apply in software
            val mirror = binding.mirrorCheckBox.isChecked
            val flip = binding.flipCheckBox.isChecked
            if (mirror || flip) {
                val w = bitmap.width
                val h = bitmap.height
                val matrix = android.graphics.Matrix()
                matrix.setScale(if (mirror) -1f else 1f, if (flip) -1f else 1f, w / 2f, h / 2f)
                val transformed = Bitmap.createBitmap(bitmap, 0, 0, w, h, matrix, true)
                bitmap.recycle()
                bitmap = transformed
            }

            FileOutputStream(imagePath).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)) {
                    throw IllegalStateException("Bitmap compression failed")
                }
            }
            bitmap.recycle()
            val file = File(imagePath)
            if (!file.exists() || file.length() < MIN_VALID_HARDWARE_CAPTURE_BYTES) {
                writeLog("capturePreviewBitmap: image too small (${file.length()} bytes), retrying")
                file.delete()
                return false
            }
            writeLog("capturePreviewBitmap: onComplete - path=$imagePath")
            showStillCapturedFeedback()
            notifyMediaScannerAsync(imagePath)
            true
        } catch (e: Exception) {
            bitmap.recycle()
            writeLog("capturePreviewBitmap: EXCEPTION - ${e.message}")
            writeLog("capturePreviewBitmap: stack - ${e.stackTraceToString()}")
            showStatus("Capture error: ${e.message}")
            false
        }
    }

    /** Apply current adjustments + mirror/flip to a saved JPEG file (WYSIWYG) */
    private fun applyStillAdjustments(path: String) {
        val b = shaderBrightness
        val c = shaderContrast
        val s = shaderSaturation
        val g = shaderGamma
        val mirror = binding.mirrorCheckBox.isChecked
        val flip = binding.flipCheckBox.isChecked
        val needsColor = Math.abs(b - 1.0f) > 0.01f || Math.abs(c - 1.0f) > 0.01f ||
            Math.abs(s - 1.0f) > 0.01f || Math.abs(g - 1.0f) > 0.01f
        if (!needsColor && !mirror && !flip) return

        try {
            val file = java.io.File(path)
            if (!file.exists()) return
            val original = android.graphics.BitmapFactory.decodeFile(path) ?: return
            val w = original.width
            val h = original.height
            // Apply mirror/flip transform
            var bitmap = if (mirror || flip) {
                val matrix = android.graphics.Matrix()
                matrix.setScale(if (mirror) -1f else 1f, if (flip) -1f else 1f, w / 2f, h / 2f)
                val transformed = android.graphics.Bitmap.createBitmap(original, 0, 0, w, h, matrix, true)
                original.recycle()
                transformed
            } else original
            if (!needsColor) {
                // Only transform, no color adjustments
                java.io.FileOutputStream(file).use { fos ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, fos)
                }
                bitmap.recycle()
                writeLog("applyStillAdjustments: mirror=$mirror flip=$flip applied to $path")
                return
            }
            val mutableBitmap = if (bitmap.isMutable) bitmap
                else bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true).also { bitmap.recycle() }
            val pixels = IntArray(w * h)
            mutableBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            val gammaLut = if (Math.abs(g - 1.0f) > 0.01f) {
                val invG = 1.0f / g
                IntArray(256) { i -> (Math.pow(i / 255.0, invG.toDouble()) * 255.0).toInt().coerceIn(0, 255) }
            } else null

            for (i in pixels.indices) {
                val px = pixels[i]
                val a = (px shr 24) and 0xFF
                var rf = ((px shr 16) and 0xFF) * b / 255.0f
                var gf = ((px shr 8) and 0xFF) * b / 255.0f
                var bf = (px and 0xFF) * b / 255.0f
                rf = (rf - 0.5f) * c + 0.5f
                gf = (gf - 0.5f) * c + 0.5f
                bf = (bf - 0.5f) * c + 0.5f
                val luma = 0.2126f * rf + 0.7152f * gf + 0.0722f * bf
                rf = luma + (rf - luma) * s
                gf = luma + (gf - luma) * s
                bf = luma + (bf - luma) * s
                var ri = (rf * 255.0f).toInt().coerceIn(0, 255)
                var gi = (gf * 255.0f).toInt().coerceIn(0, 255)
                var bi = (bf * 255.0f).toInt().coerceIn(0, 255)
                if (gammaLut != null) { ri = gammaLut[ri]; gi = gammaLut[gi]; bi = gammaLut[bi] }
                pixels[i] = (a shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
            mutableBitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            java.io.FileOutputStream(file).use { fos ->
                mutableBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, fos)
            }
            mutableBitmap.recycle()
            writeLog("applyStillAdjustments: b=$b c=$c s=$s g=$g mirror=$mirror flip=$flip applied to $path")
        } catch (e: Exception) {
            writeLog("applyStillAdjustments: EXCEPTION - ${e.message}")
        }
    }

    private fun clearLatestPreviewFrame() {
        latestPreviewBytes = null
        latestPreviewWidth = 0
        latestPreviewHeight = 0
        latestPreviewFormatName = ""
        latestPreviewTimestampMs = 0L
        hasLoggedPreviewFrame = false
    }

    private fun flashCaptureIcon() {
        binding.captureIcon.setColorFilter(ContextCompat.getColor(this, R.color.hawkeye_primary))
        binding.captureIcon.postDelayed({
            binding.captureIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
        }, CAPTURE_FEEDBACK_DURATION_MS)
    }

    private fun showHardwareCaptureTriggeredFeedback() {
        if (!hasWindowFocus()) {
            writeLog("showHardwareCaptureTriggeredFeedback: app not focused")
            return
        }
        showStatus("Capturing image...")
        flashCaptureIcon()
    }

private fun showStillCapturedFeedback() {
    val captureAgeMs = if (hardwareButtonCaptureInFlight) {
        System.currentTimeMillis() - hardwareCaptureStartedAtMs
    } else {
        null
    }
    finishHardwareCapture(success = true, rearmReason = "capture-complete")
    if (!hasWindowFocus()) {
        writeLog("showStillCapturedFeedback: app not focused, skipping visible feedback")
        return
    }
    if (captureAgeMs != null && captureAgeMs > MAX_VISIBLE_HARDWARE_CAPTURE_AGE_MS) {
        writeLog("showStillCapturedFeedback: skipping stale hardware feedback age=${captureAgeMs}ms")
        return
    }
    showStatus("Image captured")
    }

    private fun notifyMediaScannerAsync(path: String) {
        Thread {
            notifyMediaScanner(path)
        }.apply {
            name = "MediaScannerNotify"
            isDaemon = true
            start()
        }
    }

    private fun doCapture(skipReadyCheck: Boolean = false) {
        if (!skipReadyCheck && !isCameraOpened()) {
            writeLog("doCapture: camera not open")
            showStatus("Camera not ready")
            finishHardwareCapture(success = false, rearmReason = "camera-not-open")
            return
        }

        try {
            val captureDir = getAppPicturesDir()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val imagePath = File(captureDir, "IMG_$timestamp.jpg").absolutePath

            writeLog("doCapture: saving to $imagePath")
            showStatus("Capturing...")

            flashCaptureIcon()

            captureImage(object : ICaptureCallBack {
                override fun onBegin() {
                    writeLog("doCapture: onBegin")
                }

                override fun onError(error: String?) {
                    runOnUiThread {
                        finishHardwareCapture(success = false, rearmReason = "camera-capture-error")
                        writeLog("doCapture: onError - $error")
                        showStatus("Capture failed: $error")
                    }
                }

                override fun onComplete(path: String?) {
                    runOnUiThread {
                        writeLog("doCapture: onComplete - path=$path")
                        if (path != null) {
                            showStillCapturedFeedback()
                            notifyMediaScannerAsync(path)
                        } else {
                            finishHardwareCapture(success = false, rearmReason = "camera-capture-null-path")
                            showStatus("Capture failed - null path")
                        }
                    }
                }
            }, imagePath)

            writeLog("doCapture: captureImage() called successfully")
        } catch (e: Exception) {
            finishHardwareCapture(success = false, rearmReason = "camera-capture-exception")
            writeLog("doCapture: EXCEPTION - ${e.message}")
            writeLog("doCapture: stack - ${e.stackTraceToString()}")
            showStatus("Capture error: ${e.message}")
        }
    }

    private fun finishHardwareCapture(success: Boolean, rearmReason: String) {
        val fromHardwareButton = hardwareCaptureInFlight && hardwareButtonCaptureInFlight
        hardwareCaptureInFlight = false
        hardwareButtonCaptureInFlight = false
        hardwareCaptureStartedAtMs = 0L
        if (fromHardwareButton) {
            writeLog("finishHardwareCapture: success=$success rearmReason=$rearmReason")
        }
    }

    private fun notifyMediaScanner(path: String) {
        try {
            val file = File(path)
            if (!file.exists() || file.length() == 0L) {
                writeLog("Media scanner skipped - file missing or empty: $path")
                return
            }
            val isVideo = path.endsWith(".mp4")
            val mimeType = if (isVideo) "video/mp4" else "image/jpeg"
            val collection = if (isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH,
                        if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val uri = contentResolver.insert(collection, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    file.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                }
                writeLog("Saved to gallery: ${file.name}")
            }
        } catch (e: Exception) {
            writeLog("Media scanner error: ${e.message}")
        }
    }

    private fun toggleRecording() {
        if (!isCameraOpened()) {
            showStatus("Camera not ready")
            return
        }

        try {
            if (isRecording) {
                // Stop recording
                writeLog("Stopping recording...")
                captureVideoStop()
                isRecording = false
                updateRecordingUI(false)
                showStatus("Saving video...")
            } else {
                // Start recording
                // NOTE: Library appends .mp4 to the path, so do NOT include .mp4 extension
                val recordDir = getAppMoviesDir()
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                currentRecordingPath = File(recordDir, "VID_$timestamp").absolutePath

                writeLog("Starting recording to: $currentRecordingPath")

                captureVideoStart(object : ICaptureCallBack {
                    override fun onBegin() {
                        runOnUiThread {
                            writeLog("Recording started")
                            isRecording = true
                            updateRecordingUI(true)
                            showStatus("Recording...")
                        }
                    }

                    override fun onError(error: String?) {
                        runOnUiThread {
                            writeLog("Recording error: $error")
                            showStatus("Recording error: $error")
                            isRecording = false
                            updateRecordingUI(false)
                        }
                    }

                    override fun onComplete(path: String?) {
                        runOnUiThread {
                            writeLog("Recording saved: $path")
                            if (path != null) {
                                showStatus("Video saved")
                                notifyMediaScannerAsync(path)
                            }
                        }
                    }
                }, currentRecordingPath)
            }
        } catch (e: Exception) {
            writeLog("Recording error: ${e.message}")
            showStatus("Recording error: ${e.message}")
            isRecording = false
            updateRecordingUI(false)
        }
    }

    // =====================
    // Status display
    // =====================

    private fun showStatus(message: String) {
        runOnUiThread {
            binding.statusText.text = message
            binding.statusText.visibility = View.VISIBLE
            binding.statusText.removeCallbacks(hideStatusRunnable)
            binding.statusText.postDelayed(hideStatusRunnable, 3000)
        }
    }

    private val hideStatusRunnable = Runnable {
        binding.statusText.visibility = View.GONE
    }
}
