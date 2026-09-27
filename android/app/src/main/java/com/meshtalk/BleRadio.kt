package com.meshtalk

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context

/**
 * Connectionless BLE link: every packet is a 24-byte manufacturer-data advertisement.
 * One packet is advertised at a time for [Item.hold] ms; the scanner never stops.
 * All methods are called on the main thread.
 */
@SuppressLint("MissingPermission") // the service only runs after permissions are granted
class BleRadio(ctx: Context, private val onPacket: (ByteArray, Int) -> Unit) {
    private class Item(val data: ByteArray, val notBefore: Long, val hold: Int, val prio: Boolean, val seq: Long)

    private val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private val queue = ArrayList<Item>()
    private var seq = 0L
    private var scanner: BluetoothLeScanner? = null
    private var scanStartedAt = 0L
    private var lastScanTry = 0L
    private var advSet: AdvertisingSet? = null
    private var advStarting = false
    private var advOn = false
    private var advUntil = 0L
    private var advRetryAt = 0L

    var status = "Starting"; private set

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val d = r.scanRecord?.getManufacturerSpecificData(Proto.COMPANY_ID) ?: return
            if (d.size == Proto.LEN && d[0].u() == Proto.MAGIC) onPacket(d, r.rssi)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onScanResult(0, it) }

        override fun onScanFailed(errorCode: Int) {
            if (errorCode == SCAN_FAILED_ALREADY_STARTED) return
            status = "Scan failed ($errorCode), retrying"
            scanner = null
        }
    }

    private val advCb = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, st: Int) {
            advStarting = false
            if (st == ADVERTISE_SUCCESS && set != null) {
                advSet = set
                set.enableAdvertising(false, 0, 0)
                advOn = false
            } else {
                status = "Advertising failed ($st), retrying"
                advRetryAt = System.currentTimeMillis() + 30_000
            }
        }

        override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
            advSet = null
            advOn = false
        }
    }

    fun enqueue(data: ByteArray, delayMs: Long, hold: Int, prio: Boolean) {
        if (queue.size >= 80) queue.removeAt(queue.indexOfFirst { !it.prio }.coerceAtLeast(0))
        queue.add(Item(data, System.currentTimeMillis() + delayMs, hold, prio, seq++))
    }

    /** Called every ~50 ms: keeps scan/advertising alive (e.g. after Bluetooth toggles) and pumps the queue. */
    fun service(now: Long) {
        val a = adapter
        if (a == null || !a.isEnabled) {
            if (scanner != null || advSet != null) teardown()
            status = if (a == null) "No Bluetooth on this device" else "Bluetooth is off"
            return
        }
        // Android silently demotes scans older than 30 min, so restart every 20.
        if ((scanner == null || now - scanStartedAt > 20 * 60_000) && now - lastScanTry > 10_000) startScan(now)
        if (advSet == null && !advStarting && now >= advRetryAt) startAdv(now)
        pump(now)
    }

    private fun startScan(now: Long) {
        lastScanTry = now
        val s = adapter?.bluetoothLeScanner ?: return
        try {
            scanner?.stopScan(scanCb)
            val filter = ScanFilter.Builder()
                .setManufacturerData(Proto.COMPANY_ID, byteArrayOf(Proto.MAGIC.toByte()), byteArrayOf(-1))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .setReportDelay(0)
                .build()
            s.startScan(listOf(filter), settings, scanCb)
            scanner = s
            scanStartedAt = now
            if (advSet != null) status = "Active"
        } catch (e: Exception) {
            status = "Scan error: ${e.message}"
        }
    }

    private fun startAdv(now: Long) {
        val adv = adapter?.bluetoothLeAdvertiser
        if (adv == null) {
            status = "Receive-only: this phone cannot advertise"
            advRetryAt = now + 60_000
            return
        }
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(false)
            .setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_MIN)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()
        try {
            advStarting = true
            adv.startAdvertisingSet(params, AdvertiseData.Builder().build(), null, null, null, advCb)
            if (scanner != null) status = "Active"
        } catch (e: Exception) {
            advStarting = false
            advRetryAt = now + 30_000
            status = "Advertise error: ${e.message}"
        }
    }

    private fun pump(now: Long) {
        val set = advSet ?: return
        if (now < advUntil) return
        var best: Item? = null
        for (it in queue) {
            if (it.notBefore > now) continue
            val b = best
            if (b == null || (it.prio && !b.prio) || (it.prio == b.prio && it.seq < b.seq)) best = it
        }
        try {
            if (best == null) {
                if (advOn) set.enableAdvertising(false, 0, 0)  // never keep repeating a stale packet
                advOn = false
                return
            }
            queue.remove(best)
            set.setAdvertisingData(AdvertiseData.Builder().addManufacturerData(Proto.COMPANY_ID, best.data).build())
            if (!advOn) set.enableAdvertising(true, 0, 0)
            advOn = true
            advUntil = now + best.hold
        } catch (e: Exception) {
            teardown()  // e.g. Bluetooth died underneath us; service() rebuilds everything
        }
    }

    private fun teardown() {
        try { scanner?.stopScan(scanCb) } catch (_: Exception) {}
        try { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(advCb) } catch (_: Exception) {}
        scanner = null
        advSet = null
        advOn = false
        advStarting = false
    }

    fun stop() {
        teardown()
        queue.clear()
        status = "Stopped"
    }
}
