package com.readassist.supernote

import android.graphics.Rect
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Supernote drawPath 的手写禁区客户端。
 *
 * 设备内置 Document 应用使用相同协议：
 * service_myservice / android.demo.IMyService / transaction 1。
 * 每个矩形按 left、top、width、height、flag 写入；flag=0 表示禁写区域。
 */
internal object SupernoteDrawPathClient {
    private const val TAG = "SupernoteDrawPath"
    private const val SERVICE_NAME = "service_myservice"
    private const val INTERFACE_TOKEN = "android.demo.IMyService"
    private const val DOCUMENT_APP_NAME = "superNoteDocument"
    private const val TRANSACTION_SET_WRITABLE_AREAS = 1

    private var binder: IBinder? = null
    private var permanentlyUnavailable = false

    fun setDisabledAreas(rects: List<Rect>): Boolean {
        val service = getBinder() ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            // 保持文档应用当前的 drawPath 会话名，避免切换书写应用。
            data.writeString(DOCUMENT_APP_NAME)
            data.writeInt(rects.size)
            rects.forEach { rect ->
                data.writeInt(rect.left)
                data.writeInt(rect.top)
                data.writeInt(rect.width())
                data.writeInt(rect.height())
                data.writeInt(0)
            }
            val success = service.transact(TRANSACTION_SET_WRITABLE_AREAS, data, reply, 0)
            if (!success) binder = null
            Log.d(TAG, "更新手写禁区: success=$success, rects=$rects")
            success
        } catch (error: Throwable) {
            binder = null
            Log.e(TAG, "更新手写禁区失败: ${error.javaClass.simpleName}: ${error.message}")
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun getBinder(): IBinder? {
        binder?.takeIf(IBinder::isBinderAlive)?.let { return it }
        if (permanentlyUnavailable) return null

        return try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            (getService.invoke(null, SERVICE_NAME) as? IBinder).also { resolved ->
                binder = resolved
                if (resolved == null) {
                    permanentlyUnavailable = true
                    Log.e(TAG, "设备未提供 $SERVICE_NAME")
                }
            }
        } catch (error: Throwable) {
            permanentlyUnavailable = true
            Log.e(TAG, "无法访问 $SERVICE_NAME: ${error.javaClass.simpleName}: ${error.message}")
            null
        }
    }
}
