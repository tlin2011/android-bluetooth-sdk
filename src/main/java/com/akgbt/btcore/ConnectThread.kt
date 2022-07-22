package com.akgbt.btcore

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import io.reactivex.rxjava3.subjects.PublishSubject
import java.io.IOException
import java.util.*

/**
 * auth: KlayGuo
 * date:  On 2022/6/16
 */
@SuppressLint("MissingPermission")
class ConnectThread(private val device: BluetoothDevice) : Thread() {

    companion object {
        val serverUUID : UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val TAG: String = "ConnectThread"
    }

    private val socket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
        device.createRfcommSocketToServiceRecord(serverUUID)
    }

    public val connectSubject: PublishSubject<BluetoothSocket> = PublishSubject.create()

    override fun run() {
        super.run()
        // 连接前，取消扫描
        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        if (socket != null) {
            try {
                socket!!.connect()      // 5 秒会自动超时
                connectSubject.onNext(socket)
            } catch (e: IOException) {
                Log.i(TAG, "请求连接异常: $e")
                socket!!.close()
                connectSubject.onError(Error())
            }
        }
    }

    fun cancel() {
        try {
            socket!!.close()
        } catch (e: IOException) {
            Log.i(TAG, "关闭连接异常")
        }
    }
}