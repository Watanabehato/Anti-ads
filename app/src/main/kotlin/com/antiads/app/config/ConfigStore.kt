package com.antiads.app.config

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * 配置落盘抽象（internal，不对外导出）。
 *
 * 正式实现 [AtomicFileConfigStore] 用 Android AtomicFile 做原子替换；单元测试注入内存实现，
 * 从而在纯 JVM 上覆盖 revision 比较、health 状态与 Rejected 语义。
 */
internal interface ConfigStore {

    /** 文件不存在返回 null；读取失败抛 IOException。 */
    @Throws(IOException::class)
    fun read(): String?

    /** 原子替换写入；失败抛 IOException（调用方必须返回 Rejected(IO_ERROR)，不得假称已保存）。 */
    @Throws(IOException::class)
    fun write(json: String)
}

internal object ConfigFile {
    /** docs/contracts.md 第 1 节固定的宿主私有配置文件名，不得改名。 */
    const val NAME: String = "protection-config-v1.json"
}

/** filesDir/protection-config-v1.json 的 AtomicFile 实现。 */
internal class AtomicFileConfigStore(context: Context) : ConfigStore {

    private val baseFile = File(context.applicationContext.filesDir, ConfigFile.NAME)
    private val atomicFile = AtomicFile(baseFile)

    override fun read(): String? {
        if (!baseFile.isFile) return null
        return try {
            atomicFile.readFully().toString(Charsets.UTF_8)
        } catch (e: FileNotFoundException) {
            null
        }
    }

    override fun write(json: String) {
        var stream: FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(json.toByteArray(Charsets.UTF_8))
            stream.flush()
            atomicFile.finishWrite(stream)
        } catch (e: IOException) {
            stream?.let { atomicFile.failWrite(it) }
            throw e
        }
    }
}
