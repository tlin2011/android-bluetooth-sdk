package com.akgbt.btcore

import android.Manifest
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import io.reactivex.rxjava3.kotlin.subscribeBy
import java.nio.charset.Charset
import java.util.*

/**
 * auth: KlayGuo
 * date:  On 2022/6/27
 *
 * des:
 *
 * 服务端管理器：
 *
 *      1： 服务器监听管理
 *      2： 接受客户端请求并建立连接
 *      3： 接受客户端数据处理并返回数据 （taskId ）
 *
 *
 */
class BluetoothServerManger(var content: Context) {

    companion object {
        // 广播的 uuid
        val serverUUID : UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val TAG: String = "BluetoothServerManger"
    }

    private var bluetoothAdapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    private var acceptThread: AcceptThread? = null                    // 负责处理客户端接入等待线程
    private var serverCommThread: CommunicationThread? = null         // 负责读写线程
    private var bluetoothSocket: BluetoothSocket? = null              // 连接对象

    private var tempTask: BTTask? = null                              // 当前正在处理的对象 （等待数据接受完成并处理）


    /**
     * 检查权限
     */
    fun havePermission() : Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(content, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(content, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }
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
     * 作为服务端启动监听（广播）
     */
    fun startAdvertiser() {
        if (acceptThread == null) {
            acceptThread = AcceptThread(uuid = BluetoothServerManger.serverUUID)
            acceptThread!!.acceptSubject
                .subscribeBy(
                    onNext = {
                        bluetoothSocket = it
                        startServerCommunication(it)
                    }
                )
            acceptThread!!.start()
        }
    }

    /**
     * 有客户端接入，建立连接
     */
    private fun startServerCommunication(bs: BluetoothSocket) {
        serverCommThread = CommunicationThread(bs)

        serverCommThread!!.readStateSubject
            .subscribeBy(onNext = {
                reStartAdvertiser()
            }, onError = {

            })

        // 收到客户端数据
        serverCommThread!!.dataSubject
            .subscribeBy(
                onNext = {
                    val valueString = it.second.toString(Charset.defaultCharset())
                    var d = String(it.second)
                    val string2 = String(it.second, Charsets.UTF_16)
                    Log.i(BluetoothClientManager.TAG, "服务端收到数据 size: ${it.first}; data: $d $valueString  ${it.second} $string2")
                    val receiverData =  it.second
                    // 解析报文为， AABB （按实际协议定） 获取到 taskId 用于返回
                    if (receiverData.size > 4 &&
                        receiverData[0] == 0xAA.toByte() &&
                        receiverData[1] == 0xBB.toByte()) {
                        tempTask = BTTask()
                        val rTaskId = (receiverData[3].toInt() and 0xFF shl 8) or (receiverData[2].toInt() and 0xFF)
                        tempTask!!.taskId = rTaskId
                    }
                    if (tempTask == null) {
                        return@subscribeBy
                    }
                    tempTask?.addBuffer(receiverData)
                    // 检测数据接受完成， 返回给客户端
                    if (tempTask?.checkComplete() == true) {
                        val responseByte = byteArrayOf(1, 2, 3, 4, 5)
                        responseData(tempTask!!.buildCmdContent(responseByte))
                        Log.i(BluetoothClientManager.TAG, "服务端回复了数据")
                    }
                },
                onError = {
                    Log.i(BluetoothClientManager.TAG, "startServerCommunication: 出错了")
                }
            )
        serverCommThread!!.start()
    }

    /**
     * 取消广播
     */
    fun endAdvertiser() {
        acceptThread?.cancel()
    }

    /**
     * 返回数据
     */
    @Synchronized
    private fun responseData(message: ByteArray)  {
        if (serverCommThread != null) {
            val sendResult = serverCommThread!!.write(message)
            Log.i(BluetoothClientManager.TAG, "服务端返回发送结果: $sendResult ")
        }
    }


    /**
     * 监听手机蓝牙开关变化，做出处理
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
                                else -> Log.i(BluetoothClientManager.TAG, "unSupport $state")
                            }
                        }
                        BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                            Log.i(TAG, "BluetoothDevice: ACTION_ACL_DISCONNECTED ")
                        }
                        BluetoothDevice.ACTION_ACL_CONNECTED -> {
                            Log.i(TAG, "BluetoothDevice: ACTION_ACL_CONNECTED ")
                        }
                        else -> {}
                    }
                }
            }
        }
        content.registerReceiver(stateReceiver, intent)
    }

    public fun registerEvent() {
        registerBluetoothStateEvent()
    }

    private fun stateOff() {
        Log.i(BluetoothClientManager.TAG, "stateOff: 蓝牙被关闭")
    }

    private fun stateOn() {
        Log.i(BluetoothClientManager.TAG, "stateOff: 蓝牙被打开")

        reStartAdvertiser()
    }

    private fun handleDisconnect() {
        reStartAdvertiser()
    }

    private fun reStartAdvertiser () {
        acceptThread?.cancel()
        acceptThread = null

        serverCommThread?.cancel()
        serverCommThread = null

        bluetoothSocket = null
        tempTask = null

        // 启动 accept
        startAdvertiser()
    }

}