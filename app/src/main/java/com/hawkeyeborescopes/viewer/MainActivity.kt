package com.hawkeyeborescopes.viewer

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
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
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import java.io.File
import java.io.FileWriter
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

    companion object {
        private const val TAG = "HawkeyeCamera"
        private const val REQUEST_PERMISSION = 1
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
            .setAudioSource(CameraRequest.AudioSource.SOURCE_AUTO)
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

                // Re-apply mirror/flip transform (needed after resume)
                updateTransform()

                // Start button listener for physical borescope buttons
                self.device.let { dev ->
                    buttonHelper = UsbButtonHelper(
                        context = this@MainActivity,
                        onCapturePressed = { runOnUiThread { doCapture() } },
                        onRecordPressed = { runOnUiThread { toggleRecording() } },
                        writeLog = { msg2 -> writeLog(msg2) }
                    )
                    buttonHelper?.start(dev)
                }
            }
            ICameraStateCallBack.State.CLOSED -> {
                writeLog("Camera CLOSED")
                showStatus("Camera disconnected")
                buttonHelper?.stop()
                buttonHelper = null
            }
            ICameraStateCallBack.State.ERROR -> {
                writeLog("Camera ERROR: $msg")
                showStatus("Camera error: $msg")
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
        writeLog("=== APP STARTING (libausbc 3.3.3) ===")

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
        // Explicitly tear down the camera client BEFORE the surface listener
        // fires onSurfaceTextureDestroyed. This ensures clean state for resume.
        // Double-call from onSurfaceTextureDestroyed is safe (no-op when client is null).
        unRegisterMultiCamera()
        super.onStop()
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
            doCapture()
        }

        // Record/Video button
        binding.recordButton.setOnClickListener {
            writeLog("Record button pressed")
            toggleRecording()
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

    private fun setupImageControls() {
        // Brightness control
        setupSeekBar(binding.brightnessSeekBar, binding.brightnessValue) { progress ->
            if (isCameraOpened()) {
                try { setBrightness(progress) } catch (e: Exception) {
                    writeLog("Failed to set brightness: ${e.message}")
                }
            }
        }

        // Contrast control
        setupSeekBar(binding.contrastSeekBar, binding.contrastValue) { progress ->
            if (isCameraOpened()) {
                try { setContrast(progress) } catch (e: Exception) {
                    writeLog("Failed to set contrast: ${e.message}")
                }
            }
        }

        // Saturation control
        setupSeekBar(binding.saturationSeekBar, binding.saturationValue) { progress ->
            if (isCameraOpened()) {
                try { setSaturation(progress) } catch (e: Exception) {
                    writeLog("Failed to set saturation: ${e.message}")
                }
            }
        }

        // Hue control
        setupSeekBar(binding.hueSeekBar, binding.hueValue) { progress ->
            if (isCameraOpened()) {
                try { setHue(progress) } catch (e: Exception) {
                    writeLog("Failed to set hue: ${e.message}")
                }
            }
        }

        // Gamma control
        setupSeekBar(binding.gammaSeekBar, binding.gammaValue) { progress ->
            if (isCameraOpened()) {
                try { setGamma(progress) } catch (e: Exception) {
                    writeLog("Failed to set gamma: ${e.message}")
                }
            }
        }

        // Sharpness control
        setupSeekBar(binding.sharpnessSeekBar, binding.sharpnessValue) { progress ->
            if (isCameraOpened()) {
                try { setSharpness(progress) } catch (e: Exception) {
                    writeLog("Failed to set sharpness: ${e.message}")
                }
            }
        }

        // Defaults button
        binding.defaultsButton.setOnClickListener {
            resetImageControlsToDefaults()
        }
    }

    private fun setupSeekBar(seekBar: SeekBar, valueText: android.widget.TextView, onProgress: (Int) -> Unit) {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                valueText.text = progress.toString()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { onProgress(it.progress) }
            }
        })
    }

    private fun resetImageControlsToDefaults() {
        // Reset UI sliders to midpoint
        binding.brightnessSeekBar.progress = 50
        binding.contrastSeekBar.progress = 50
        binding.saturationSeekBar.progress = 50
        binding.hueSeekBar.progress = 50
        binding.gammaSeekBar.progress = 50
        binding.sharpnessSeekBar.progress = 50

        // Apply camera-native defaults if camera is open
        if (isCameraOpened()) {
            try {
                resetBrightness()
                resetContrast()
                resetSaturation()
                resetHue()
                resetGamma()
                resetSharpness()

                // Read back actual values and update sliders
                updateSliderFromCamera(binding.brightnessSeekBar, binding.brightnessValue) { getBrightness() }
                updateSliderFromCamera(binding.contrastSeekBar, binding.contrastValue) { getContrast() }
                updateSliderFromCamera(binding.saturationSeekBar, binding.saturationValue) { getSaturation() }
                updateSliderFromCamera(binding.hueSeekBar, binding.hueValue) { getHue() }
                updateSliderFromCamera(binding.gammaSeekBar, binding.gammaValue) { getGamma() }
                updateSliderFromCamera(binding.sharpnessSeekBar, binding.sharpnessValue) { getSharpness() }
            } catch (e: Exception) {
                writeLog("Error resetting image controls: ${e.message}")
            }
        }

        showStatus("Reset to defaults")
    }

    private fun updateSliderFromCamera(seekBar: SeekBar, valueText: android.widget.TextView, getter: () -> Int?) {
        try {
            val value = getter() ?: return
            seekBar.progress = value.coerceIn(0, 100)
            valueText.text = seekBar.progress.toString()
        } catch (e: Exception) {
            writeLog("Failed to read camera value: ${e.message}")
        }
    }

    private fun setupTransformControls() {
        // Mirror checkbox (horizontal flip)
        binding.mirrorCheckBox.setOnCheckedChangeListener { _, isChecked ->
            writeLog("Mirror: $isChecked")
            updateTransform()
        }

        // Flip checkbox (vertical flip)
        binding.flipCheckBox.setOnCheckedChangeListener { _, isChecked ->
            writeLog("Flip: $isChecked")
            updateTransform()
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

    private fun getAppPicturesDir(): File {
        val dir = File(getExternalFilesDir(null), "Pictures")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getAppMoviesDir(): File {
        val dir = File(getExternalFilesDir(null), "Movies")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Capture a still image. Named doCapture() to avoid shadowing
     * the parent CameraActivity.captureImage(callback, path) method.
     */
    private fun doCapture() {
        if (!isCameraOpened()) {
            writeLog("doCapture: camera not open")
            showStatus("Camera not ready")
            return
        }

        try {
            val captureDir = getAppPicturesDir()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val imagePath = File(captureDir, "IMG_$timestamp.jpg").absolutePath

            writeLog("doCapture: saving to $imagePath")
            showStatus("Capturing...")

            // Flash effect on capture icon
            binding.captureIcon.setColorFilter(ContextCompat.getColor(this, R.color.hawkeye_primary))
            binding.captureIcon.postDelayed({
                binding.captureIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
            }, 150)

            captureImage(object : ICaptureCallBack {
                override fun onBegin() {
                    writeLog("doCapture: onBegin")
                }

                override fun onError(error: String?) {
                    runOnUiThread {
                        writeLog("doCapture: onError - $error")
                        showStatus("Capture failed: $error")
                    }
                }

                override fun onComplete(path: String?) {
                    runOnUiThread {
                        writeLog("doCapture: onComplete - path=$path")
                        if (path != null) {
                            showStatus("Image saved")
                            notifyMediaScanner(path)
                        } else {
                            showStatus("Capture failed - null path")
                        }
                    }
                }
            }, imagePath)

            writeLog("doCapture: captureImage() called successfully")
        } catch (e: Exception) {
            writeLog("doCapture: EXCEPTION - ${e.message}")
            writeLog("doCapture: stack - ${e.stackTraceToString()}")
            showStatus("Capture error: ${e.message}")
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
                                notifyMediaScanner(path)
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
