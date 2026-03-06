package com.hawkeyeborescopes.viewer

import android.content.Context
import android.hardware.usb.*
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Helper class to detect button presses from USB borescope devices.
 * Hawkeye borescopes send ROLL and TILT commands via their physical buttons:
 * - TILT button -> Capture still image
 * - ROLL button -> Toggle video recording
 */
class UsbButtonHelper(
    private val context: Context,
    private val onCapturePressed: () -> Unit,
    private val onRecordPressed: () -> Unit,
    private val writeLog: (String) -> Unit
) {
    companion object {
        private const val TAG = "UsbButtonHelper"
        private const val USB_CLASS_HID = 3

        // Debounce time to prevent double triggers (ms)
        private const val DEBOUNCE_MS = 500L
    }

    private var usbManager: UsbManager? = null
    private var connection: UsbDeviceConnection? = null
    private var hidInterface: UsbInterface? = null
    private var endpoint: UsbEndpoint? = null
    private var readThread: Thread? = null
    private val running = AtomicBoolean(false)

    private var lastCaptureTime = 0L
    private var lastRecordTime = 0L

    fun start(device: UsbDevice) {
        usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        writeLog("UsbButtonHelper: Scanning device for HID interface...")
        writeLog("UsbButtonHelper: Device has ${device.interfaceCount} interfaces")

        // Find HID interface on the device
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            writeLog("UsbButtonHelper: Interface $i - class=${intf.interfaceClass}, subclass=${intf.interfaceSubclass}")

            if (intf.interfaceClass == USB_CLASS_HID) {
                hidInterface = intf
                writeLog("UsbButtonHelper: Found HID interface at index $i")

                // Find interrupt IN endpoint
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    writeLog("UsbButtonHelper: Endpoint $j - type=${ep.type}, direction=${ep.direction}, address=${ep.address}")

                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                        ep.direction == UsbConstants.USB_DIR_IN) {
                        endpoint = ep
                        writeLog("UsbButtonHelper: Using interrupt IN endpoint at address ${ep.address}")
                        break
                    }
                }
                break
            }
        }

        if (hidInterface == null) {
            writeLog("UsbButtonHelper: No HID interface found - buttons won't work")
            return
        }

        if (endpoint == null) {
            writeLog("UsbButtonHelper: No interrupt IN endpoint found - buttons won't work")
            return
        }

        // Open connection
        connection = usbManager?.openDevice(device)
        if (connection == null) {
            writeLog("UsbButtonHelper: Failed to open USB connection for HID")
            return
        }

        // Claim interface
        if (!connection!!.claimInterface(hidInterface, true)) {
            writeLog("UsbButtonHelper: Failed to claim HID interface")
            connection?.close()
            connection = null
            return
        }

        writeLog("UsbButtonHelper: HID interface claimed successfully, starting button listener")
        running.set(true)
        startReadThread()
    }

    private fun startReadThread() {
        readThread = Thread {
            val buffer = ByteArray(64)
            writeLog("UsbButtonHelper: Read thread started")

            while (running.get() && connection != null && endpoint != null) {
                try {
                    val bytesRead = connection?.bulkTransfer(endpoint, buffer, buffer.size, 100)

                    if (bytesRead != null && bytesRead > 0) {
                        handleButtonData(buffer, bytesRead)
                    }
                } catch (e: Exception) {
                    if (running.get()) {
                        // Don't spam logs for timeout errors
                        if (!e.message.toString().contains("timeout", ignoreCase = true)) {
                            writeLog("UsbButtonHelper: Error reading HID: ${e.message}")
                        }
                    }
                }
            }
            writeLog("UsbButtonHelper: Read thread stopped")
        }
        readThread?.name = "UsbButtonReader"
        readThread?.start()
    }

    private fun handleButtonData(data: ByteArray, length: Int) {
        // Log the raw data for debugging
        val hex = data.take(length).joinToString(" ") { String.format("%02X", it) }
        writeLog("UsbButtonHelper: HID data [$length bytes]: $hex")

        val now = System.currentTimeMillis()

        // Look for button press patterns
        // Common patterns for borescope buttons:
        // - Non-zero values typically indicate a button press
        // - The specific byte position and value depends on the device

        // Check each byte for potential button signals
        for (i in 0 until length) {
            val value = data[i].toInt() and 0xFF

            if (value != 0) {
                // Analyze the pattern to determine which button
                // Based on Windows app: TILT -> capture, ROLL -> record

                when {
                    // Common patterns for "tilt" or first button (capture)
                    // Often the first non-zero byte or specific values
                    (i == 0 && (value == 0x01 || value == 0x10 || value == 0x20)) ||
                    (i == 1 && value == 0x01) ||
                    (length >= 2 && data[0].toInt() and 0xFF == 0x01 && value == 0x00) -> {
                        if (now - lastCaptureTime > DEBOUNCE_MS) {
                            lastCaptureTime = now
                            writeLog("UsbButtonHelper: TILT/Capture button detected")
                            onCapturePressed()
                        }
                        return
                    }

                    // Common patterns for "roll" or second button (record)
                    (i == 0 && (value == 0x02 || value == 0x40 || value == 0x80)) ||
                    (i == 1 && value == 0x02) ||
                    (length >= 2 && data[0].toInt() and 0xFF == 0x02) -> {
                        if (now - lastRecordTime > DEBOUNCE_MS) {
                            lastRecordTime = now
                            writeLog("UsbButtonHelper: ROLL/Record button detected")
                            onRecordPressed()
                        }
                        return
                    }
                }
            }
        }

        // If we get here with non-zero data but didn't match a pattern,
        // try a simpler heuristic: first button press = capture, alternating = record
        val hasNonZero = data.take(length).any { (it.toInt() and 0xFF) != 0 }
        if (hasNonZero) {
            // Use first byte value to decide
            val firstNonZero = data.take(length).firstOrNull { (it.toInt() and 0xFF) != 0 }
            val value = firstNonZero?.toInt()?.and(0xFF) ?: 0

            if (value != 0) {
                // Odd values -> capture, Even values -> record (simple heuristic)
                if (value % 2 == 1) {
                    if (now - lastCaptureTime > DEBOUNCE_MS) {
                        lastCaptureTime = now
                        writeLog("UsbButtonHelper: Button press (value=$value) -> Capture")
                        onCapturePressed()
                    }
                } else {
                    if (now - lastRecordTime > DEBOUNCE_MS) {
                        lastRecordTime = now
                        writeLog("UsbButtonHelper: Button press (value=$value) -> Record")
                        onRecordPressed()
                    }
                }
            }
        }
    }

    fun stop() {
        writeLog("UsbButtonHelper: Stopping...")
        running.set(false)

        try {
            readThread?.interrupt()
            readThread?.join(500)
        } catch (e: Exception) {
            writeLog("UsbButtonHelper: Error stopping read thread: ${e.message}")
        }
        readThread = null

        try {
            hidInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
        } catch (e: Exception) {
            writeLog("UsbButtonHelper: Error releasing HID interface: ${e.message}")
        }

        connection = null
        hidInterface = null
        endpoint = null

        writeLog("UsbButtonHelper: Stopped")
    }
}
