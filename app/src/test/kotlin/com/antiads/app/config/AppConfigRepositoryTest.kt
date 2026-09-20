package com.antiads.app.config

import com.antiads.core.config.ConfigCodec
import com.antiads.core.config.ConfigConstants
import com.antiads.core.config.ConfigStorageState
import com.antiads.core.config.ConfigWriteResult
import com.antiads.core.config.PackageConfig
import com.antiads.core.config.ProtectionConfig
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置仓储契约测试（docs/contracts.md 第 3 节）。
 * 覆盖 revision 比较、Rejected 原因、health 四态、observe 语义与“不假称已保存”。
 */
class AppConfigRepositoryTest {

    @Test
    fun defaultsWhenNoFile() {
        val repository = AppConfigRepository(FakeConfigStore(content = null))

        assertEquals(ProtectionConfig(), repository.snapshot())
        assertEquals(ConfigStorageState.DEFAULTS_NO_FILE, repository.health().state)
        assertFalse(repository.snapshot().masterEnabled)
    }

    @Test
    fun corruptFileFallsBackToAllOff() {
        val repository = AppConfigRepository(FakeConfigStore(content = "{ this is not json"))

        assertEquals(ConfigStorageState.RECOVERED_CORRUPT, repository.health().state)
        assertEquals("INVALID_JSON", repository.health().errorCode)
        val snapshot = repository.snapshot()
        assertFalse(snapshot.masterEnabled)
        assertFalse(snapshot.accessibilityEnabled)
        assertFalse(snapshot.hookEnabled)
        assertTrue(snapshot.packages.isEmpty())
        assertEquals(0L, snapshot.revision)
    }

    @Test
    fun unknownSchemaFileIsRejectedAndDoesNotReuseEnableValues() {
        val damaged = "{\"schemaVersion\":9,\"revision\":3,\"masterEnabled\":true,\"accessibilityEnabled\":true," +
            "\"hookEnabled\":true,\"packages\":{}}"
        val repository = AppConfigRepository(FakeConfigStore(content = damaged))

        assertEquals(ConfigStorageState.RECOVERED_CORRUPT, repository.health().state)
        assertEquals("UNSUPPORTED_SCHEMA_VERSION", repository.health().errorCode)
        assertFalse(repository.snapshot().masterEnabled)
    }

    @Test
    fun readFailureReportsIoError() {
        val store = FakeConfigStore(content = "{}", readError = IOException("boom"))
        val repository = AppConfigRepository(store)

        assertEquals(ConfigStorageState.IO_ERROR, repository.health().state)
        assertEquals(AppConfigRepository.ERROR_CODE_IO, repository.health().errorCode)
        assertEquals(ProtectionConfig(), repository.snapshot())
    }

    @Test
    fun writePersistsNewRevisionAndPublishesSnapshot() {
        val store = FakeConfigStore()
        val repository = AppConfigRepository(store)

        val result = repository.write(ProtectionConfig(masterEnabled = true), 0L)

        assertTrue(result is ConfigWriteResult.Saved)
        val saved = (result as ConfigWriteResult.Saved).config
        assertEquals(1L, saved.revision)
        assertTrue(saved.masterEnabled)
        assertEquals(1L, repository.snapshot().revision)
        assertEquals(ConfigStorageState.READY, repository.health().state)
        assertEquals(1, store.writeCount)
        assertNotNull(store.lastWritten)
        val persisted = ConfigCodec.decodeConfig(store.lastWritten!!)
        assertEquals(1L, persisted.revision)
        assertTrue(persisted.masterEnabled)
    }

    @Test
    fun staleExpectedRevisionIsRejectedWithoutTouchingDisk() {
        val store = FakeConfigStore()
        val repository = AppConfigRepository(store)
        repository.write(ProtectionConfig(masterEnabled = true), 0L)

        val result = repository.write(ProtectionConfig(masterEnabled = false), 0L)

        assertTrue(result is ConfigWriteResult.Rejected)
        assertEquals(AppConfigRepository.REASON_CONFLICT, (result as ConfigWriteResult.Rejected).reason)
        assertEquals(1, store.writeCount)
        assertEquals(1L, repository.snapshot().revision)
        assertTrue(repository.snapshot().masterEnabled)
    }

    @Test
    fun mismatchedInputRevisionIsRejectedAsInvalid() {
        val store = FakeConfigStore()
        val repository = AppConfigRepository(store)

        val result = repository.write(ProtectionConfig(masterEnabled = true, revision = 5L), 0L)

        assertTrue(result is ConfigWriteResult.Rejected)
        assertEquals(AppConfigRepository.REASON_INVALID, (result as ConfigWriteResult.Rejected).reason)
        assertEquals(0, store.writeCount)
        assertEquals(0L, repository.snapshot().revision)
    }

