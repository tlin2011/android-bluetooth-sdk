package com.akgbt.btcore

import io.reactivex.rxjava3.core.ObservableEmitter
import io.reactivex.rxjava3.subjects.PublishSubject
import java.util.*

/**
 * auth: KlayGuo
 * date:  On 2022/6/20
 *
 * des： 任务对象
 *
 *  1： 存储要发送的数据 （任务ID 管理）
 *  2： 任务超时处理
 *  3： 数据组装 （ 任务ID、报文组装前后缀、长度、CRC校验等）
 *  3： 结束校验 （ 只有任务本身，才知道是否正确结束）
 *
 */
class BTTask(var taskId: Int = (0..1000).random(), val timeout: Long = 20000, var sourceData: ByteArray? = null, val type: Int = 0, var subscribe: ObservableEmitter<Boolean>? = null) {

    private var bufferLength : Int = 0
    private val receiveBuffer: MutableList<ByteArray> = arrayListOf()

    val timeoutSubject: PublishSubject<Int> = PublishSubject.create()

    private val timer = Timer()

    fun begin() {
        receiveBuffer.clear()
        val timerTask = object : TimerTask() {
            override fun run() {
                timeoutSubject.onError(BTException("未响应超时"))
            }
        }
        timer.schedule(timerTask, timeout)
    }

    public fun complete() {
        timer.cancel()
    }

    public fun addBuffer(buffer: ByteArray) {
        bufferLength += buffer.size
        receiveBuffer.add(buffer)
    }

    public fun checkComplete() : Boolean {

        if (bufferLength == 0 || receiveBuffer.size == 0) {
            return false
        }

        var totalData: ByteArray = ByteArray(bufferLength);

        var index: Int = 0

        for (buffer in receiveBuffer) {
            System.arraycopy(buffer, 0, totalData, index, buffer.size)
            index+= buffer.size
        }

        // 数据长度 和 统计长度不一致
        if (totalData.size != bufferLength) {
            return false
        }

        // 头2 + 尾巴2 + 长度2 至少 6 （协议决定）
        if (totalData.size < 6) {
            return false
        }

        val rTaskId = (totalData[3].toInt() and 0xFF shl 8) or (totalData[2].toInt() and 0xFF)

        // 头尾交易
        if (totalData[0] == 0xAA.toByte() &&
            totalData[1] == 0xBB.toByte() &&
            totalData[bufferLength - 2] == 0xCC.toByte() &&
            totalData[bufferLength - 1] == 0xDD.toByte() &&
            rTaskId == taskId ) {
            return true
        }
        return false;
    }

    public fun buildCmdContent(source: ByteArray) : ByteArray  {

        val taskId = this.taskId
        val taskIdByteArray: ByteArray = ByteArray(2)
        taskIdByteArray[0] = (taskId shr 0).toByte()
        taskIdByteArray[1] = (taskId shr 8).toByte()

        // 组装 头 cid 尾  crc 长度 等
        val head: ByteArray = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val end: ByteArray = byteArrayOf(0xCC.toByte(), 0xDD.toByte())

        val sourceLength = source.size

        val lengthByteArray: ByteArray = ByteArray(2)            // 长度 占位
        lengthByteArray[0] = (sourceLength shr 0).toByte()
        lengthByteArray[1] = (sourceLength shr 8).toByte()

        return head + taskIdByteArray +  lengthByteArray + source + end
    }
}