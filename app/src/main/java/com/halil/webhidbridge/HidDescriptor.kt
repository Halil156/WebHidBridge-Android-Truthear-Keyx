package com.halil.webhidbridge

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fetches the raw USB HID Report Descriptor from the device and parses it into
 * roughly the same shape as WebHID's `HIDDevice.collections` in a real browser
 * (usagePage / usage / children / inputReports / outputReports / featureReports).
 *
 * Sites built against real WebHID (like DevicePEQ) may use this info — not just
 * vendorId/productId — to decide whether a device is "supported" and which
 * report IDs to talk to. Our polyfill needs to supply the same shape or those
 * checks silently fail.
 */
object HidDescriptor {

    private const val GET_DESCRIPTOR = 0x06
    private const val DESCRIPTOR_TYPE_REPORT = 0x22
    private const val TIMEOUT_MS = 5000

    fun fetchRaw(connection: UsbDeviceConnection, iface: UsbInterface): ByteArray? {
        // Standard | Device-to-host | Interface
        val requestType = 0x81
        val value = DESCRIPTOR_TYPE_REPORT shl 8 // descriptor index 0
        val buffer = ByteArray(4096)
        val n = connection.controlTransfer(
            requestType, GET_DESCRIPTOR, value, iface.id, buffer, buffer.size, TIMEOUT_MS
        )
        if (n <= 0) return null
        return buffer.copyOfRange(0, n)
    }

    /** Parses a raw HID report descriptor into a JSONArray of top-level collections. */
    fun parse(bytes: ByteArray): JSONArray {
        val root = JSONArray()
        val stack = ArrayDeque<JSONObject>()

        // Global state (persists across items, save/restored by Push/Pop)
        var usagePage = 0
        var logicalMin = 0
        var logicalMax = 0
        var reportSize = 0
        var reportCount = 0
        var reportId = 0
        val globalStack = ArrayDeque<IntArray>() // [usagePage, logicalMin, logicalMax, reportSize, reportCount, reportId]

        // Local state (cleared after every Main item)
        var usages = mutableListOf<Int>()
        var usageMin = -1
        var usageMax = -1

        fun currentCollectionChildren(): JSONArray =
            if (stack.isEmpty()) root else stack.last().getJSONArray("children")

        fun buildItemJson(): JSONObject = JSONObject().apply {
            put("usagePage", usagePage)
            if (usages.isNotEmpty()) {
                put("usages", JSONArray(usages))
            } else if (usageMin >= 0 && usageMax >= 0) {
                put("usageMinimum", usageMin)
                put("usageMaximum", usageMax)
            }
            put("reportSize", reportSize)
            put("reportCount", reportCount)
            put("logicalMinimum", logicalMin)
            put("logicalMaximum", logicalMax)
        }

        fun addReportItem(kind: String) {
            if (stack.isEmpty()) return // Main item outside any collection — malformed, skip
            val col = stack.last()
            val listKey = when (kind) {
                "input" -> "inputReports"
                "output" -> "outputReports"
                else -> "featureReports"
            }
            val reports = col.getJSONArray(listKey)
            // find or create the report entry for the current reportId
            var reportObj: JSONObject? = null
            for (i in 0 until reports.length()) {
                val r = reports.getJSONObject(i)
                if (r.getInt("reportId") == reportId) { reportObj = r; break }
            }
            if (reportObj == null) {
                reportObj = JSONObject().put("reportId", reportId).put("items", JSONArray())
                reports.put(reportObj)
            }
            reportObj!!.getJSONArray("items").put(buildItemJson())
        }

        var i = 0
        while (i < bytes.size) {
            val prefix = bytes[i].toInt() and 0xFF
            if (prefix == 0xFE) break // long item — not used by any known PEQ dongle, stop safely
            val size = when (prefix and 0x03) { 3 -> 4; else -> prefix and 0x03 }
            val type = (prefix shr 2) and 0x03
            val tag = (prefix shr 4) and 0x0F
            i++
            if (i + size > bytes.size) break
            var data = 0
            for (b in 0 until size) data = data or ((bytes[i + b].toInt() and 0xFF) shl (8 * b))
            // sign-extend logical/physical min/max (they can be negative)
            val signedData = if (size == 1) data.toByte().toInt()
                else if (size == 2) data.toShort().toInt()
                else data
            i += size

            when (type) {
                1 -> { // Global
                    when (tag) {
                        0x0 -> usagePage = data
                        0x1 -> logicalMin = signedData
                        0x2 -> logicalMax = signedData
                        0x7 -> reportSize = data
                        0x8 -> reportId = data
                        0x9 -> reportCount = data
                        0xA -> globalStack.addLast(intArrayOf(usagePage, logicalMin, logicalMax, reportSize, reportCount, reportId))
                        0xB -> globalStack.removeLastOrNull()?.let {
                            usagePage = it[0]; logicalMin = it[1]; logicalMax = it[2]
                            reportSize = it[3]; reportCount = it[4]; reportId = it[5]
                        }
                    }
                }
                2 -> { // Local
                    when (tag) {
                        0x0 -> usages.add(data)
                        0x1 -> usageMin = data
                        0x2 -> usageMax = data
                    }
                }
                0 -> { // Main
                    when (tag) {
                        0x8 -> { addReportItem("input"); usages = mutableListOf(); usageMin = -1; usageMax = -1 }
                        0x9 -> { addReportItem("output"); usages = mutableListOf(); usageMin = -1; usageMax = -1 }
                        0xB -> { addReportItem("feature"); usages = mutableListOf(); usageMin = -1; usageMax = -1 }
                        0xA -> { // Collection
                            val usage = usages.firstOrNull() ?: usageMin.takeIf { it >= 0 } ?: 0
                            val col = JSONObject().apply {
                                put("usagePage", usagePage)
                                put("usage", usage)
                                put("type", data)
                                put("children", JSONArray())
                                put("inputReports", JSONArray())
                                put("outputReports", JSONArray())
                                put("featureReports", JSONArray())
                            }
                            currentCollectionChildren().put(col)
                            stack.addLast(col)
                            usages = mutableListOf(); usageMin = -1; usageMax = -1
                        }
                        0xC -> { // End Collection
                            stack.removeLastOrNull()
                            usages = mutableListOf(); usageMin = -1; usageMax = -1
                        }
                    }
                }
            }
        }
        return root
    }
}
