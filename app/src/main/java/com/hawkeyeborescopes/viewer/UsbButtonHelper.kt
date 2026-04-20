package com.hawkeyeborescopes.viewer

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.jiangdg.ausbc.camera.CameraUVC
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Helper class to bridge Hawkeye borescope hardware button events into the
 * Android capture and recording actions.
 *
 * The Windows bridge watches the camera's UVC camera-control notifications:
 * - TILT absolute -> still capture
 * - ROLL absolute -> video toggle
 *
 * This helper mirrors that behavior by listening to the same UVC status
 * callbacks exposed by the Android camera library.
 */
class UsbButtonHelper(
    private val camera: CameraUVC,
    private val usbDevice: UsbDevice?,
    private val onCapturePressed: () -> Unit,
    private val onRecordPressed: () -> Unit,
    private val writeLog: (String) -> Unit,
    private val armRollAtStartup: Boolean = false
) {
    companion object {
        private const val TAG = "UsbButtonHelper"
        private const val DEBOUNCE_MS = 300L
        private const val POLL_INTERVAL_MS = 30L

        private const val ENABLE_RAW_DIAGNOSTICS = true
        private const val FORCED_TILT_REARM_DELAY_MS = 400L
        private const val RESET_DELAY_MS = 60L
        private const val DIRECT_RESET_STEP_DELAY_MS = 24L
        private const val RESET_RETRY_COUNT = 8
        private const val RESET_RETRY_DELAY_MS = 180L
        private const val DIRECT_RESET_VERIFY_DELAY_MS = 120L
        private const val TILT_RESET_SUPPRESSION_MS = 1_500L
        private const val POLL_DIAGNOSTIC_WINDOW_MS = 15_000L
        private const val POLL_DIAGNOSTIC_LOG_INTERVAL_MS = 300L
        private const val NULL_POLL_RECOVERY_THRESHOLD = 6
        private const val USB_TIMEOUT_MS = 200
        private const val TILT_TRIGGER_VALUE = 1
        private const val SELECTOR_PANTILT_ABSOLUTE = 0x0D
        private const val SELECTOR_ROLL_ABSOLUTE = 0x0F
        private const val STATUS_CLASS_CONTROL_CAMERA = 0x11
        private const val STATUS_ATTRIBUTE_VALUE_CHANGE = 0x00
        private const val STATUS_CALLBACK_CLASS = "com.jiangdg.uvc.IStatusCallback"
        private const val BUTTON_CALLBACK_CLASS = "com.jiangdg.uvc.IButtonCallback"
        private const val UVC_GET_CUR = 0x81
        private const val UVC_REQUEST_TYPE_INTERFACE_IN = 0xA1
        private const val UVC_SET_CUR = 0x01
        private const val UVC_REQUEST_TYPE_INTERFACE_OUT = 0x21
    }

    private data class DirectControlTarget(
        val name: String,
        val entityId: Int,
        val selector: Int,
        val length: Int
    )


    private val running = AtomicBoolean(false)
    private val diagnosticLock = Any()
    private val controlLock = Any()
    private var rawUvcCamera: Any? = null
    private var statusCallbackProxy: Any? = null
    private var buttonCallbackProxy: Any? = null
    private var usbManager: UsbManager? = null
    private var controlConnection: UsbDeviceConnection? = null
    private var controlInterface: UsbInterface? = null
    private var diagnosticConnection: UsbDeviceConnection? = null
    private var diagnosticInterface: UsbInterface? = null
    private var diagnosticEndpoint: UsbEndpoint? = null
    private var diagnosticThread: Thread? = null
    private var pollingThread: Thread? = null
    private var directPanTiltTarget: DirectControlTarget? = null
    private var directRollTarget: DirectControlTarget? = null
    private var directPanTiltBaseline: ByteArray? = null
    private var directRollBaseline: ByteArray? = null
    @Volatile private var probedInterfaceNumber: Int? = null
    private var lastCaptureTime = 0L
    private var lastRecordTime = 0L
    private var lastRollValue: Int? = null
    private var tiltDefaultValue: Int? = null
    private var lastPolledPanTiltPayload: ByteArray? = null
    private var lastPolledTiltValue: Int? = null
    private var lastPolledRollValue: Int? = null
    @Volatile private var suppressedPanTiltPayload: ByteArray? = null
    @Volatile private var suppressedTiltValue: Int? = null
    @Volatile private var suppressTiltUntilMs = 0L
    @Volatile private var diagnosticPollUntilMs = 0L
    @Volatile private var lastDiagnosticPollLogMs = 0L
    @Volatile private var nullPollStreak = 0

    @Volatile private var pendingTiltRearm = false
    @Volatile private var tiltRearmThread: Thread? = null
    @Volatile private var forcedTiltRearmThread: Thread? = null
    @Volatile private var tiltTriggerVerificationInFlight = false

    fun start(): Boolean {
        usbManager = camera.ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        logDeviceInterfaces()

        rawUvcCamera = extractRawUvcCamera()
        if (rawUvcCamera == null) {
            writeLog("$TAG: Could not access raw UVC camera instance")
            return false
        }
        val nativePtr = getNativePtr(rawUvcCamera!!)
        writeLog("$TAG: rawUvcCamera class=${rawUvcCamera!!.javaClass.name} nativePtr=$nativePtr")

        if (!createCallbackProxies()) {
            rawUvcCamera = null
            return false
        }

        running.set(true)
        if (!setUvcCallbacks(statusCallbackProxy, buttonCallbackProxy)) {
            running.set(false)
            return false
        }
        writeLog("$TAG: Registered UVC status/button callbacks (nativePtr=$nativePtr, non-zero means active)")
        if (ENABLE_RAW_DIAGNOSTICS) {
            startRawDiagnosticReader()
            // If diagnostic connection failed (no interrupt endpoint), fall back to
            // a simpler control connection that only needs the control interface
            val hasDiag = synchronized(diagnosticLock) { diagnosticConnection != null }
            val hasCtrl = synchronized(controlLock) { controlConnection != null }
            if (!hasDiag && !hasCtrl) {
                writeLog("$TAG: Diagnostic connection unavailable, falling back to control connection")
                openControlConnection()
            }
            writeLog("$TAG: Diagnostic mode: reading interrupt endpoint + direct GET_CUR polling")
        } else {
            writeLog("$TAG: Raw diagnostic reader disabled for normal operation")
            writeLog("$TAG: Using native JNI polling only (no controlConnection, preserves libuvc interrupt)")
        }
        // ARM the tilt: SET_CUR pan_tilt to [0,0,0,0, 0,0,0,0] (pan=0, tilt=0)
        // Windows AmCap requires setting tilt to 0 before the button will register.
        // The default value is 3600 (0x0E10) which may be an "unarmed" state.
        armTiltForButtonDetection()
        startControlPolling()
        // Arm roll to 0 on devices that need it (phone — tablet's roll starts at 0 naturally).
        // Deferred 2s to avoid USB contention during video stream startup.
        if (armRollAtStartup) {
            Thread {
                try { Thread.sleep(2000) } catch (_: InterruptedException) { return@Thread }
                if (running.get()) {
                    writeLog("$TAG: Deferred roll arm starting (2s after camera open)")
                    armRollForButtonDetection()
                }
            }.start()
        }
        startDiagnosticPollWindow("camera-open")
        return true
    }

    /**
     * Opens a dedicated USB connection for UVC control transfers (GET_CUR / SET_CUR).
     * This connection is independent of the camera library's own connection, so it
     * stays valid even when the camera is busy with capture operations.
     * Mirrors the Windows bridge's stable IAMCameraControl handle.
     */
    private fun openControlConnection() {
        val device = usbDevice ?: run {
            writeLog("$TAG: No UsbDevice available for control connection")
            return
        }
        val manager = usbManager ?: return

        // Find the UVC Video Control interface (class=VIDEO, subclass=1)
        var controlIntf: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_VIDEO && intf.interfaceSubclass == 1) {
                controlIntf = intf
                break
            }
        }
        if (controlIntf == null) {
            writeLog("$TAG: No UVC control interface found on device")
            return
        }

        synchronized(controlLock) {
            val conn = manager.openDevice(device)
            if (conn == null) {
                writeLog("$TAG: Failed to open device for control connection")
                return
            }
            // Claim the interface — needed on Android 9 (TV) and some devices
            // where controlTransfer fails without it. We use force=true to steal
            // it from the kernel driver if necessary. The interrupt endpoint is
            // non-functional on this device anyway.
            val claimed = conn.claimInterface(controlIntf, true)
            writeLog("$TAG: Control connection opened on interface=${controlIntf.id} (claimed=$claimed)")
            controlConnection = conn
            controlInterface = controlIntf
        }

        // Probe for pan/tilt and roll control targets using the new connection
        probeControlTargets()
    }

    /**
     * Probes UVC controls using the camera library's own connection, avoiding
     * claimInterface which would steal the interface from libuvc's native code.
     */
    private fun probeControlTargetsViaCameraConnection() {
        // Find the UVC Video Control interface number (class=VIDEO, subclass=1)
        val device = usbDevice ?: run {
            writeLog("$TAG: No UsbDevice for camera-connection probe")
            return
        }
        var interfaceNumber: Int? = null
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_VIDEO && intf.interfaceSubclass == 1) {
                interfaceNumber = intf.id
                break
            }
        }
        if (interfaceNumber == null) {
            writeLog("$TAG: No UVC control interface found on device")
            return
        }
        probedInterfaceNumber = interfaceNumber

        val probeTargets = listOf(
            DirectControlTarget("absolutePanTilt", 1, SELECTOR_PANTILT_ABSOLUTE, 8),
            DirectControlTarget("absoluteRoll", 1, SELECTOR_ROLL_ABSOLUTE, 2)
        ) + (2..6).flatMap { entityId ->
            listOf(
                DirectControlTarget("absolutePanTilt", entityId, SELECTOR_PANTILT_ABSOLUTE, 8),
                DirectControlTarget("absoluteRoll", entityId, SELECTOR_ROLL_ABSOLUTE, 2)
            )
        }
        var foundPanTilt = false
        var foundRoll = false
        for (target in probeTargets) {
            val payload = readViaCameraConnection(target, interfaceNumber) ?: continue
            val hex = payloadToHex(payload)
            writeLog(
                "$TAG: Control probe (camera-conn) ${target.name} entity=${target.entityId} " +
                    "selector=0x${target.selector.toString(16)} payload=[$hex]"
            )
            when (target.selector) {
                SELECTOR_PANTILT_ABSOLUTE -> if (!foundPanTilt) {
                    foundPanTilt = true
                    directPanTiltTarget = target
                    if (directPanTiltBaseline == null) directPanTiltBaseline = payload.copyOf()
                    val tilt = decodeTiltValue(payload)
                    if (tilt != null) tiltDefaultValue = tilt
                    writeLog("$TAG: Pan/tilt baseline set, tiltDefault=$tilt")
                }
                SELECTOR_ROLL_ABSOLUTE -> if (!foundRoll) {
                    foundRoll = true
                    directRollTarget = target
                    if (directRollBaseline == null) directRollBaseline = payload.copyOf()
                }
            }
        }
        if (!foundPanTilt) writeLog("$TAG: Control probe: no pan/tilt target responded")
        if (!foundRoll) writeLog("$TAG: Control probe: no roll target responded")
        writeLog("$TAG: Probed via camera library connection (no interface claim)")
    }

    /**
     * Probes UVC camera-terminal controls using the dedicated control connection.
     * Populates directPanTiltTarget/directRollTarget and their baselines.
     */
    private fun probeControlTargets() {
        val interfaceNumber = synchronized(controlLock) { controlInterface?.id } ?: return
        probedInterfaceNumber = interfaceNumber
        val probeTargets = listOf(
            DirectControlTarget("absolutePanTilt", 1, SELECTOR_PANTILT_ABSOLUTE, 8),
            DirectControlTarget("absoluteRoll", 1, SELECTOR_ROLL_ABSOLUTE, 2)
        ) + (2..6).flatMap { entityId ->
            listOf(
                DirectControlTarget("absolutePanTilt", entityId, SELECTOR_PANTILT_ABSOLUTE, 8),
                DirectControlTarget("absoluteRoll", entityId, SELECTOR_ROLL_ABSOLUTE, 2)
            )
        }
        var foundPanTilt = false
        var foundRoll = false
        for (target in probeTargets) {
            val payload = readControlValue(target, interfaceNumber) ?: continue
            val hex = payloadToHex(payload)
            writeLog(
                "$TAG: Control probe ${target.name} entity=${target.entityId} " +
                    "selector=0x${target.selector.toString(16)} payload=[$hex]"
            )
            when (target.selector) {
                SELECTOR_PANTILT_ABSOLUTE -> if (!foundPanTilt) {
                    foundPanTilt = true
                    directPanTiltTarget = target
                    if (directPanTiltBaseline == null) directPanTiltBaseline = payload.copyOf()
                    val tilt = decodeTiltValue(payload)
                    if (tilt != null) tiltDefaultValue = tilt
                    writeLog("$TAG: Pan/tilt baseline set, tiltDefault=$tilt")
                }
                SELECTOR_ROLL_ABSOLUTE -> if (!foundRoll) {
                    foundRoll = true
                    directRollTarget = target
                    if (directRollBaseline == null) directRollBaseline = payload.copyOf()
                }
            }
        }
        if (!foundPanTilt) writeLog("$TAG: Control probe: no pan/tilt target responded")
        if (!foundRoll) writeLog("$TAG: Control probe: no roll target responded")
    }

    /** Try GET_CUR on a single control using any available connection. */
    private fun readAnySingleControl(target: DirectControlTarget, interfaceNumber: Int): ByteArray? {
        return readControlValue(target, interfaceNumber)
            ?: tryReadDirectControl(target, interfaceNumber)
            ?: readViaCameraConnection(target, interfaceNumber)
    }

    /** GET_CUR via the dedicated control connection. */
    private fun readControlValue(target: DirectControlTarget, interfaceNumber: Int): ByteArray? {
        val conn = synchronized(controlLock) { controlConnection } ?: return null
        return try {
            val buffer = ByteArray(target.length)
            val count = conn.controlTransfer(
                UVC_REQUEST_TYPE_INTERFACE_IN,
                UVC_GET_CUR,
                target.selector shl 8,
                (target.entityId shl 8) or interfaceNumber,
                buffer,
                buffer.size,
                USB_TIMEOUT_MS
            )
            if (count > 0) buffer.copyOf(count) else null
        } catch (_: Throwable) { null }
    }

    /** SET_CUR via the dedicated control connection. */
    private fun writeControlValue(target: DirectControlTarget, interfaceNumber: Int, payload: ByteArray): Int {
        val conn = synchronized(controlLock) { controlConnection } ?: return -1
        return try {
            conn.controlTransfer(
                UVC_REQUEST_TYPE_INTERFACE_OUT,
                UVC_SET_CUR,
                target.selector shl 8,
                (target.entityId shl 8) or interfaceNumber,
                payload.copyOf(target.length),
                target.length,
                USB_TIMEOUT_MS
            )
        } catch (_: Throwable) { -1 }
    }

    /**
     * Gets the camera library's own UsbDeviceConnection via mCtrlBlock.getConnection().
     * This connection is always alive while the camera is streaming, making it a reliable
     * fallback when our dedicated controlConnection/diagnosticConnection are unavailable.
     */
    private fun getCameraLibraryConnection(): UsbDeviceConnection? {
        return try {
            // CameraUVC -> mCtrlBlock (USBMonitor.UsbControlBlock) -> getConnection()
            val ctrlBlockField = findField(camera.javaClass, "mCtrlBlock") ?: return null
            val ctrlBlock = ctrlBlockField.get(camera) ?: return null
            val getConn = ctrlBlock.javaClass.getMethod("getConnection")
            getConn.invoke(ctrlBlock) as? UsbDeviceConnection
        } catch (_: Throwable) {
            null
        }
    }

    /** GET_CUR via the camera library's own USB connection (fallback). */
    private fun readViaCameraConnection(target: DirectControlTarget, interfaceNumber: Int): ByteArray? {
        val conn = getCameraLibraryConnection() ?: return null
        return try {
            val buffer = ByteArray(target.length)
            val count = conn.controlTransfer(
                UVC_REQUEST_TYPE_INTERFACE_IN,
                UVC_GET_CUR,
                target.selector shl 8,
                (target.entityId shl 8) or interfaceNumber,
                buffer,
                buffer.size,
                USB_TIMEOUT_MS
            )
            if (count > 0) buffer.copyOf(count) else null
        } catch (_: Throwable) { null }
    }

    /** SET_CUR via the camera library's own USB connection (fallback). */
    private fun writeViaCameraConnection(target: DirectControlTarget, interfaceNumber: Int, payload: ByteArray): Int {
        val conn = getCameraLibraryConnection() ?: return -1
        return try {
            conn.controlTransfer(
                UVC_REQUEST_TYPE_INTERFACE_OUT,
                UVC_SET_CUR,
                target.selector shl 8,
                (target.entityId shl 8) or interfaceNumber,
                payload.copyOf(target.length),
                target.length,
                USB_TIMEOUT_MS
            )
        } catch (_: Throwable) { -1 }
    }

    private fun closeControlConnection() {
        synchronized(controlLock) {
            try {
                controlInterface?.let { controlConnection?.releaseInterface(it) }
                controlConnection?.close()
            } catch (t: Throwable) {
                writeLog("$TAG: Failed to close control connection: ${t.message}")
            }
            controlConnection = null
            controlInterface = null
        }
    }

    private fun logDeviceInterfaces() {
        val device = usbDevice
        if (device == null) {
            writeLog("$TAG: No UsbDevice available for interface diagnostics")
            return
        }
        writeLog(
            "$TAG: Device vid=0x${device.vendorId.toString(16)} pid=0x${device.productId.toString(16)} " +
                "class=${device.deviceClass} subclass=${device.deviceSubclass} protocol=${device.deviceProtocol} " +
                "interfaces=${device.interfaceCount}"
        )
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            writeLog(
                "$TAG: Interface $i id=${intf.id} alt=${intf.alternateSetting} name=${intf.name ?: "<none>"} " +
                    "class=${intf.interfaceClass} subclass=${intf.interfaceSubclass} protocol=${intf.interfaceProtocol} " +
                    "endpoints=${intf.endpointCount}"
            )
            for (j in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(j)
                writeLog(
                    "$TAG: Interface $i endpoint $j addr=${ep.address} dir=${ep.direction} type=${ep.type} " +
                        "maxPacket=${ep.maxPacketSize} interval=${ep.interval}"
                )
            }
        }
    }

    private fun extractRawUvcCamera(): Any? {
        return try {
            val field = findField(camera.javaClass, "mUvcCamera") ?: return null
            field.get(camera)
        } catch (t: Throwable) {
            writeLog("$TAG: Failed to reflect mUvcCamera: ${t.message}")
            null
        }
    }

    private fun ensureActiveRawUvcCamera(): Any? {
        val current = rawUvcCamera
        val currentPtr = current?.let { getNativePtr(it) } ?: 0L
        if (current != null && currentPtr != 0L) {
            return current
        }
        val refreshed = extractRawUvcCamera()
        val refreshedPtr = refreshed?.let { getNativePtr(it) } ?: 0L
        if (refreshed != null && refreshed !== current) {
            writeLog(
                "$TAG: Refreshed raw UVC camera instance class=${refreshed.javaClass.name} " +
                    "nativePtr=$refreshedPtr"
            )
        } else if (refreshed != null && currentPtr == 0L) {
            writeLog(
                "$TAG: Rechecked raw UVC camera instance class=${refreshed.javaClass.name} " +
                    "nativePtr=$refreshedPtr"
            )
        }
        rawUvcCamera = refreshed
        return refreshed
    }

    private fun createCallbackProxies(): Boolean {
        val uvc = rawUvcCamera ?: return false
        return try {
            val classLoader = uvc.javaClass.classLoader
            val statusCallbackClass = Class.forName(STATUS_CALLBACK_CLASS, true, classLoader)
            val buttonCallbackClass = Class.forName(BUTTON_CALLBACK_CLASS, true, classLoader)

            statusCallbackProxy = Proxy.newProxyInstance(
                classLoader,
                arrayOf(statusCallbackClass),
                StatusCallbackHandler()
            )
            buttonCallbackProxy = Proxy.newProxyInstance(
                classLoader,
                arrayOf(buttonCallbackClass),
                ButtonCallbackHandler()
            )
            true
        } catch (t: Throwable) {
            writeLog("$TAG: Failed to create callback proxies: ${t.message}")
            false
        }
    }

    private fun setUvcCallbacks(status: Any?, button: Any?): Boolean {
        val uvc = rawUvcCamera ?: return false
        try {
            val classLoader = uvc.javaClass.classLoader
            val statusCallbackClass = Class.forName(STATUS_CALLBACK_CLASS, true, classLoader)
            val buttonCallbackClass = Class.forName(BUTTON_CALLBACK_CLASS, true, classLoader)
            val statusMethod = uvc.javaClass.getMethod("setStatusCallback", statusCallbackClass)
            val buttonMethod = uvc.javaClass.getMethod("setButtonCallback", buttonCallbackClass)
            statusMethod.invoke(uvc, status)
            buttonMethod.invoke(uvc, button)
            return true
        } catch (t: Throwable) {
            writeLog("$TAG: Failed to register callbacks: ${t.message}")
            return false
        }
    }

    private fun handleStatusEvent(
        statusClass: Int,
        event: Int,
        selector: Int,
        statusAttribute: Int,
        data: ByteBuffer?
    ) {
        if (!running.get()) {
            return
        }

        val payload = data?.let { copyBuffer(it) } ?: ByteArray(0)
        val hex = payload.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        writeLog(
            "$TAG: status class=$statusClass event=$event selector=0x${selector.toString(16)} " +
                "attr=$statusAttribute payload=[$hex]"
        )

        if (statusClass != STATUS_CLASS_CONTROL_CAMERA ||
            statusAttribute != STATUS_ATTRIBUTE_VALUE_CHANGE
        ) {
            return
        }

        when (selector) {
            SELECTOR_PANTILT_ABSOLUTE -> handlePanTiltEvent(payload)
            SELECTOR_ROLL_ABSOLUTE -> handleRollEvent(payload)
        }
    }

    private fun handlePanTiltEvent(payload: ByteArray) {
        val tilt = decodeTiltValue(payload)
        if (tilt == null) {
            writeLog("$TAG: Ignoring pan/tilt event with unexpected payload length=${payload.size}")
            return
        }

        writeLog("$TAG: decoded tilt=$tilt")

        val now = System.currentTimeMillis()
        if (tilt != TILT_TRIGGER_VALUE || now - lastCaptureTime <= DEBOUNCE_MS) {
            return
        }

        val defaultTilt = tiltDefaultValue ?: lastPolledTiltValue ?: decodeTiltValue(directPanTiltBaseline ?: ByteArray(0))
        if (defaultTilt == null || tilt == defaultTilt) {
            return
        }
        dispatchTiltCapture("status-callback")
    }

    private fun handleRollEvent(payload: ByteArray) {
        val roll = decodeRollValue(payload)
        if (roll == null) {
            writeLog("$TAG: Ignoring roll event with unexpected payload length=${payload.size}")
            return
        }

        writeLog("$TAG: decoded roll=$roll")

        val previous = lastRollValue
        lastRollValue = roll
        if (previous == null && roll == 0) {
            writeLog("$TAG: Initial roll idle state observed")
            return
        }
        if (previous != null && previous == roll) {
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastRecordTime <= DEBOUNCE_MS) {
            return
        }

        lastRecordTime = now
        writeLog("$TAG: ROLL trigger detected -> video toggle")
        onRecordPressed()
    }

    private fun startRawDiagnosticReader() {
        reopenDiagnosticConnection("initial")
    }

    private fun startControlPolling() {
        pollingThread = Thread {
            while (running.get()) {
                try {
                    // When ENABLE_RAW_DIAGNOSTICS is true, we claim the interface
                    // and use direct USB control transfers. This gives us both
                    // interrupt endpoint reads AND direct GET_CUR polling.
                    // When false, use nativeGetTilt/nativeGetRoll via libuvc JNI.
                    val tilt: Int?
                    val roll: Int?
                    val panTiltPayload: ByteArray?
                    if (ENABLE_RAW_DIAGNOSTICS) {
                        // Direct USB control transfers (we have the interface claimed)
                        panTiltPayload = readDirectPanTiltPayload()
                        tilt = panTiltPayload?.let(::decodeTiltValue)
                        roll = readDirectRollValue()
                    } else {
                        val uvc = rawUvcCamera ?: ensureActiveRawUvcCamera()
                        tilt = uvc?.let { readCameraControlValue(it, "nativeGetTilt") }
                        roll = uvc?.let { readCameraControlValue(it, "nativeGetRoll") }
                        panTiltPayload = null
                    }
                    if (tilt == null && roll == null) {
                        nullPollStreak += 1
                        if (nullPollStreak % NULL_POLL_RECOVERY_THRESHOLD == 0) {
                            recoverControlAccess("null-poll-streak")
                        }
                    } else {
                        nullPollStreak = 0
                    }
                    maybeLogDiagnosticPoll(tilt, roll)

                    if (panTiltPayload != null && tilt != null) {
                        val previousTilt = lastPolledTiltValue
                        val previousPayload = lastPolledPanTiltPayload
                        lastPolledTiltValue = tilt
                        lastPolledPanTiltPayload = panTiltPayload.copyOf()
                        if (previousTilt == null) {
                            tiltDefaultValue = tiltDefaultValue ?: tilt
                            writeLog("$TAG: Initial polled tilt=$tilt")
                        } else if (previousPayload != null && !previousPayload.contentEquals(panTiltPayload)) {
                            writeLog(
                                "$TAG: Polled pan/tilt payload changed " +
                                    "[${payloadToHex(previousPayload)}] -> [${payloadToHex(panTiltPayload)}] " +
                                    "tilt=$previousTilt->$tilt"
                            )
                            if (shouldSuppressPanTiltChange(tilt, panTiltPayload)) {
                                writeLog("$TAG: Suppressed pan/tilt change during reset window")
                                if (directPanTiltBaseline?.contentEquals(panTiltPayload) == true) {
                                    tiltDefaultValue = tilt
                                }
                                continue
                            }
                            startDiagnosticPollWindow("tilt-change")
                            if (shouldTriggerPolledPanTilt(previousPayload, panTiltPayload, previousTilt, tilt)) {
                                handlePolledTiltTrigger()
                            }
                        }
                    } else if (tilt != null) {
                        val previousTilt = lastPolledTiltValue
                        lastPolledTiltValue = tilt
                        if (previousTilt == null) {
                            tiltDefaultValue = tiltDefaultValue ?: tilt
                            writeLog("$TAG: Initial polled tilt=$tilt")
                        } else if (previousTilt != tilt) {
                            writeLog("$TAG: Polled tilt changed $previousTilt -> $tilt")
                            if (shouldSuppressTiltChange(tilt)) {
                                writeLog("$TAG: Suppressed tilt change to $tilt during reset window")
                                lastPolledTiltValue = tilt
                                tiltDefaultValue = tilt
                                continue
                            }
                            startDiagnosticPollWindow("tilt-change")
                            if (shouldTriggerPolledTilt(previousTilt, tilt)) {
                                handlePolledTiltTrigger()
                            }
                        }
                    }

                    if (roll != null) {
                        val previousRoll = lastPolledRollValue
                        lastPolledRollValue = roll
                        if (previousRoll == null) {
                            writeLog("$TAG: Initial polled roll=$roll")
                        } else if (previousRoll != roll) {
                            writeLog("$TAG: Polled roll changed $previousRoll -> $roll")
                            startDiagnosticPollWindow("roll-change")
                            handlePolledRollTrigger(roll)
                        }
                    }
                } catch (t: Throwable) {
                    if (running.get()) {
                        writeLog("$TAG: Control polling error: ${t.message}")
                    }
                    return@Thread
                }

                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.apply {
            name = "UsbButtonPoll"
            isDaemon = true
            start()
        }
    }

    private fun startDiagnosticPollWindow(reason: String, durationMs: Long = POLL_DIAGNOSTIC_WINDOW_MS) {
        val now = System.currentTimeMillis()
        diagnosticPollUntilMs = maxOf(diagnosticPollUntilMs, now + durationMs)
        lastDiagnosticPollLogMs = 0L
        writeLog("$TAG: Diagnostic poll window started ($reason) for ${durationMs}ms")
    }

    private fun maybeLogDiagnosticPoll(tilt: Int?, roll: Int?) {
        val now = System.currentTimeMillis()
        if (now > diagnosticPollUntilMs) {
            return
        }
        if (lastDiagnosticPollLogMs != 0L && now - lastDiagnosticPollLogMs < POLL_DIAGNOSTIC_LOG_INTERVAL_MS) {
            return
        }
        lastDiagnosticPollLogMs = now
        writeLog(
            "$TAG: Diagnostic poll sample tilt=${tilt?.toString() ?: "<null>"} " +
                "roll=${roll?.toString() ?: "<null>"}"
        )
    }

    private fun probeDirectControlTargets() {
        val interfaceNumber = diagnosticInterface?.id
            ?: synchronized(controlLock) { controlInterface?.id }
            ?: return
        var foundPanTilt = false
        var foundRoll = false
        val probeTargets = listOf(
            DirectControlTarget("absolutePanTilt", 1, SELECTOR_PANTILT_ABSOLUTE, 8),
            DirectControlTarget("absoluteRoll", 1, SELECTOR_ROLL_ABSOLUTE, 2)
        ) + (2..6).flatMap { entityId ->
            listOf(
                DirectControlTarget("absolutePanTilt", entityId, SELECTOR_PANTILT_ABSOLUTE, 8),
                DirectControlTarget("absoluteRoll", entityId, SELECTOR_ROLL_ABSOLUTE, 2)
            )
        }

        for (target in probeTargets) {
            val payload = tryReadDirectControl(target, interfaceNumber)
            if (payload == null) {
                continue
            }
            val hex = payload.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            writeLog(
                "$TAG: Direct ${target.name} GET_CUR success entity=${target.entityId} " +
                    "selector=0x${target.selector.toString(16)} payload=[$hex]"
            )
            when (target.selector) {
                SELECTOR_PANTILT_ABSOLUTE -> if (!foundPanTilt) {
                    foundPanTilt = true
                    directPanTiltTarget = target
                    if (directPanTiltBaseline == null) {
                        directPanTiltBaseline = payload.copyOf()
                    }
                }
                SELECTOR_ROLL_ABSOLUTE -> if (!foundRoll) {
                    foundRoll = true
                    directRollTarget = target
                    if (directRollBaseline == null) {
                        directRollBaseline = payload.copyOf()
                    }
                }
            }
        }

        if (!foundPanTilt && directPanTiltTarget == null) {
            writeLog("$TAG: No direct absolutePanTilt target responded to GET_CUR")
        }
        if (!foundRoll && directRollTarget == null) {
            writeLog("$TAG: No direct absoluteRoll target responded to GET_CUR")
        }
    }

    private fun readDirectTiltValue(): Int? {
        val target = directPanTiltTarget ?: return null
        val interfaceNumber = synchronized(controlLock) { controlInterface?.id }
            ?: diagnosticInterface?.id ?: probedInterfaceNumber ?: return null
        val payload = readControlValue(target, interfaceNumber)
            ?: tryReadDirectControl(target, interfaceNumber)
            ?: readViaCameraConnection(target, interfaceNumber)
            ?: return null
        return decodeTiltValue(payload)
    }

    private fun readDirectPanTiltPayload(): ByteArray? {
        val target = directPanTiltTarget ?: return null
        val interfaceNumber = synchronized(controlLock) { controlInterface?.id }
            ?: diagnosticInterface?.id ?: probedInterfaceNumber ?: return null
        return readControlValue(target, interfaceNumber)
            ?: tryReadDirectControl(target, interfaceNumber)
            ?: readViaCameraConnection(target, interfaceNumber)
    }

    private fun readDirectRollValue(): Int? {
        val target = directRollTarget ?: return null
        val interfaceNumber = synchronized(controlLock) { controlInterface?.id }
            ?: diagnosticInterface?.id ?: probedInterfaceNumber ?: return null
        val payload = readControlValue(target, interfaceNumber)
            ?: tryReadDirectControl(target, interfaceNumber)
            ?: readViaCameraConnection(target, interfaceNumber)
            ?: return null
        return decodeRollValue(payload)
    }

    private fun tryReadDirectControl(target: DirectControlTarget, interfaceNumber: Int): ByteArray? {
        val connection = synchronized(diagnosticLock) { diagnosticConnection } ?: return null
        return try {
            val buffer = ByteArray(target.length)
            val count = connection.controlTransfer(
                UVC_REQUEST_TYPE_INTERFACE_IN,
                UVC_GET_CUR,
                target.selector shl 8,
                (target.entityId shl 8) or interfaceNumber,
                buffer,
                buffer.size,
                USB_TIMEOUT_MS
            )
            if (count <= 0) {
                null
            } else {
                buffer.copyOf(count)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readCameraControlValue(uvc: Any?, methodName: String): Int? {
        return try {
            val activeCamera = uvc ?: ensureActiveRawUvcCamera() ?: return null
            val nativePtr = getNativePtr(activeCamera)
            if (nativePtr == 0L) {
                writeLog("$TAG: $methodName skipped - native pointer is 0")
                return null
            }
            val method = findMethod(
                activeCamera.javaClass,
                methodName,
                Long::class.javaPrimitiveType!!
            ) ?: return null
            method.invoke(null, nativePtr) as? Int
        } catch (_: NoSuchMethodException) {
            null
        } catch (_: NoSuchFieldException) {
            null
        }
    }

    private fun handlePolledTiltTrigger() {
        val now = System.currentTimeMillis()
        if (now - lastCaptureTime <= DEBOUNCE_MS) {
            return
        }
        writeLog("$TAG: Polled TILT trigger detected -> capture")
        startDiagnosticPollWindow("tilt-trigger")
        dispatchTiltCapture("polled")
    }

    private fun dispatchTiltCapture(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastCaptureTime <= DEBOUNCE_MS) {
            return
        }
        lastCaptureTime = now
        pendingTiltRearm = true
        tiltTriggerVerificationInFlight = false
        scheduleForcedTiltRearm(reason)
        writeLog("$TAG: Dispatching tilt capture ($reason)")
        onCapturePressed()
    }

    private fun scheduleForcedTiltRearm(reason: String) {
        try {
            forcedTiltRearmThread?.interrupt()
        } catch (_: Throwable) {
        }
        forcedTiltRearmThread = Thread {
            try {
                Thread.sleep(FORCED_TILT_REARM_DELAY_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (!running.get() || !pendingTiltRearm) {
                return@Thread
            }
            writeLog("$TAG: Forced tilt re-arm timer fired ($reason)")
            resetTiltAfterTrigger()
        }.apply {
            name = "UsbButtonTiltForcedReset"
            isDaemon = true
            start()
        }
    }

    fun requestTiltRearm(reason: String) {
        if (!running.get()) {
            return
        }
        if (!pendingTiltRearm) {
            writeLog("$TAG: requestTiltRearm ignored - no pending tilt trigger ($reason)")
            return
        }
        val existing = tiltRearmThread
        if (existing?.isAlive == true) {
            writeLog("$TAG: requestTiltRearm skipped - reset already pending ($reason)")
            return
        }
        tiltRearmThread = Thread {
            try {
                Thread.sleep(RESET_DELAY_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (!running.get() || !pendingTiltRearm) {
                return@Thread
            }
            writeLog("$TAG: requestTiltRearm running ($reason)")
            resetTiltAfterTrigger()
        }.apply {
            name = "UsbButtonTiltReset"
            isDaemon = true
            start()
        }
    }

    private fun shouldTriggerPolledTilt(previousTilt: Int, tilt: Int): Boolean {
        val defaultTilt = tiltDefaultValue ?: previousTilt
        if (tilt == TILT_TRIGGER_VALUE) {
            return true
        }
        return previousTilt == defaultTilt && tilt != defaultTilt
    }

    private fun shouldTriggerPolledPanTilt(
        previousPayload: ByteArray,
        payload: ByteArray,
        previousTilt: Int,
        tilt: Int
    ): Boolean {
        val baseline = directPanTiltBaseline
        if (baseline != null) {
            val wasBaseline = previousPayload.contentEquals(baseline)
            val isBaseline = payload.contentEquals(baseline)
            if (wasBaseline && !isBaseline) {
                return true
            }
        }
        return shouldTriggerPolledTilt(previousTilt, tilt)
    }

    private fun shouldSuppressPanTiltChange(tilt: Int, payload: ByteArray): Boolean {
        val now = System.currentTimeMillis()
        if (now > suppressTiltUntilMs) {
            suppressedPanTiltPayload = null
            suppressedTiltValue = null
            suppressTiltUntilMs = 0L
            return false
        }
        val matchesPayload = suppressedPanTiltPayload?.contentEquals(payload) == true
        val matchesTilt = suppressedTiltValue == tilt
        if (!matchesPayload && !matchesTilt) {
            return false
        }
        suppressedPanTiltPayload = null
        suppressedTiltValue = null
        suppressTiltUntilMs = 0L
        return true
    }

    private fun shouldSuppressTiltChange(tilt: Int): Boolean {
        return shouldSuppressPanTiltChange(tilt, ByteArray(0))
    }

    private fun handlePolledRollTrigger(roll: Int) {
        lastRollValue = roll
        val now = System.currentTimeMillis()
        if (now - lastRecordTime <= DEBOUNCE_MS) {
            return
        }
        lastRecordTime = now
        writeLog("$TAG: Polled ROLL trigger detected -> video toggle")
        onRecordPressed()
    }

    @Volatile private var lastRecoveryLogMs = 0L
    private val RECOVERY_LOG_THROTTLE_MS = 5_000L

    private fun recoverControlAccess(reason: String) {
        nullPollStreak = 0

        // Throttle recovery log messages to avoid flooding
        val now = System.currentTimeMillis()
        val shouldLog = now - lastRecoveryLogMs >= RECOVERY_LOG_THROTTLE_MS
        if (shouldLog) lastRecoveryLogMs = now

        if (ENABLE_RAW_DIAGNOSTICS) {
            // In diagnostic mode, reopen the diagnostic connection (our own claimed interface)
            if (reopenDiagnosticConnection(reason)) {
                startDiagnosticPollWindow("recovered-$reason", 5_000L)
            }
            if (shouldLog) {
                val hasConn = synchronized(diagnosticLock) { diagnosticConnection != null }
                writeLog("$TAG: Recovery ($reason) diagnosticConnection=${if (hasConn) "reopened" else "FAILED"}")
            }
        } else {
            // In normal mode, refresh native JNI camera reference
            val uvc = ensureActiveRawUvcCamera()
            if (shouldLog) {
                writeLog("$TAG: Recovery ($reason) rawUvcCamera=${if (uvc != null) "available" else "UNAVAILABLE"}")
            }
            ensureActiveRawUvcCamera()?.let { current ->
                setUvcCallbacks(statusCallbackProxy, buttonCallbackProxy)
                if (shouldLog) {
                    writeLog("$TAG: Re-registered UVC callbacks during recovery ($reason) on ${current.javaClass.simpleName}")
                }
            }
        }
    }

    private fun reopenDiagnosticConnection(reason: String): Boolean {
        val device = usbDevice ?: return false
        val manager = usbManager ?: return false
        val candidate = findDiagnosticInterface(device)
        if (candidate == null) {
            writeLog("$TAG: No interrupt interface available for raw diagnostics")
            return false
        }

        synchronized(diagnosticLock) {
            closeDiagnosticResourcesLocked()

            val connection = manager.openDevice(device)
            if (connection == null) {
                writeLog("$TAG: Failed to open device for raw diagnostic reader ($reason)")
                return false
            }
            if (!connection.claimInterface(candidate.first, true)) {
                writeLog("$TAG: Failed to claim diagnostic interface ${candidate.first.id} ($reason)")
                connection.close()
                return false
            }

            diagnosticConnection = connection
            diagnosticInterface = candidate.first
            diagnosticEndpoint = candidate.second
            writeLog(
                "$TAG: Raw diagnostic reader attached to interface=${candidate.first.id} " +
                    "endpoint=${candidate.second.address} reason=$reason"
            )
            probeDirectControlTargets()
            startDiagnosticReaderLocked(candidate.second)
        }
        return true
    }

    private fun startDiagnosticReaderLocked(endpoint: UsbEndpoint) {
        diagnosticThread = Thread {
            val buffer = ByteArray((endpoint.maxPacketSize.coerceAtLeast(8)).coerceAtMost(64))
            while (running.get()) {
                val connection = synchronized(diagnosticLock) { diagnosticConnection }
                val activeEndpoint = synchronized(diagnosticLock) { diagnosticEndpoint }
                if (connection == null || activeEndpoint == null) {
                    return@Thread
                }
                try {
                    val count = connection.bulkTransfer(
                        activeEndpoint,
                        buffer,
                        buffer.size,
                        250
                    )
                    if (count > 0) {
                        val hex = buffer.take(count).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                        writeLog("$TAG: Raw diagnostic data [$count]: $hex")
                    }
                } catch (t: Throwable) {
                    if (running.get()) {
                        writeLog("$TAG: Raw diagnostic reader error: ${t.message}")
                    }
                    return@Thread
                }
            }
        }.apply {
            name = "UsbButtonDiag"
            isDaemon = true
            start()
        }
    }

    private fun closeDiagnosticResourcesLocked() {
        try {
            diagnosticThread?.interrupt()
        } catch (_: Throwable) {
        }
        try {
            diagnosticInterface?.let { diagnosticConnection?.releaseInterface(it) }
            diagnosticConnection?.close()
        } catch (t: Throwable) {
            writeLog("$TAG: Failed to close diagnostic reader: ${t.message}")
        }
        diagnosticThread = null
        diagnosticEndpoint = null
        diagnosticInterface = null
        diagnosticConnection = null
    }

    private fun findDiagnosticInterface(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_VIDEO &&
                intf.interfaceSubclass == 1
            ) {
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                        ep.direction == UsbConstants.USB_DIR_IN
                    ) {
                        writeLog(
                            "$TAG: Using UVC control interrupt endpoint on interface=${intf.id} " +
                                "alt=${intf.alternateSetting} endpoint=${ep.address}"
                        )
                        return intf to ep
                    }
                }
            }
        }

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_VIDEO) {
                continue
            }
            for (j in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                    ep.direction == UsbConstants.USB_DIR_IN
                ) {
                    writeLog(
                        "$TAG: Using non-video interrupt endpoint on interface=${intf.id} " +
                            "alt=${intf.alternateSetting} endpoint=${ep.address}"
                    )
                    return intf to ep
                }
            }
        }
        return null
    }

    /**
     * Arms the tilt control for button detection by setting tilt to 0.
     * Windows AmCap requires: set tilt to 0, then the button will change it to 1.
     * The factory default of 3600 may be an "unarmed" state where button presses
     * don't register as value changes.
     */
    private fun armTiltForButtonDetection() {
        val target = directPanTiltTarget
        if (target == null) {
            writeLog("$TAG: Cannot arm tilt - no pan/tilt target found")
            return
        }
        val interfaceNumber = synchronized(controlLock) { controlInterface?.id }
            ?: synchronized(diagnosticLock) { diagnosticInterface?.id }
            ?: probedInterfaceNumber
        if (interfaceNumber == null) {
            writeLog("$TAG: Cannot arm tilt - no interface number")
            return
        }

        // Read current value
        val currentPayload = readAnySingleControl(target, interfaceNumber)
        val currentTilt = currentPayload?.let(::decodeTiltValue)
        writeLog("$TAG: Tilt arm: current tilt=$currentTilt payload=[${currentPayload?.let(::payloadToHex) ?: "null"}]")

        // Build payload with pan=0, tilt=0
        val armPayload = ByteArray(8) // all zeros: pan=0, tilt=0
        val armCount = writeControlValue(target, interfaceNumber, armPayload)
            .let { if (it > 0) it else tryWriteDirectControl(target, interfaceNumber, armPayload) }
            .let { if (it > 0) it else writeViaCameraConnection(target, interfaceNumber, armPayload) }

        writeLog("$TAG: Tilt arm: SET_CUR to [${payloadToHex(armPayload)}] result=$armCount")

        // Verify
        try { Thread.sleep(50) } catch (_: InterruptedException) { return }
        val verifyPayload = readAnySingleControl(target, interfaceNumber)
        val verifyTilt = verifyPayload?.let(::decodeTiltValue)
        writeLog("$TAG: Tilt arm: verify after SET_CUR tilt=$verifyTilt payload=[${verifyPayload?.let(::payloadToHex) ?: "null"}]")

        if (verifyTilt == 0) {
            writeLog("$TAG: Tilt ARMED to 0 - ready for button detection (expecting 0->1 on press)")
            tiltDefaultValue = 0
            lastPolledTiltValue = 0
            directPanTiltBaseline = armPayload.copyOf()
        } else {
            writeLog("$TAG: Tilt arm FAILED - value didn't change to 0 (still $verifyTilt)")
        }
    }

    private fun armRollForButtonDetection() {
        val target = directRollTarget
        if (target == null) {
            writeLog("$TAG: Cannot arm roll - no roll target found")
            return
        }
        val interfaceNumber = synchronized(controlLock) { controlInterface?.id }
            ?: synchronized(diagnosticLock) { diagnosticInterface?.id }
            ?: probedInterfaceNumber
        if (interfaceNumber == null) {
            writeLog("$TAG: Cannot arm roll - no interface number")
            return
        }

        // Read current value
        val currentPayload = readAnySingleControl(target, interfaceNumber)
        val currentRoll = currentPayload?.let(::decodeRollValue)
        writeLog("$TAG: Roll arm: current roll=$currentRoll payload=[${currentPayload?.let(::payloadToHex) ?: "null"}]")

        // Build payload with roll=0 (2 bytes, little-endian)
        val armPayload = ByteArray(2) // [0x00, 0x00] = roll 0
        val armCount = writeControlValue(target, interfaceNumber, armPayload)
            .let { if (it > 0) it else tryWriteDirectControl(target, interfaceNumber, armPayload) }
            .let { if (it > 0) it else writeViaCameraConnection(target, interfaceNumber, armPayload) }

        writeLog("$TAG: Roll arm: SET_CUR to [${payloadToHex(armPayload)}] result=$armCount")

        // Verify
        try { Thread.sleep(50) } catch (_: InterruptedException) { return }
        val verifyPayload = readAnySingleControl(target, interfaceNumber)
        val verifyRoll = verifyPayload?.let(::decodeRollValue)
        writeLog("$TAG: Roll arm: verify after SET_CUR roll=$verifyRoll payload=[${verifyPayload?.let(::payloadToHex) ?: "null"}]")

        if (verifyRoll == 0) {
            writeLog("$TAG: Roll ARMED to 0 - ready for button detection (expecting 0->non-zero on press)")
            lastRollValue = 0
            lastPolledRollValue = 0
            directRollBaseline = armPayload.copyOf()
        } else {
            writeLog("$TAG: Roll arm FAILED - value didn't change to 0 (still $verifyRoll)")
        }
    }

    private fun resetTiltAfterTrigger() {
        if (resetTiltWithRetries()) {
            pendingTiltRearm = false
            return
        }
        writeLog("$TAG: Tilt re-arm failed after retries")
    }

    private fun resetTiltWithRetries(): Boolean {
        val targetTilt = tiltDefaultValue
            ?: decodeTiltValue(directPanTiltBaseline ?: ByteArray(0))
            ?: lastPolledTiltValue
            ?: readDirectTiltValue()
        if (targetTilt == null) {
            writeLog("$TAG: Tilt re-arm skipped - no target tilt available")
            return false
        }

        for (attempt in 1..RESET_RETRY_COUNT) {
            val resetIssued = resetTiltToDefault(targetTilt)
            if (resetIssued) {
                try {
                    Thread.sleep(DIRECT_RESET_VERIFY_DELAY_MS)
                } catch (_: InterruptedException) {
                    return false
                }
            }
            var readBack = readDirectTiltValue() ?: rawUvcCamera?.let { readCameraControlValue(it, "nativeGetTilt") }
            if (resetIssued && readBack == null) {
                writeLog("$TAG: Tilt re-arm verification lost control access, recovering")
                recoverControlAccess("tilt-reset-verify")
                readBack = readDirectTiltValue() ?: rawUvcCamera?.let { readCameraControlValue(it, "nativeGetTilt") }
            }
            writeLog(
                "$TAG: Tilt re-arm attempt=$attempt target=$targetTilt " +
                    "resetIssued=$resetIssued readBack=${readBack?.toString() ?: "<null>"}"
            )
            if (resetIssued && readBack == targetTilt) {
                lastPolledTiltValue = readBack
                tiltDefaultValue = readBack
                lastPolledPanTiltPayload = directPanTiltBaseline?.copyOf() ?: lastPolledPanTiltPayload
                suppressedPanTiltPayload = directPanTiltBaseline?.copyOf()
                suppressedTiltValue = readBack
                suppressTiltUntilMs = System.currentTimeMillis() + TILT_RESET_SUPPRESSION_MS
                startDiagnosticPollWindow("tilt-reset")
                return true
            }
            if (attempt < RESET_RETRY_COUNT) {
                try {
                    Thread.sleep(RESET_RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    return false
                }
            }
        }

        return false
    }

    private fun resetTiltToDefault(targetTilt: Int): Boolean {
        if (resetPanTiltToBaseline(targetTilt)) {
            val baselineTilt = decodeTiltValue(directPanTiltBaseline ?: ByteArray(0))
            writeLog(
                "$TAG: Direct pan/tilt baseline reset issued " +
                    "baselineTilt=${baselineTilt?.toString() ?: "<null>"}"
            )
            return true
        }
        val uvc = ensureActiveRawUvcCamera() ?: run {
            writeLog("$TAG: Unable to reset tilt: raw UVC camera unavailable")
            return false
        }
        try {
            val nativePtr = getNativePtr(uvc)
            if (nativePtr == 0L) {
                writeLog("$TAG: Unable to reset tilt: native pointer is 0")
                return false
            }

            val updateTiltLimit = findMethod(
                uvc.javaClass,
                "nativeUpdateTiltLimit",
                Long::class.javaPrimitiveType!!
            ) ?: run {
                writeLog("$TAG: Unable to reset tilt: nativeUpdateTiltLimit not found")
                return false
            }
            val nativeSetTilt = findMethod(
                uvc.javaClass,
                "nativeSetTilt",
                Long::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!
            ) ?: run {
                writeLog("$TAG: Unable to reset tilt: nativeSetTilt not found")
                return false
            }

            val limitResult = (updateTiltLimit.invoke(uvc, nativePtr) as? Int) ?: 0
            val setResult = (nativeSetTilt.invoke(null, nativePtr, targetTilt) as? Int) ?: 0
            val ok = setResult == 0
            writeLog(
                "$TAG: Native tilt reset ${if (ok) "issued" else "failed"} " +
                    "target=$targetTilt setResult=$setResult limitResult=$limitResult"
            )
            return ok
        } catch (t: Throwable) {
            writeLog("$TAG: Unable to reset tilt: ${t.message}")
            return false
        }
    }

    private fun resetPanTiltToBaseline(targetTilt: Int): Boolean {
        val target = directPanTiltTarget ?: return false
        val baseline = directPanTiltBaseline ?: return false
        val interfaceNumber = synchronized(controlLock) { controlInterface?.id }
            ?: synchronized(diagnosticLock) { diagnosticInterface?.id }
            ?: probedInterfaceNumber
            ?: return false
        val alternateTilt = when {
            targetTilt != 0 -> 0
            TILT_TRIGGER_VALUE != targetTilt -> TILT_TRIGGER_VALUE
            else -> 1
        }
        val alternatePayload = buildPanTiltPayloadWithTilt(baseline, alternateTilt)
        if (alternatePayload != null) {
            val alternateCount = writeControlValue(target, interfaceNumber, alternatePayload)
                .let { if (it > 0) it else tryWriteDirectControl(target, interfaceNumber, alternatePayload) }
                .let { if (it > 0) it else writeViaCameraConnection(target, interfaceNumber, alternatePayload) }
            writeLog(
                "$TAG: Direct pan/tilt pre-reset " +
                    "alternateTilt=$alternateTilt bytes=$alternateCount"
            )
            if (alternateCount > 0) {
                try {
                    Thread.sleep(DIRECT_RESET_STEP_DELAY_MS)
                } catch (_: InterruptedException) {
                    return false
                }
            }
        }
        val count = writeControlValue(target, interfaceNumber, baseline)
            .let { if (it > 0) it else tryWriteDirectControl(target, interfaceNumber, baseline) }
            .let { if (it > 0) it else writeViaCameraConnection(target, interfaceNumber, baseline) }
        if (count <= 0) {
            writeLog(
                "$TAG: Direct pan/tilt baseline reset failed entity=${target.entityId} " +
                    "selector=0x${target.selector.toString(16)} bytes=$count"
            )
            recoverControlAccess("direct-reset-failed")
            return false
        }
        writeLog("$TAG: Pan/tilt baseline reset succeeded via ${if (synchronized(controlLock) { controlConnection } != null) "control" else "camera-library"} connection")
        return true
    }

    private fun buildPanTiltPayloadWithTilt(source: ByteArray, tiltValue: Int): ByteArray? {
        if (source.size < 8) {
            return null
        }
        return source.copyOf().also { payload ->
            ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).putInt(4, tiltValue)
        }
    }

    private fun tryWriteDirectControl(
        target: DirectControlTarget,
        interfaceNumber: Int,
        payload: ByteArray
    ): Int {
        val connection = synchronized(diagnosticLock) { diagnosticConnection } ?: return -1
        return try {
            connection.controlTransfer(
                UVC_REQUEST_TYPE_INTERFACE_OUT,
                UVC_SET_CUR,
                target.selector shl 8,
                (target.entityId shl 8) or interfaceNumber,
                payload.copyOf(target.length),
                target.length,
                USB_TIMEOUT_MS
            )
        } catch (_: Throwable) {
            -1
        }
    }

    private fun copyBuffer(buffer: ByteBuffer): ByteArray {
        val duplicate = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        return bytes
    }

    private fun payloadToHex(payload: ByteArray): String {
        return payload.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    }

    private fun getNativePtr(target: Any): Long {
        return try {
            val field = findField(target.javaClass, "mNativePtr") ?: return 0L
            field.getLong(target)
        } catch (_: Throwable) {
            0L
        }
    }

    private fun findField(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name).apply {
                    isAccessible = true
                }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    private fun findMethod(type: Class<*>, name: String, vararg parameterTypes: Class<*>): java.lang.reflect.Method? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, *parameterTypes).apply {
                    isAccessible = true
                }
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        return null
    }

    private fun decodeTiltValue(payload: ByteArray): Int? {
        if (payload.isEmpty()) {
            return null
        }
        val littleEndian = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return when {
            payload.size >= 8 -> littleEndian.getInt(4)
            payload.size >= 4 -> littleEndian.getInt(0)
            else -> payload[0].toInt() and 0xFF
        }
    }

    private fun decodeRollValue(payload: ByteArray): Int? {
        if (payload.isEmpty()) {
            return null
        }
        val littleEndian = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return when {
            payload.size >= 2 -> littleEndian.short.toInt()
            else -> payload[0].toInt()
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) {
            return
        }
        setUvcCallbacks(null, null)
        try {
            diagnosticThread?.interrupt()
        } catch (_: Throwable) {
        }
        try {
            pollingThread?.interrupt()
        } catch (_: Throwable) {
        }
        synchronized(diagnosticLock) {
            closeDiagnosticResourcesLocked()
        }
        closeControlConnection()
        pollingThread = null
        usbManager = null
        statusCallbackProxy = null
        buttonCallbackProxy = null
        rawUvcCamera = null
        tiltDefaultValue = null
        lastPolledPanTiltPayload = null
        lastPolledTiltValue = null
        lastPolledRollValue = null
        suppressedPanTiltPayload = null
        suppressedTiltValue = null
        suppressTiltUntilMs = 0L
        nullPollStreak = 0
        pendingTiltRearm = false
        try {
            tiltRearmThread?.interrupt()
        } catch (_: Throwable) {
        }
        tiltRearmThread = null
        try {
            forcedTiltRearmThread?.interrupt()
        } catch (_: Throwable) {
        }
        forcedTiltRearmThread = null
        tiltTriggerVerificationInFlight = false
        writeLog("$TAG: Stopped")
    }

    private inner class StatusCallbackHandler : InvocationHandler {
        override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? {
            if (method.name != "onStatus" || args == null || args.size < 5) {
                return null
            }
            val statusClass = args[0] as? Int ?: return null
            val event = args[1] as? Int ?: return null
            val selector = args[2] as? Int ?: return null
            val statusAttribute = args[3] as? Int ?: return null
            val data = args[4] as? ByteBuffer
            handleStatusEvent(statusClass, event, selector, statusAttribute, data)
            return null
        }
    }

    private inner class ButtonCallbackHandler : InvocationHandler {
        override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? {
            if (method.name != "onButton" || args == null || args.size < 2) {
                return null
            }
            if (!running.get()) {
                return null
            }
            val button = args[0] as? Int ?: return null
            val state = args[1] as? Int ?: return null
            writeLog("$TAG: UVC button callback button=$button state=$state")
            return null
        }
    }
}
