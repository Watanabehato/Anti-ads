package com.antiads.hook.internal.policy

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * 单一后台 worker（合同第 7.3 节）：整个目标进程只允许一个读取/上报线程。
 *
 * - 提交的任务串行执行；任务异常被隔离，worker 继续可用；
 * - 没有任何看门狗替代线程：即使任务永久卡住（Binder 无可保证超时），也只占用这一个线程；
 * - 线程为 daemon，绝不阻止目标进程正常退出。
 */
class SingleThreadWorker(name: String) : AutoCloseable {

    private val createdThreads = AtomicInteger(0)

    private val executor = Executors.newSingleThreadExecutor(
        ThreadFactory { runnable ->
            createdThreads.incrementAndGet()
            Thread(runnable, name).apply { isDaemon = true }
        }
    )

    /** 提交任务；worker 已关闭时静默忽略（防护功能不受影响）。 */
    fun submit(task: Runnable) {
        try {
            executor.execute {
                try {
                    task.run()
                } catch (t: Throwable) {
                    // 隔离：单个任务失败不得杀死唯一 worker。
                }
            }
        } catch (t: Throwable) {
            // RejectedExecutionException（已关闭）：忽略。
        }
    }

    /** 仅测试/诊断：本 worker 实际创建过的线程数（合同要求恒 ≤1）。 */
    fun createdThreadCount(): Int = createdThreads.get()

    override fun close() {
        executor.shutdownNow()
    }
}
