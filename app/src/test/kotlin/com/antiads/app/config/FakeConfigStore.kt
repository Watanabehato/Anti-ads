package com.antiads.app.config

import java.io.IOException

/** 内存 ConfigStore：让仓储的 revision/health/Rejected 语义可以在纯 JVM 上验证。 */
internal class FakeConfigStore(
    var content: String? = null,
    var readError: IOException? = null,
    var writeError: IOException? = null
) : ConfigStore {

    var writeCount: Int = 0
        private set

    var lastWritten: String? = null
        private set

    override fun read(): String? {
        readError?.let { throw it }
        return content
    }

    override fun write(json: String) {
        writeError?.let { throw it }
        writeCount++
        lastWritten = json
        content = json
    }
}
