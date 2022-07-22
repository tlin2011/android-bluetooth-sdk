package com.akgbt.btcore

import android.bluetooth.BluetoothSocket
import android.util.Log
import io.reactivex.rxjava3.subjects.PublishSubject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.lang.Exception

/**
 * auth: KlayGuo
 * date:  On 2022/6/16
 *
 * des: 通讯句柄类
 *
 *   服务端 和 客户端
 *
 */
class CommunicationThread(private val socket: BluetoothSocket) : Thread() {

    private var inputStream: DataInputStream? = null
    private var outputStream: DataOutputStream? = null

    companion object {
        const val TAG: String = "CommunicationThread"
    }

    public val dataSubject: PublishSubject<Pair<Int, ByteArray>> = PublishSubject.create()

    public val readStateSubject: PublishSubject<Boolean> = PublishSubject.create()

    init {
        try {
            inputStream = DataInputStream(socket.inputStream)
            outputStream = DataOutputStream(socket.outputStream)

            Log.i(TAG, "会话建立完成")

        } catch (e: IOException) {
            Log.e(TAG, "获取输出输入流失败")
        }
    }

    override fun run() {
        super.run()
        var numBytes: Int
        while (true) {
            val mmBuffer: ByteArray = ByteArray(2000)
            // 阻塞 收数据
            if (inputStream != null && socket.isConnected) {
                numBytes = try {
                    Log.i(TAG, "run: reading")
                    inputStream!!.read(mmBuffer)
                } catch (e: Exception) {
                    Log.e(TAG, "读取数据异常 $e")
                    readStateSubject.onNext(true)
                    break
                }
                if (numBytes > 0) {
                    val resultByteArray: ByteArray = ByteArray(numBytes)
                    System.arraycopy(mmBuffer, 0, resultByteArray, 0, numBytes)
                    dataSubject.onNext(Pair<Int, ByteArray>(numBytes, resultByteArray))
                }
            }
        }
    }

    /**
     * 写数据
     */
    fun write(bytes: ByteArray) : Boolean {
        if (!socket.isConnected) {
            return false
        }
        return try {
            outputStream?.write(bytes)
            outputStream?.flush()
            true
        } catch (e: IOException) {
            false
        }
    }

    fun cancel() {
        socket.close()
    }
}
