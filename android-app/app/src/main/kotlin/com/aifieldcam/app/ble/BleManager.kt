package com.aifieldcam.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

enum class BleConnState { IDLE, SCANNING, CONNECTED, ERROR }

class BleManager private constructor(context: Context) {

    interface StatusListener {
        fun onStatusChanged()
    }

    interface ImageListener {
        fun onImageReceived(jpeg: ByteArray, savedFile: java.io.File)
    }

    interface CmdEventListener {
        fun onCmdEvent(evtId: Int, payload: ByteArray)
    }

    interface VideoListener {
        fun onVideoReceived(data: ByteArray, savedFile: java.io.File)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gatt: BluetoothGatt? = null
    private var charCmdWrite: BluetoothGattCharacteristic? = null

    var connState: BleConnState = BleConnState.IDLE
        private set
    var lastError: String = ""
        private set
    var batteryPercent: Int = -1
        private set
    var deviceState: Int = BleConfig.FSM_IDLE
        private set
    var sensorFlags: Int = 0
        private set

    private val statusListeners = CopyOnWriteArraySet<StatusListener>()
    private val imageListeners = CopyOnWriteArraySet<ImageListener>()
    private val videoListeners = CopyOnWriteArraySet<VideoListener>()
    private var cmdEventListener: CmdEventListener? = null

    private var pendingImageSize = 0
    private val pendingImageSizes = ConcurrentHashMap<Int, Int>()
    private val imageBuffers = ConcurrentHashMap<Int, ByteArray>()
    private val imageTotals = ConcurrentHashMap<Int, Int>()
    private val imageReceived = ConcurrentHashMap<Int, MutableSet<Int>>()
    private var imageAssemblyTimeout: Runnable? = null

    private var pendingVideoExpectedSize = 0
    private val videoBuffers = ConcurrentHashMap<Int, ByteArray>()
    private val videoTotals = ConcurrentHashMap<Int, Int>()
    private val videoReceived = ConcurrentHashMap<Int, MutableSet<Int>>()
    private var videoAssemblyTimeout: Runnable? = null