    @Test
    fun invalidConfigIsRejected() {
        val store = FakeConfigStore()
        val repository = AppConfigRepository(store)
        val invalid = ProtectionConfig(
            masterEnabled = true,
            packages = mapOf("android" to PackageConfig())
        )

        val result = repository.write(invalid, 0L)

        assertTrue(result is ConfigWriteResult.Rejected)
        assertEquals(AppConfigRepository.REASON_INVALID, (result as ConfigWriteResult.Rejected).reason)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun outOfRangeSensorTypesAreRejected() {
        val store = FakeConfigStore()
        val repository = AppConfigRepository(store)
        val invalid = ProtectionConfig(
            packages = mapOf("com.example.app" to PackageConfig(blockedSensorTypes = setOf(1, 99)))
        )

        val result = repository.write(invalid, 0L)

        assertTrue(result is ConfigWriteResult.Rejected)
        assertEquals(AppConfigRepository.REASON_INVALID, (result as ConfigWriteResult.Rejected).reason)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun ioFailureKeepsPreviousSnapshotAndReportsIoError() {
        val store = FakeConfigStore()
        val repository = AppConfigRepository(store)
        repository.write(ProtectionConfig(masterEnabled = true), 0L)
        store.writeError = IOException("disk full")

        val result = repository.write(ProtectionConfig(masterEnabled = false, revision = 1L), 1L)

        assertTrue(result is ConfigWriteResult.Rejected)
        assertEquals(AppConfigRepository.REASON_IO_ERROR, (result as ConfigWriteResult.Rejected).reason)
        assertEquals(1L, repository.snapshot().revision)
        assertTrue(repository.snapshot().masterEnabled)
        assertEquals(ConfigStorageState.READY, repository.health().state)
    }

    @Test
    fun revisionExhaustionIsRejectedExplicitly() {
        val atMax = ConfigCodec.encodeConfig(ProtectionConfig(revision = ConfigConstants.MAX_REVISION))
        val store = FakeConfigStore(content = atMax)
        val repository = AppConfigRepository(store)
        assertEquals(ConfigConstants.MAX_REVISION, repository.snapshot().revision)

        val result = repository.write(ProtectionConfig(revision = ConfigConstants.MAX_REVISION), ConfigConstants.MAX_REVISION)

        assertTrue(result is ConfigWriteResult.Rejected)
        assertEquals(AppConfigRepository.REASON_REVISION_EXHAUSTED, (result as ConfigWriteResult.Rejected).reason)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun explicitSaveReplacesCorruptFile() {
        val store = FakeConfigStore(content = "not-json-at-all")
        val repository = AppConfigRepository(store)
        assertEquals(ConfigStorageState.RECOVERED_CORRUPT, repository.health().state)

        val result = repository.write(ProtectionConfig(masterEnabled = true), 0L)

        assertTrue(result is ConfigWriteResult.Saved)
        assertEquals(ConfigStorageState.READY, repository.health().state)
        val persisted = ConfigCodec.decodeConfig(store.lastWritten!!)
        assertTrue(persisted.masterEnabled)
    }

    @Test
    fun observeEmitsCurrentSnapshotAndCloseIsIdempotent() {
        val repository = AppConfigRepository(FakeConfigStore())
        val seen = mutableListOf<Long>()
        val subscription = repository.observe { config -> seen += config.revision }

        assertEquals(listOf(0L), seen)
        repository.write(ProtectionConfig(masterEnabled = true), 0L)
        assertEquals(listOf(0L, 1L), seen)

        subscription.close()
        subscription.close()
        repository.write(ProtectionConfig(masterEnabled = false), 1L)
        assertEquals(listOf(0L, 1L), seen)
    }

    @Test
    fun listenerExceptionDoesNotFailPersistedWrite() {
        val store = FakeConfigStore()
        val repository = AppConfigRepository(store)
        repository.observe { throw IllegalStateException("listener boom") }

        val result = repository.write(ProtectionConfig(masterEnabled = true), 0L)

        assertTrue(result is ConfigWriteResult.Saved)
        assertEquals(1, store.writeCount)
        assertEquals(1L, repository.snapshot().revision)
    }

    @Test
    fun packageSnapshotIsNotAffectedByLaterCallerMutation() {
        val store = FakeConfigStore()
        val repository = AppConfigRepository(store)
        val mutableSensors = mutableSetOf(1, 4)
        val config = ProtectionConfig(
            masterEnabled = true,
            packages = mapOf("com.example.app" to PackageConfig(blockedSensorTypes = mutableSensors))
        )
        repository.write(config, 0L)

        mutableSensors.add(99)

        assertEquals(setOf(1, 4), repository.snapshot().packages.getValue("com.example.app").blockedSensorTypes)
    }
}
