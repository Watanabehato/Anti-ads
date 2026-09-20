package com.antiads.hook.internal.policy

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 单线程后台队列的契约：串行、线程数 ≤1、任务异常隔离、卡住任务不产生替代线程。 */
class SingleThreadWorkerTest {

    @Test
    fun tasksRunSeriallyOnExactlyOneThread() {
        val worker = SingleThreadWorker("test-worker")
        val finished = CountDownLatch(3)
        val order = java.util.Collections.synchronizedList(ArrayList<Int>())
        try {
            for (index in 1..3) {
                worker.submit {
                    order.add(index)
                    finished.countDown()
                }
            }
            assertTrue("任务必须全部执行完", finished.await(10, TimeUnit.SECONDS))
            assertEquals(listOf(1, 2, 3), order.toList())
            assertEquals(1, worker.createdThreadCount())
        } finally {
            worker.close()
        }
    }

    @Test
    fun taskFailureIsIsolatedAndWorkerKeepsRunning() {
        val worker = SingleThreadWorker("test-worker-2")
        val ran = AtomicInteger(0)
        val finished = CountDownLatch(1)
        try {
            worker.submit { ran.incrementAndGet(); throw IllegalStateException("boom") }
            worker.submit { ran.incrementAndGet(); finished.countDown() }
            assertTrue(finished.await(10, TimeUnit.SECONDS))
            assertEquals(2, ran.get())
            assertEquals(1, worker.createdThreadCount())
        } finally {
            worker.close()
        }
    }

    @Test
    fun stuckTaskDoesNotSpawnReplacementThreads() {
        val worker = SingleThreadWorker("test-worker-3")
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        try {
            worker.submit {
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            repeat(10) { worker.submit { } }
            Thread.sleep(100L)
            assertEquals("卡住的任务不得导致新建线程", 1, worker.createdThreadCount())
        } finally {
            release.countDown()
            worker.close()
        }
    }

    @Test
    fun submitAfterCloseIsIgnored() {
        val worker = SingleThreadWorker("test-worker-4")
        worker.close()
        worker.submit { throw IllegalStateException("must not run") }
        assertEquals(0, worker.createdThreadCount())
    }
}
