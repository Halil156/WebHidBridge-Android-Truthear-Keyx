package com.halil.webhidbridge

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Emulates the slice of the WebHID API that DevicePEQ-style sites use
 * (requestDevice / open / sendFeatureReport / receiveFeatureReport / sendReport
 * / inputreport events), backed by Android's real USB Host API.
 *
 * The matching JS side lives in assets/webhid_shim.js.
 */
class WebHidBridge(private val activity: Activity, private val webView: WebView) {

    private val usbManager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class OpenDevice(
        val device: UsbDevice,
        val connection: UsbDeviceConnection,
        val iface: UsbInterface,
        val endpointIn: UsbEndpoint?,
        val endpointOut: UsbEndpoint?,
        var reading: Boolean = true,
        var collections: JSONArray = JSONArray()
    )

    private val openDevices = HashMap<String, OpenDevice>() // key = UsbDevice.deviceName

    // ---- USB permission handling ----------------------------------------

    private val ACTION_USB_PERMISSION = "${activity.packageName}.USB_PERMISSION"
    private var permissionReceiverRegistered = false
    private val pendingPermission = HashMap<String, CountDownLatch>()
    private val permissionGranted = HashMap<String, Boolean>()

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            val name = device?.deviceName ?: return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            permissionGranted[name] = granted
            pendingPermission[name]?.countDown()
        }
    }

    private fun ensureReceiverRegistered() {
        if (permissionReceiverRegistered) return
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            activity.registerReceiver(permissionReceiver, filter)
        }
        permissionReceiverRegistered = true
    }

    fun teardown() {
        if (permissionReceiverRegistered) {
            activity.unregisterReceiver(permissionReceiver)
            permissionReceiverRegistered = false
        }
        openDevices.values.forEach { it.reading = false; it.connection.close() }
        openDevices.clear()
    }

    private fun requestPermissionBlocking(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        ensureReceiverRegistered()
        val latch = CountDownLatch(1)
        pendingPermission[device.deviceName] = latch
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(activity.packageName)
        }
        val pi = PendingIntent.getBroadcast(activity, 0, intent, flags)
        usbManager.requestPermission(device, pi)
        latch.await(30, TimeUnit.SECONDS) // user has 30s to tap Allow in the system dialog
        return permissionGranted[device.deviceName] ?: false
    }

    // ---- JS entry point ----------------------------------------------------

    @JavascriptInterface
    fun call(method: String, argsJson: String, callId: Int) {
        thread {
            try {
                val args = JSONObject(argsJson)
                val result: Any? = when (method) {
                    "requestDevice" -> requestDevice(args)
                    "getDevices" -> listKnownDevices()
                    "open" -> openDevice(args)
                    "close" -> closeDevice(args)
                    "sendReport" -> sendReport(args, reportType = OUTPUT)
                    "sendFeatureReport" -> sendReport(args, reportType = FEATURE)
                    "receiveFeatureReport" -> receiveFeatureReport(args)
                    else -> throw IllegalArgumentException("Unknown method: $method")
                }
                resolveJs(callId, result)
            } catch (e: Exception) {
                rejectJs(callId, e.message ?: e.toString())
            }
        }
    }

    private fun resolveJs(callId: Int, result: Any?) {
        val json = when (result) {
            null -> "null"
            is JSONObject, is JSONArray -> result.toString()
            else -> result.toString()
        }
        val escaped = JSONObject.quote(json)
        mainHandler.post {
            webView.evaluateJavascript("window.__hidResolve($callId, $escaped)", null)
        }
    }

    private fun rejectJs(callId: Int, message: String) {
        val escaped = JSONObject.quote(message)
        mainHandler.post {
            webView.evaluateJavascript("window.__hidReject($callId, $escaped)", null)
        }
    }

    // ---- Device discovery / permission / open -------------------------------

    private fun matchesFilters(device: UsbDevice, filters: JSONArray): Boolean {
        if (filters.length() == 0) return true
        for (i in 0 until filters.length()) {
            val f = filters.optJSONObject(i) ?: continue
            val vendorOk = if (f.has("vendorId")) f.getInt("vendorId") == device.vendorId else true
            val productOk = if (f.has("productId")) f.getInt("productId") == device.productId else true
            if (vendorOk && productOk) return true
        }
        return false
    }

    private fun deviceInfoJson(device: UsbDevice): JSONObject = JSONObject().apply {
        put("id", device.deviceName)
        put("vendorId", device.vendorId)
        put("productId", device.productId)
        put("productName", device.productName ?: "")
        put("collections", openDevices[device.deviceName]?.collections ?: JSONArray())
    }

    private fun requestDevice(args: JSONObject): JSONObject {
        val filters = args.optJSONArray("filters") ?: JSONArray()

        val allDevices = usbManager.deviceList.values
        android.util.Log.d("HidBridge", "Filters from site: $filters")
        if (allDevices.isEmpty()) {
            android.util.Log.d("HidBridge", "usbManager.deviceList is EMPTY — Android görmüyor cihazı (OTG/kablo sorunu olabilir)")
        } else {
            allDevices.forEach {
                android.util.Log.d("HidBridge", "Görülen cihaz: name=${it.deviceName} vendorId=${it.vendorId} productId=${it.productId} product=${it.productName}")
            }
        }

        val candidates = allDevices.filter { matchesFilters(it, filters) }
        val device = candidates.firstOrNull()
            ?: throw IllegalStateException("Eşleşen USB cihaz bulunamadı (bağlı mı? OTG izni verildi mi?)")

        if (!requestPermissionBlocking(device)) {
            throw IllegalStateException("USB izni verilmedi")
        }
        openInternal(device)
        return deviceInfoJson(device)
    }

    private fun listKnownDevices(): JSONArray {
        val arr = JSONArray()
        openDevices.values.forEach { arr.put(deviceInfoJson(it.device)) }
        return arr
    }

    private fun findDevice(id: String): UsbDevice =
        usbManager.deviceList[id] ?: openDevices[id]?.device
        ?: throw IllegalStateException("Cihaz artık bağlı değil: $id")

    private fun openInternal(device: UsbDevice) {
        if (openDevices.containsKey(device.deviceName)) return

        // Prefer an HID-class interface; fall back to interface 0.
        var chosen: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_HID) {
                chosen = iface
                break
            }
        }
        val iface = chosen ?: device.getInterface(0)

        val connection = usbManager.openDevice(device)
            ?: throw IllegalStateException("Cihaz açılamadı (openDevice null döndü)")
        if (!connection.claimInterface(iface, true)) {
            throw IllegalStateException("USB arayüzü claim edilemedi")
        }

        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
                else epOut = ep
            }
        }

        val opened = OpenDevice(device, connection, iface, epIn, epOut)

        try {
            val raw = HidDescriptor.fetchRaw(connection, iface)
            if (raw != null) {
                opened.collections = HidDescriptor.parse(raw)
                android.util.Log.d("HidBridge", "Report descriptor parsed: ${opened.collections.length()} top-level collection(s), raw=${raw.size} bytes")
            } else {
                android.util.Log.d("HidBridge", "Report descriptor GET_DESCRIPTOR returned nothing")
            }
        } catch (e: Exception) {
            android.util.Log.d("HidBridge", "Report descriptor fetch/parse failed: ${e.message}")
        }

        openDevices[device.deviceName] = opened

        if (epIn != null) startInputReportLoop(device.deviceName, opened, epIn)
    }

    private fun openDevice(args: JSONObject): JSONObject {
        val id = args.getString("deviceId")
        val device = findDevice(id)
        openInternal(device)
        return deviceInfoJson(device)
    }

    private fun closeDevice(args: JSONObject): JSONObject {
        val id = args.getString("deviceId")
        openDevices.remove(id)?.let {
            it.reading = false
            it.connection.close()
        }
        return JSONObject()
    }

    private fun startInputReportLoop(deviceId: String, opened: OpenDevice, endpoint: UsbEndpoint) {
        thread {
            val buffer = ByteArray(endpoint.maxPacketSize.coerceAtLeast(64))
            while (opened.reading) {
                val n = opened.connection.bulkTransfer(endpoint, buffer, buffer.size, 1000)
                if (n > 0) {
                    val reportId = buffer[0].toInt() and 0xFF
                    val data = buffer.copyOfRange(1, n)
                    val b64 = Base64.encodeToString(data, Base64.NO_WRAP)
                    mainHandler.post {
                        webView.evaluateJavascript(
                            "window.__hidDispatchInputReport(${JSONObject.quote(deviceId)}, $reportId, ${JSONObject.quote(b64)})",
                            null
                        )
                    }
                }
                // n <= 0 just means "no data within the timeout" — keep polling.
            }
        }
    }

    // ---- HID GET_REPORT / SET_REPORT control transfers -----------------------
    // These mirror exactly what the browser's sendFeatureReport/receiveFeatureReport
    // do under the hood, per the USB HID class spec (bRequest 0x09 = SET_REPORT,
    // 0x01 = GET_REPORT).

    companion object {
        private const val FEATURE = 0x03
        private const val OUTPUT = 0x02
        private const val HID_GET_REPORT = 0x01
        private const val HID_SET_REPORT = 0x09
        private const val TIMEOUT_MS = 5000
    }

    private fun sendReport(args: JSONObject, reportType: Int): JSONObject {
        val id = args.getString("deviceId")
        val reportId = args.getInt("reportId")
        val dataB64 = args.getString("data")
        val payload = Base64.decode(dataB64, Base64.DEFAULT)
        val opened = openDevices[id] ?: throw IllegalStateException("Cihaz açık değil: $id")

        // Try the interrupt OUT endpoint first for plain output reports (feels more
        // like real HID); fall back to the SET_REPORT control transfer, which works
        // for both Output and Feature reports and is what most PEQ dongles expect.
        if (reportType == OUTPUT && opened.endpointOut != null) {
            val buffer = byteArrayOf(reportId.toByte()) + payload
            val n = opened.connection.bulkTransfer(opened.endpointOut, buffer, buffer.size, TIMEOUT_MS)
            if (n >= 0) return JSONObject()
            // fall through to control transfer if the interrupt write failed
        }

        val requestType = 0x21 // Host-to-device | Class | Interface
        val value = (reportType shl 8) or (reportId and 0xFF)
        val n = opened.connection.controlTransfer(
            requestType, HID_SET_REPORT, value, opened.iface.id, payload, payload.size, TIMEOUT_MS
        )
        if (n < 0) throw IllegalStateException("SET_REPORT başarısız (reportId=$reportId)")
        return JSONObject()
    }

    private fun receiveFeatureReport(args: JSONObject): JSONObject {
        val id = args.getString("deviceId")
        val reportId = args.getInt("reportId")
        val opened = openDevices[id] ?: throw IllegalStateException("Cihaz açık değil: $id")

        val requestType = 0xA1 // Device-to-host | Class | Interface
        val value = (FEATURE shl 8) or (reportId and 0xFF)
        val buffer = ByteArray(64)
        val n = opened.connection.controlTransfer(
            requestType, HID_GET_REPORT, value, opened.iface.id, buffer, buffer.size, TIMEOUT_MS
        )
        if (n < 0) throw IllegalStateException("GET_REPORT başarısız (reportId=$reportId)")
        val trimmed = buffer.copyOfRange(0, n)
        return JSONObject().put("data", Base64.encodeToString(trimmed, Base64.NO_WRAP))
    }
}
