package com.akgbt.btcore

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.subjects.PublishSubject
import java.lang.Exception
import java.nio.charset.Charset
import kotlin.collections.ArrayList

/**
 * auth: KlayGuo
 * date:  On 2022/6/27
 *
 * 客户端管理器：
 * 1：  客户端发起请求连接（建立通讯）
 * 2：  数据队列处理 （任务并发、超时处理）
 * 3：  接受数据Buffer（多包）
 * 4：  重连
 */
class BluetoothClientManager(var content: Context) {

    companion object {
        const val TAG: String = "BluetoothClientManager"
    }

    private var bluetoothAdapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

    val scanSubject: PublishSubject<Pair<String, BluetoothDevice?>> = PublishSubject.create()

    private var clientCommThread: CommunicationThread? = null         // 负责数据收发

    private val taskList: ArrayList<BTTask> = ArrayList()             // 等待任务列表 【按type 归类，同类指令 FIFO】

    private val currentTasks: ArrayList<BTTask> = ArrayList()         // 正在处理的 任务集合 （可用于多任务并行）

    private var historyAddress: String? = null                      // 最后连接设备 mac 地址，用于重连

    private var connected: Boolean = false                          // 已连接标识

    private var connecting: Boolean = false                         // 正在连接状态


    /**
     * 客户端 扫描结果
     */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(p0: Context?, intent: Intent?) {
            intent?.let {
                when(intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        device?.let {
                            val deviceHardwareAddress = device.address
                            Log.i(BluetoothClientManager.TAG, "onReceive: $deviceHardwareAddress")
                            scanSubject.onNext(Pair(BluetoothDevice.ACTION_FOUND, it))
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                        Log.i(BluetoothClientManager.TAG,"scan start")
                        scanSubject.onNext(Pair(BluetoothAdapter.ACTION_DISCOVERY_STARTED, null))
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        unRegisterBluetoothEvent() // 取消注册
                        Log.i(BluetoothClientManager.TAG,"scan end")
                        scanSubject.onNext(Pair(BluetoothAdapter.ACTION_DISCOVERY_FINISHED, null))
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * 取消注册扫描结果通知
     */
    private fun unRegisterBluetoothEvent() {
        try {
            content.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.e(BluetoothClientManager.TAG, "unRegisterBluetoothEvent exception")
        }
    }

    /**
     * 注册扫描结果通知
     */
    private fun registerBluetoothEvent() {
        val intent =  IntentFilter()

        intent.addAction(BluetoothDevice.ACTION_FOUND)
        intent.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
        intent.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        content.registerReceiver(receiver, intent)
    }


    /**
     * 注册蓝牙开关通知
     */
    public fun registerEvent() {
        registerBluetoothStateEvent()
    }

    /**
     * 注册蓝牙状态变化
     */
    private fun registerBluetoothStateEvent() {
        val intent =  IntentFilter()
        intent.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        intent.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        intent.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)

        val stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, intent: Intent?) {
                if (intent == null) {
                    return
                }
                val action = intent!!.action
                action?.let {
                    when (it) {
                        BluetoothAdapter.ACTION_STATE_CHANGED -> {
                            when (val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0)) {
                                BluetoothAdapter.STATE_OFF -> stateOff()
                                BluetoothAdapter.STATE_ON -> stateOn()
                                else -> Log.i(TAG, "unSupport $state")
                            }
                        }
                        BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                            Log.i(BluetoothServerManger.TAG, "onReceive:ACTION_ACL_DISCONNECTED ")
                            handleDisconnect()
                        }
                        BluetoothDevice.ACTION_ACL_CONNECTED -> {
                            Log.i(BluetoothServerManger.TAG, "onReceive:ACTION_ACL_DISCONNECTED ")
                            connected = true
                        }
                        else -> {}
                    }
                }
            }
        }
        content.registerReceiver(stateReceiver, intent)
    }


    private fun handleDisconnect() {
        Log.i(TAG, "disconnect 蓝牙连接被断开")

        connected = false

        clientCommThread?.cancel()
        clientCommThread = null

        currentTasks.clear()
        for (btTask in taskList) {
            btTask.subscribe?.onError(BTException("蓝牙开关被关闭"))
            btTask.subscribe?.onComplete()
        }
        taskList.clear()
    }

    /**
     * 蓝牙被关闭 停止数据传输
     */
    private fun stateOff() {
        Log.i(TAG, "stateOff: 蓝牙被关闭")

        connected = false

        clientCommThread?.cancel()
        clientCommThread = null

        currentTasks.clear()
        for (btTask in taskList) {
            btTask.subscribe?.onError(BTException("蓝牙开关被关闭"))
            btTask.subscribe?.onComplete()
        }
        taskList.clear()
    }

    /**
     * 打开蓝牙 不操作，有数据传输时再连接
     */
    private fun stateOn() {
        Log.i(TAG, "stateOff: 蓝牙被打开")
    }


    /**
     * 蓝牙是否可用
     */
    fun bluetoothEnable() : Boolean {
        return bluetoothAdapter != null
    }

    /**
     * 蓝牙是否打开
     */
    fun bluetoothIsOpen() : Boolean {
        return bluetoothAdapter.isEnabled ?: false
    }

    /**
     * 检查权限
     */
    private fun havePermission() : Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(content, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(content, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 获取配对列表
     */
    @SuppressLint("MissingPermission")
    fun getBondedDevices() : Set<BluetoothDevice> {
        return bluetoothAdapter.bondedDevices;
    }

    /**
     * 通过对象连接
     */
    @Synchronized
    fun connectDevice(device: BluetoothDevice, timeout: Long) : Observable<Boolean> {
        if (connecting) {
            return Observable.error(BTException("正在连接"))
        }
        connecting = true
        // 记录连接历史，用于重连
        historyAddress = device.address

        unRegisterBluetoothEvent()
        cancelDiscovery()

        return Observable.create<Boolean> { emitter ->
            if (connected) {
                connecting = false
                Log.i(TAG, "connectDevice: 已连接 ")
                emitter.onNext(true)
                return@create
            }
             val connectThread = ConnectThread(device)
            //  connect 会阻塞 5 秒后超时， 无需自己加超时处理
            connectThread.connectSubject
                .subscribeBy(onNext = {
                    connecting = false
                    startClientCommunication(it)
                    emitter.onNext(true)
                }, onError = {
                    connecting = false
                    Log.i(BluetoothClientManager.TAG, "尝试连接失败")
                    emitter.onError(it)
                })
            connectThread.start()
        }
    }

    @Synchronized
    private fun connectDeviceWithAddress(address: String) {
        if (connected) {
            Log.i(TAG, "connectDevice: 已连接 ")
            return
        }
        unRegisterBluetoothEvent()
        cancelDiscovery()
        val deviceSet = getBondedDevices();
        val d = deviceSet.find {
            it.address.equals(address)
        }
        if (d != null) {
            reConnectDevice(d)
            return
        }
        // 没有在配对列表找到，开始扫描
        scanSubject
            .subscribeBy(
                onNext = {
                    it.second?.let { device ->
                        if (device.address.equals(address)) {
                            reConnectDevice(device)
                        }
                    }
                }
            )
        startDiscoverDevice()
    }

    @Synchronized
    private fun reConnectDevice(device: BluetoothDevice) {
        connectDevice(device, 15000)
            .subscribeBy(onNext = {
                Log.i(TAG, "onCreate: success connectDeviceWithAddress")
                // 重连成功，如果有任务则执行
                if (taskList.size > 0) {
                    trigger(taskList.first().type)
                }
            }, onError = {
                Log.i(TAG, "onCreate: failed connectDeviceWithAddress")
                // 如果连不上，任务队列重置
                currentTasks.clear()
                for (btTask in taskList) {
                    btTask.subscribe?.onError(BTException("蓝牙未连接"))
                    btTask.subscribe?.onComplete()
                }
                taskList.clear()
            })
    }

    /**
     * 开始扫描
     */
    @SuppressLint("MissingPermission")
    fun startDiscoverDevice() : Boolean {

        if (!bluetoothEnable()) {
            return false
        }

        if (!bluetoothIsOpen()) {
            return false
        }

        if (!havePermission()) {
            return false
        }
        registerBluetoothEvent()
        return bluetoothAdapter.startDiscovery()
    }

    @SuppressLint("MissingPermission")
    private fun cancelDiscovery() {
        if (bluetoothAdapter.isDiscovering) bluetoothAdapter.cancelDiscovery()
    }


    /**
     * 客户端建立可读写通道
     */
    private fun startClientCommunication(bs: BluetoothSocket) {
        clientCommThread = CommunicationThread(bs)
        clientCommThread!!.dataSubject
            .subscribeBy(
                onNext = {
                    val valueString = it.second.toString(Charset.defaultCharset())
                    var d = String(it.second)

                    Log.i(BluetoothClientManager.TAG, "客户端收到数据 size: ${it.first}; $d  ${it.second} data: $valueString ")
                    val source = it.second
                    val taskId = (source[3].toInt() and 0xFF shl 8) or (source[2].toInt() and 0xFF)
                    val ct =  currentTasks.first { t -> t.taskId == taskId }
                    ct.addBuffer(source)

                    if ( ct.checkComplete()) {
                        // 接受数据完成，next
                        val type = ct.type
                        ct.subscribe?.onNext(true)
                        ct.subscribe?.onComplete()
                        ct.complete()           // 完成 停止 超时记时
                        if (currentTasks.contains(ct))  currentTasks.remove(ct)
                        if (taskList.contains(ct))  taskList.remove(ct)
                        Log.i(BluetoothClientManager.TAG, "该任务已完成 task id: $taskId")
                        //下一个
                        trigger(type)
                    }
                },
                onError = {
                    Log.i(BluetoothClientManager.TAG, "爆发异常")
                }
            )
        clientCommThread!!.start()
    }

    /**
     * 数据发送
     */
    @Synchronized
    fun sendData(timeout: Long, message: String, type: Int = 0) : Observable<Boolean> {

        if (!bluetoothEnable()) {
            return Observable.error(BTException("蓝牙不可用"))
        }

        if (!bluetoothIsOpen()) {
            return Observable.error(BTException("蓝牙未开启"))
        }

        // 发起重连
        if (!connected) {
            if (historyAddress == null) {
                return Observable.error(BTException("蓝牙未连接"))
            }
            connectDeviceWithAddress(historyAddress!!)
        }
        return Observable.create<Boolean> { emitter ->
            // 存入队列中
            taskList.add(BTTask(timeout = timeout, sourceData = message.toByteArray(), subscribe = emitter))
            if (connected) {
                trigger(type)
            }
        }
    }

    /**
     * 检测是否空闲， 空闲时即可发送
     *
     * type：任务类别，只有不同的type 才会堵塞，（如所有任务都按顺序，使用同一type即可）
     */
    private fun trigger(type: Int) {
        if (!connected) {
            return
        }
        // 判断是否有同类型的指令正在执行
        if (currentTasks.any { it.type == type }) {
            return
        }
        if (taskList.size <= 0) {
            return
        }
        val btTask = taskList.first { it.type == type }
        btTask?.let {
            currentTasks.add(it)
            val cmdContent = btTask.buildCmdContent(it.sourceData!!)
            btTask.begin()
            btTask.timeoutSubject.subscribeBy(
                onError = { e ->
                    // 指令超时 下一条
                    btTask.subscribe?.onError(e)
                    btTask.subscribe?.onComplete()
                    if (currentTasks.contains(btTask))  currentTasks.remove(btTask)
                    if (taskList.contains(btTask))  taskList.remove(btTask)
                    Log.i(BluetoothClientManager.TAG, "该任务已超时 task id: ${btTask.taskId}")
                    trigger(type)
                }
            )
            val sendResult = clientCommThread!!.write(cmdContent)
            Log.i(BluetoothClientManager.TAG, "客户端返回发送结果: $sendResult ")
        }
    }
}