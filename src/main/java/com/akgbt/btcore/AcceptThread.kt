package com.akgbt.btcore

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import io.reactivex.rxjava3.subjects.PublishSubject
import java.lang.Exception
import java.util.*

/**
 * auth:  KlayGuo
 * date:  On 2022/6/15
 * 描述:  用于启动监听， 等待客户端连接（类似SOCKET） accept 会阻塞当前线程
 */
@SuppressLint("MissingPermission")
class AcceptThread(private val uuid: UUID) : Thread() {

    private val serverSocket: BluetoothServerSocket? by lazy(LazyThreadSafetyMode.NONE) {
        BluetoothAdapter.getDefaultAdapter()?.listenUsingRfcommWithServiceRecord("BluetoothChatInsecure", uuid)
    }

    private var btSocket: BluetoothSocket? = null

    // 有客户端接入Subject
    public val acceptSubject: PublishSubject<BluetoothSocket> = PublishSubject.create()

    override fun run() {
        super.run()
        var shouldLoop : Boolean = true

        while (shouldLoop) {
            btSocket = try {
                Log.i("AcceptThread", " 服务正在等待监听..... ")
                serverSocket?.accept()
            } catch (e: Exception) {
                shouldLoop = false
                null
            }
            btSocket?.also {
                serverSocket?.close()
                shouldLoop = false
                // 通知有客户端设备连接
                acceptSubject.onNext(it)

                Log.i("BluetoothSocket === : ", "$it")
            }
        }
    }

    public fun cancel() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {

        }
    }
}