package com.antiads.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Provider 非 call 入口策略：不返回数据、拒绝一切外部变更。 */
class ProviderEntryPolicyTest {

    @Test
    fun readEntryPointsReturnNullAndAllMutationsThrow() {
        assertEquals(ProviderEntryDecision.RETURN_NULL, ProviderEntryPolicy.decide(ProviderEntryPoint.QUERY))
        assertEquals(ProviderEntryDecision.RETURN_NULL, ProviderEntryPolicy.decide(ProviderEntryPoint.GET_TYPE))

        val throwing = listOf(
            ProviderEntryPoint.INSERT,
            ProviderEntryPoint.UPDATE,
            ProviderEntryPoint.DELETE,
            ProviderEntryPoint.BULK_INSERT,
            ProviderEntryPoint.OPEN_FILE,
            ProviderEntryPoint.OPEN_ASSET_FILE
        )
        for (entryPoint in throwing) {
            assertEquals(entryPoint.name, ProviderEntryDecision.THROW_UNSUPPORTED, ProviderEntryPolicy.decide(entryPoint))
        }
        assertEquals(
            setOf(ProviderEntryPoint.QUERY, ProviderEntryPoint.GET_TYPE),
            ProviderEntryPolicy.dataReturningEntryPoints()
        )
    }

    @Test
    fun callMethodPolicyOnlyAllowsTwoReadOnlyMethods() {
        assertTrue(ProviderMethodPolicy.isAllowed("get_policy_v1"))
        assertTrue(ProviderMethodPolicy.isAllowed("report_hook_v1"))

        for (method in ProviderMethodPolicy.FORBIDDEN) {
            assertFalse(method, ProviderMethodPolicy.isAllowed(method))
        }
        assertFalse(ProviderMethodPolicy.isAllowed(null))
        assertFalse(ProviderMethodPolicy.isAllowed(""))
        assertFalse(ProviderMethodPolicy.isAllowed("get_all_config"))
    }
}