    private var scanTimeoutRunnable: Runnable? = null
    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private var notifyGatt: BluetoothGatt? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!isTargetDevice(result)) return
            stopScan()
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            connState = BleConnState.ERROR
            lastError = "扫描失败: $errorCode"
            notifyStatusChanged()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        failConnection("连接失败: $status")
                        return
                    }
                    if (!gatt.requestMtu(BLE_MTU)) {
                        gatt.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connState == BleConnState.CONNECTED || connState == BleConnState.SCANNING) {
                        if (status != BluetoothGatt.GATT_SUCCESS && lastError.isEmpty()) {
                            lastError = "连接断开: $status"
                            connState = BleConnState.ERROR
                        } else if (connState != BleConnState.ERROR) {
                            resetGattState()
                        }
                    } else {
                        resetGattState()
                    }
                    notifyStatusChanged()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                lastError = "MTU 协商失败($status)，使用默认 MTU"
            }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnection("发现服务失败")
                return
            }
            val service = gatt.services.firstOrNull {
                BleConfig.charIdMatches(it.uuid.toString(), "A001")
            }
            if (service == null) {
                failConnection("未找到 A001 服务")
                return
            }
            charCmdWrite = service.characteristics.firstOrNull {
                BleConfig.charIdMatches(it.uuid.toString(), "A002")
            }
            if (charCmdWrite == null) {
                failConnection("未找到 CMD_WRITE (A002)")
                return
            }
            val notifyChars = listOfNotNull(
                service.characteristics.firstOrNull {
                    BleConfig.charIdMatches(it.uuid.toString(), "A003")
                },
                service.characteristics.firstOrNull {
                    BleConfig.charIdMatches(it.uuid.toString(), "A006")
                },
                service.characteristics.firstOrNull {
                    BleConfig.charIdMatches(it.uuid.toString(), "A007")
                },
                service.characteristics.firstOrNull {
                    BleConfig.charIdMatches(it.uuid.toString(), "A008")
                },
            )
            if (notifyChars.isEmpty()) {
                failConnection("未找到可订阅的特征值")
                return
            }
            startNotifyChain(gatt, notifyChars)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnection("启用通知失败: $status")
                return
            }
            processNextNotify()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            handleCharacteristicValue(characteristic.uuid.toString(), characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleCharacteristicValue(characteristic.uuid.toString(), value)
        }
    }

    fun addStatusListener(listener: StatusListener) {
        statusListeners.add(listener)
    }

    fun removeStatusListener(listener: StatusListener) {
        statusListeners.remove(listener)
    }

    fun addImageListener(listener: ImageListener) {
        imageListeners.add(listener)
    }

    fun removeImageListener(listener: ImageListener) {
        imageListeners.remove(listener)
    }

    fun setCmdEventListener(listener: CmdEventListener?) {
        cmdEventListener = listener
    }

    fun addVideoListener(listener: VideoListener) {
        videoListeners.add(listener)
    }

    fun removeVideoListener(listener: VideoListener) {
        videoListeners.remove(listener)
    }

    fun isCharging(): Boolean = (sensorFlags and BleConfig.SENSOR_FLAG_CHARGING) != 0

    fun getStatusSummary(): String = when (connState) {
        BleConnState.CONNECTED -> buildString {
            append("已连接 · ")
            append(BleConfig.fsmStateLabel(deviceState))
            if (batteryPercent >= 0) append(" · 电量 $batteryPercent%")
            if (isCharging()) append(" · 充电中")
        }
        BleConnState.SCANNING -> "扫描中…"
        BleConnState.ERROR -> lastError.ifBlank { "连接错误" }
        BleConnState.IDLE -> "未连接"
    }

    fun canStartConnect(): Boolean {
        return connState == BleConnState.IDLE || connState == BleConnState.ERROR
    }

    @SuppressLint("MissingPermission")
    fun startConnect(onDone: (Boolean, String) -> Unit) {
        if (!BlePermissionHelper.hasAll(appContext)) {
            val msg = "缺少蓝牙权限，请在系统设置中授权"
            lastError = msg
            connState = BleConnState.ERROR
            notifyStatusChanged()
            onDone(false, msg)
            return
        }
        if (!canStartConnect()) {
            onDone(false, "当前正在连接或已连接")
            return
        }
        if (adapter == null || !adapter.isEnabled) {
            onDone(false, "请开启蓝牙")
            return
        }

        lastError = ""
        resetImageAssembly()
        resetVideoAssembly()
        connState = BleConnState.SCANNING
        notifyStatusChanged()

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            connState = BleConnState.ERROR
            lastError = "BLE 扫描不可用"
            onDone(false, lastError)
            notifyStatusChanged()
            return
        }

        scanTimeoutRunnable?.let(mainHandler::removeCallbacks)
        scanTimeoutRunnable = Runnable {
            if (connState == BleConnState.SCANNING) {
                stopScan()
                connState = BleConnState.ERROR
                val msg = "未找到 ${BleConfig.BLE_DEVICE_NAME_PREFIX}，请确认固件已烧录"
                lastError = msg
                notifyStatusChanged()
                onDone(false, msg)
            }
        }
        mainHandler.postDelayed(scanTimeoutRunnable!!, SCAN_TIMEOUT_MS)

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(filters, settings, scanCallback)
        onDone(true, "正在扫描…")
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        closeGatt()
        resetGattState()
        notifyStatusChanged()
    }

    @SuppressLint("MissingPermission")
    fun writeCmd(cmdId: Int, payload: ByteArray = ByteArray(0)): Boolean {
        if (!BlePermissionHelper.hasAll(appContext)) {
            lastError = "缺少蓝牙权限"
            return false
        }
        if (connState != BleConnState.CONNECTED) {
            lastError = "未连接设备"
            return false
        }
        val characteristic = charCmdWrite ?: run {
            lastError = "GATT 未就绪"
            return false
        }
        val gattClient = gatt ?: run {
            lastError = "GATT 未就绪"
            return false
        }
        val buffer = ByteBuffer.allocate(3 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(cmdId.toByte())
        buffer.putShort(payload.size.toShort())
        buffer.put(payload)
        characteristic.value = buffer.array()
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return gattClient.writeCharacteristic(characteristic)
    }

    private fun isTargetDevice(result: ScanResult): Boolean {
        val name = result.device.name ?: result.scanRecord?.deviceName ?: ""
        if (name.contains(BleConfig.BLE_DEVICE_NAME_PREFIX)) return true
        val serviceUuids = result.scanRecord?.serviceUuids ?: return false
        return serviceUuids.any { BleConfig.charIdMatches(it.uuid.toString(), "A001") }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        closeGatt()
        gatt = device.connectGatt(appContext, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        scanTimeoutRunnable?.let(mainHandler::removeCallbacks)
        scanTimeoutRunnable = null
        if (BlePermissionHelper.hasAll(appContext)) {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNotifyChain(gatt: BluetoothGatt, characteristics: List<BluetoothGattCharacteristic>) {
        notifyGatt = gatt
        notifyQueue.clear()
        notifyQueue.addAll(characteristics)
        processNextNotify()
    }

    @SuppressLint("MissingPermission")
    private fun processNextNotify() {
        val gattClient = notifyGatt
        if (gattClient == null) {
            failConnection("GATT 已断开")
            return
        }
        val characteristic = notifyQueue.removeFirstOrNull()
        if (characteristic == null) {
            connState = BleConnState.CONNECTED
            lastError = ""
            notifyStatusChanged()
            return
        }
        gattClient.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_UUID)
        if (descriptor == null) {
            processNextNotify()
            return
        }
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gattClient.writeDescriptor(descriptor)
    }

    private fun handleCharacteristicValue(uuid: String, value: ByteArray?) {
        if (value == null || value.isEmpty()) return
        when {
            BleConfig.charIdMatches(uuid, "A008") -> handleSensorNotify(value)
            BleConfig.charIdMatches(uuid, "A006") -> handleImageChunk(value)
            BleConfig.charIdMatches(uuid, "A007") -> handleVideoChunk(value)
            BleConfig.charIdMatches(uuid, "A003") -> handleCmdNotify(value)
        }
    }

    private fun handleSensorNotify(value: ByteArray) {
        if (value.size < 3) return
        val prevState = deviceState
        val prevBattery = batteryPercent
        val prevFlags = sensorFlags
        batteryPercent = value[0].toInt() and 0xFF
        deviceState = value[1].toInt() and 0xFF
        sensorFlags = value[2].toInt() and 0xFF
        if (prevState != deviceState || prevBattery != batteryPercent || prevFlags != sensorFlags) {
            notifyStatusChanged()
        }
    }

    private fun handleCmdNotify(value: ByteArray) {
        val evt = value[0].toInt() and 0xFF
        cmdEventListener?.onCmdEvent(evt, value)
        if (evt == BleConfig.EVT_CAPTURE_DONE) {
            resetImageAssembly()
            if (value.size >= 5) {
                val size = ByteBuffer.wrap(value, 1, 4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .int
                pendingImageSize = size
                pendingImageSizes[FIRMWARE_IMAGE_MSG_ID] = size
                scheduleImageAssemblyTimeout()
            }
        }
        if (evt == BleConfig.EVT_RECORD_STOPPED && value.size >= 9) {
            resetVideoAssembly()
            pendingVideoExpectedSize = ByteBuffer.wrap(value, 5, 4)
                .order(ByteOrder.BIG_ENDIAN)
                .int
            if (pendingVideoExpectedSize > 0) {
                scheduleVideoAssemblyTimeout()
            }
        }
    }

    private fun scheduleImageAssemblyTimeout() {
        imageAssemblyTimeout?.let(mainHandler::removeCallbacks)
        imageAssemblyTimeout = Runnable {
            if (imageBuffers.isNotEmpty()) {
                lastError = "图片接收超时，请重试拍照"
                resetImageAssembly()
                notifyStatusChanged()
            }
        }
        mainHandler.postDelayed(imageAssemblyTimeout!!, IMAGE_ASSEMBLY_TIMEOUT_MS)
    }

    private fun handleImageChunk(value: ByteArray) {
        if (value.size < 6) return
        val buffer = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN)
        val msgId = buffer.short.toInt() and 0xFFFF
        val seq = buffer.short.toInt() and 0xFFFF
        val total = buffer.short.toInt() and 0xFFFF
        val dataLen = value.size - 6
        if (total <= 0 || seq >= total || dataLen <= 0) return

        if (!imageTotals.containsKey(msgId)) {
            val expectedSize = pendingImageSizes[msgId] ?: pendingImageSize
            val cap = if (expectedSize > 0) expectedSize else total * IMAGE_CHUNK_SIZE
            imageTotals[msgId] = total
            imageBuffers[msgId] = ByteArray(cap)
            imageReceived[msgId] = mutableSetOf()
            scheduleImageAssemblyTimeout()
        }

        val buf = imageBuffers[msgId] ?: return
        val got = imageReceived[msgId] ?: return
        if (got.contains(seq)) return

        val offset = seq * IMAGE_CHUNK_SIZE
        if (offset + dataLen > buf.size) return
        System.arraycopy(value, 6, buf, offset, dataLen)
        got.add(seq)
        tryFinishImage(msgId)
    }

    private fun isValidJpegHeader(jpeg: ByteArray): Boolean {
        return jpeg.size >= 2 &&
            (jpeg[0].toInt() and 0xFF) == 0xFF &&
            (jpeg[1].toInt() and 0xFF) == 0xD8
    }

    private fun tryFinishImage(msgId: Int) {
        val total = imageTotals[msgId] ?: return
        val buf = imageBuffers[msgId] ?: return
        val got = imageReceived[msgId] ?: return
        if (got.size < total) return

        val expectedSize = pendingImageSizes[msgId] ?: pendingImageSize
        val outLen = when {
            expectedSize > 0 -> expectedSize.coerceAtMost(buf.size)
            else -> {
                val lastSeq = total - 1
                val lastOffset = lastSeq * IMAGE_CHUNK_SIZE
                val filled = buf.indexOfLast { it != 0.toByte() } + 1
                maxOf(filled, lastOffset + IMAGE_CHUNK_SIZE).coerceAtMost(buf.size)
            }
        }
        val jpeg = buf.copyOf(outLen)
        if (!isValidJpegHeader(jpeg)) {
            lastError = "图片数据校验失败，请重试"
            resetImageAssembly()
            notifyStatusChanged()
            return
        }
        resetImageAssembly()
        mainHandler.post { dispatchImage(jpeg) }
    }

    private fun handleVideoChunk(value: ByteArray) {
        if (value.size < 8) return
        val buffer = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN)
        val fileId = buffer.int
        val seq = buffer.short.toInt() and 0xFFFF
        val total = buffer.short.toInt() and 0xFFFF
        val dataLen = value.size - 8
        if (total <= 0 || seq >= total || dataLen <= 0) return

        if (!videoTotals.containsKey(fileId)) {
            val cap = if (pendingVideoExpectedSize > 0) {
                pendingVideoExpectedSize
            } else {
                total * VIDEO_CHUNK_SIZE
            }
            videoTotals[fileId] = total
            videoBuffers[fileId] = ByteArray(cap)
            videoReceived[fileId] = mutableSetOf()
            scheduleVideoAssemblyTimeout()
        }

        val buf = videoBuffers[fileId] ?: return
        val got = videoReceived[fileId] ?: return
        if (got.contains(seq)) return

        val offset = seq * VIDEO_CHUNK_SIZE
        if (offset + dataLen > buf.size) return
        System.arraycopy(value, 8, buf, offset, dataLen)
        got.add(seq)
        tryFinishVideo(fileId)
    }

    private fun tryFinishVideo(fileId: Int) {
        val total = videoTotals[fileId] ?: return
        val buf = videoBuffers[fileId] ?: return
        val got = videoReceived[fileId] ?: return
        if (got.size < total) return

        val outLen = if (pendingVideoExpectedSize > 0) {
            pendingVideoExpectedSize.coerceAtMost(buf.size)
        } else {
            val lastSeq = total - 1
            val filled = buf.indexOfLast { it != 0.toByte() } + 1
            maxOf(filled, (lastSeq + 1) * VIDEO_CHUNK_SIZE).coerceAtMost(buf.size)
        }
        val data = buf.copyOf(outLen)
        resetVideoAssembly()
        mainHandler.post { dispatchVideo(data) }
    }

    private fun dispatchVideo(data: ByteArray) {
        val file = VideoStore.saveVideo(appContext, data)
        videoListeners.forEach { it.onVideoReceived(data, file) }
    }

    private fun scheduleVideoAssemblyTimeout() {
        videoAssemblyTimeout?.let(mainHandler::removeCallbacks)
        videoAssemblyTimeout = Runnable {
            if (videoBuffers.isNotEmpty()) {
                lastError = "录像文件接收超时"
                resetVideoAssembly()
                notifyStatusChanged()
            }
        }
        mainHandler.postDelayed(videoAssemblyTimeout!!, VIDEO_ASSEMBLY_TIMEOUT_MS)
    }

    private fun resetVideoAssembly() {
        videoAssemblyTimeout?.let(mainHandler::removeCallbacks)
        videoAssemblyTimeout = null
        pendingVideoExpectedSize = 0
        videoBuffers.clear()
        videoTotals.clear()
        videoReceived.clear()
    }

    private fun dispatchImage(jpeg: ByteArray) {
        val file = AlbumStore.saveImage(appContext, jpeg)
        imageListeners.forEach { it.onImageReceived(jpeg, file) }
    }

    private fun resetImageAssembly() {
        imageAssemblyTimeout?.let(mainHandler::removeCallbacks)
        imageAssemblyTimeout = null
        pendingImageSize = 0
        pendingImageSizes.clear()
        imageBuffers.clear()
        imageTotals.clear()
        imageReceived.clear()
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val client = gatt ?: return
        try {
            client.disconnect()
        } catch (_: Exception) {
        }
        try {
            client.close()
        } catch (_: Exception) {
        }
        gatt = null
    }

    private fun failConnection(message: String) {
        lastError = message
        connState = BleConnState.ERROR
        notifyQueue.clear()
        notifyGatt = null
        closeGatt()
        charCmdWrite = null
        notifyStatusChanged()
    }

    private fun resetGattState() {
        gatt = null
        charCmdWrite = null
        notifyQueue.clear()
        notifyGatt = null
        connState = BleConnState.IDLE
        batteryPercent = -1
        deviceState = BleConfig.FSM_IDLE
        sensorFlags = 0
    }

    private fun notifyStatusChanged() {
        mainHandler.post {
            statusListeners.forEach { it.onStatusChanged() }
        }
    }

    companion object {
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val IMAGE_ASSEMBLY_TIMEOUT_MS = 30_000L
        private const val VIDEO_ASSEMBLY_TIMEOUT_MS = 60_000L
        private const val IMAGE_CHUNK_SIZE = 512
        private const val VIDEO_CHUNK_SIZE = 512
        private const val FIRMWARE_IMAGE_MSG_ID = 1
        private const val BLE_MTU = 517
        private val SERVICE_UUID = UUID.fromString(BleConfig.BLE_SERVICE_UUID)
        private val CLIENT_CONFIG_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        @Volatile
        private var instance: BleManager? = null

        fun getInstance(context: Context): BleManager {
            return instance ?: synchronized(this) {
                instance ?: BleManager(context).also { instance = it }
            }
        }
    }
}
