package com.antiads.accessibility

import com.antiads.accessibility.Fixtures.PKG
import com.antiads.accessibility.Fixtures.T0
import com.antiads.accessibility.Fixtures.WIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 执行前复核的无状态边界（窗口期、快照年龄、身份、敏感动作、文案）。 */
class ExecutionGuardsTest {

    // ---------- 目标选择与配置 ----------

    @Test
    fun disconnectedIsRefused() {
        val decision = ExecutionGuards.checkPolicy(Fixtures.policy(connected = false))
        assertEquals(GuardCode.NOT_CONNECTED, decision.reason)
    }

    @Test
    fun missingDependencyIsRefused() {
        val decision = ExecutionGuards.checkPolicy(Fixtures.policy(dependencyInstalled = false))
        assertEquals(GuardCode.DEPENDENCY_MISSING, decision.reason)
    }

    @Test
    fun rejectedPackageIsRefused() {
        val decision = ExecutionGuards.checkPolicy(Fixtures.policy(rejected = true))
        assertEquals(GuardCode.REJECTED_PACKAGE, decision.reason)
    }

    @Test
    fun invalidPackageNameIsRefused() {
        val decision = ExecutionGuards.checkPolicy(Fixtures.policy(valid = false))
        assertEquals(GuardCode.INVALID_PACKAGE, decision.reason)
    }

    @Test
    fun disabledTargetIsRefused() {
        val decision = ExecutionGuards.checkPolicy(Fixtures.policy(enabled = false))
        assertEquals(GuardCode.POLICY_DISABLED, decision.reason)
    }

    @Test
    fun missingBuiltinRuleIsRefused() {
        val decision = ExecutionGuards.checkPolicy(Fixtures.policy(ruleIdsContainBuiltin = false))
        assertEquals(GuardCode.RULE_NOT_ENABLED, decision.reason)
    }

    @Test
    fun revisionChangeBetweenSnapshotAndClickIsRefused() {
        val decision = ExecutionGuards.checkPolicy(
            Fixtures.policy(revisionAtSnapshot = 7L, revisionNow = 8L)
        )
        assertEquals(GuardCode.CONFIG_REVISION_CHANGED, decision.reason)
    }

    @Test
    fun matchingPolicyIsAllowed() {
        assertTrue(ExecutionGuards.checkPolicy(Fixtures.policy()).allowed)
    }

    // ---------- 窗口与时间边界 ----------

    @Test
    fun unknownWindowIdentityIsRefused() {
        val decision = ExecutionGuards.checkWindow(Fixtures.window(packageName = ""), nowElapsedMs = T0)
        assertEquals(GuardCode.WINDOW_IDENTITY_UNKNOWN, decision.reason)
    }

    @Test
    fun rootPackageMismatchIsRefused() {
        val decision = ExecutionGuards.checkWindow(Fixtures.window(rootPackageName = Fixtures.OTHER_PKG), nowElapsedMs = T0)
        assertEquals(GuardCode.WINDOW_CHANGED, decision.reason)
    }

    @Test
    fun unknownRootPackageIsRefused() {
        val decision = ExecutionGuards.checkWindow(Fixtures.window(rootPackageName = null), nowElapsedMs = T0)
        assertEquals(GuardCode.WINDOW_CHANGED, decision.reason)
    }

    @Test
    fun rootWindowMismatchIsRefused() {
        val decision = ExecutionGuards.checkWindow(Fixtures.window(rootWindowId = Fixtures.OTHER_WIN), nowElapsedMs = T0)
        assertEquals(GuardCode.WINDOW_CHANGED, decision.reason)
    }

    @Test
    fun nonApplicationWindowIsRefused() {
        val decision = ExecutionGuards.checkWindow(Fixtures.window(isApplicationWindow = false), nowElapsedMs = T0)
        assertEquals(GuardCode.NOT_APPLICATION_WINDOW, decision.reason)
    }

    @Test
    fun lockedKeyguardIsRefused() {
        val decision = ExecutionGuards.checkWindow(Fixtures.window(keyguardLocked = true), nowElapsedMs = T0)
        assertEquals(GuardCode.KEYGUARD_LOCKED, decision.reason)
    }

    @Test
    fun sensitivePackageIsRefused() {
        val decision = ExecutionGuards.checkWindow(Fixtures.window(sensitivePackage = true), nowElapsedMs = T0)
        assertEquals(GuardCode.SENSITIVE_PACKAGE, decision.reason)
    }

    @Test
    fun incompleteTraversalIsRefused() {
        val decision = ExecutionGuards.checkWindow(Fixtures.window(traversalComplete = false), nowElapsedMs = T0)
        assertEquals(GuardCode.TRAVERSAL_INCOMPLETE, decision.reason)
    }

    @Test
    fun degenerateScreenSizeIsRefused() {
        val decision = ExecutionGuards.checkWindow(Fixtures.window(screenWidthPx = 0), nowElapsedMs = T0)
        assertEquals(GuardCode.WINDOW_SIZE_INVALID, decision.reason)
    }

    @Test
    fun editableNodeAnywhereInWindowIsRefused() {
        val decision = ExecutionGuards.checkWindow(Fixtures.window(hasEditableOrPasswordNode = true), nowElapsedMs = T0)
        assertEquals(GuardCode.EDITABLE_NODE_IN_WINDOW, decision.reason)
    }

    @Test
    fun windowAgeAtBoundaryIsAllowed() {
        assertTrue(
            ExecutionGuards.checkWindow(
                Fixtures.window(
                    foregroundSinceElapsedMs = T0,
                    capturedAtElapsedMs = T0 + SkipLimits.WINDOW_MAX_AGE_MS
                ),
                nowElapsedMs = T0 + SkipLimits.WINDOW_MAX_AGE_MS
            ).allowed
        )
    }

    @Test
    fun windowAgeBeyondBoundaryIsRefused() {
        val decision = ExecutionGuards.checkWindow(
            Fixtures.window(
                foregroundSinceElapsedMs = T0,
                capturedAtElapsedMs = T0 + SkipLimits.WINDOW_MAX_AGE_MS + 1
            ),
            nowElapsedMs = T0 + SkipLimits.WINDOW_MAX_AGE_MS + 1
        )
        assertEquals(GuardCode.WINDOW_AGE_OUT_OF_RANGE, decision.reason)
    }

    @Test
    fun windowAgeBehindForegroundStartIsRefused() {
        val decision = ExecutionGuards.checkWindow(
            Fixtures.window(foregroundSinceElapsedMs = T0, capturedAtElapsedMs = T0 - 1),
            nowElapsedMs = T0
        )
        assertEquals(GuardCode.WINDOW_AGE_OUT_OF_RANGE, decision.reason)
    }

    @Test
    fun snapshotAgeAtBoundaryIsAllowed() {
        assertTrue(
            ExecutionGuards.checkWindow(
                Fixtures.window(capturedAtElapsedMs = T0),
                nowElapsedMs = T0 + SkipLimits.SNAPSHOT_MAX_AGE_MS
            ).allowed
        )
    }

    @Test
    fun staleSnapshotIsRefused() {
        val decision = ExecutionGuards.checkWindow(
            Fixtures.window(capturedAtElapsedMs = T0),
            nowElapsedMs = T0 + SkipLimits.SNAPSHOT_MAX_AGE_MS + 1
        )
        assertEquals(GuardCode.SNAPSHOT_STALE, decision.reason)
    }

    @Test
    fun clockRollbackIsRefused() {
        val decision = ExecutionGuards.checkWindow(
            Fixtures.window(capturedAtElapsedMs = T0),
            nowElapsedMs = T0 - 1
        )
        assertEquals(GuardCode.SNAPSHOT_STALE, decision.reason)
    }

    // ---------- 候选与节点 ----------

    @Test
    fun foreignRuleCandidateIsRefused() {
        val decision = ExecutionGuards.checkCandidate(Fixtures.candidate(ruleId = "custom.rule.v1"))
        assertEquals(GuardCode.RULE_NOT_BUILTIN, decision.reason)
    }

    @Test
    fun unexpectedReasonIsRefused() {
        val decision = ExecutionGuards.checkCandidate(Fixtures.candidate(reason = "MAYBE_AD"))
        assertEquals(GuardCode.REASON_NOT_EXPLICIT_AD_SKIP, decision.reason)
    }

    @Test
    fun editableCandidateIsRefused() {
        val decision = ExecutionGuards.checkCandidate(
            Fixtures.candidate(snapshotNode = Fixtures.node(editable = true), refreshed = Fixtures.node(editable = true))
        )
        assertEquals(GuardCode.NODE_EDITABLE, decision.reason)
    }

    @Test
    fun nonClickableCandidateIsRefused() {
        val decision = ExecutionGuards.checkCandidate(
            Fixtures.candidate(
                snapshotNode = Fixtures.node(clickable = false),
                refreshed = Fixtures.node(clickable = false)
            )
        )
        assertEquals(GuardCode.NODE_NOT_ACTIONABLE, decision.reason)
    }

    @Test
    fun failedRefreshIsRefused() {
        val decision = ExecutionGuards.checkCandidate(Fixtures.candidate(refreshed = null))
        assertEquals(GuardCode.REFRESH_FAILED, decision.reason)
    }

    @Test
    fun refreshTurnedEditableIsRefused() {
        val decision = ExecutionGuards.checkCandidate(
            Fixtures.candidate(refreshed = Fixtures.node(editable = true))
        )
        assertEquals(GuardCode.REFRESH_EDITABLE, decision.reason)
    }

    @Test
    fun refreshTurnedInvisibleIsRefused() {
        val decision = ExecutionGuards.checkCandidate(
            Fixtures.candidate(refreshed = Fixtures.node(visible = false))
        )
        assertEquals(GuardCode.REFRESH_NOT_ACTIONABLE, decision.reason)
    }

    @Test
    fun changedCountdownTextIsRefused() {
        val decision = ExecutionGuards.checkCandidate(
            Fixtures.candidate(
                snapshotNode = Fixtures.node(text = "跳过 5 秒"),
                refreshed = Fixtures.node(text = "跳过 4 秒")
            )
        )
        assertEquals(GuardCode.NODE_TEXT_CHANGED, decision.reason)
    }

    @Test
    fun whitespaceAndCaseNormalizationKeepsCandidateAllowed() {
        assertTrue(
            ExecutionGuards.checkCandidate(
                Fixtures.candidate(
                    snapshotNode = Fixtures.node(text = "  Skip Ads  "),
                    refreshed = Fixtures.node(text = "skip ads")
                )
            ).allowed
        )
    }

    @Test
    fun sensitiveActionTextIsRefusedEvenWhenUnchanged() {
        val decision = ExecutionGuards.checkCandidate(
            Fixtures.candidate(
                snapshotNode = Fixtures.node(text = "允许并继续"),
                refreshed = Fixtures.node(text = "允许并继续")
            )
        )
        assertEquals(GuardCode.SENSITIVE_ACTION, decision.reason)
    }

    @Test
    fun sensitiveActionTermsAndSafeSkipTerms() {
        assertTrue(SensitiveActionText.denies("立即购买"))
        assertTrue(SensitiveActionText.denies("登录后继续"))
        assertTrue(SensitiveActionText.denies("grant permission"))
        assertTrue(SensitiveActionText.denies("allow"))
        assertFalse(SensitiveActionText.denies("跳过"))
        assertFalse(SensitiveActionText.denies("skip ads"))
        assertFalse(SensitiveActionText.denies(""))
    }

    @Test
    fun nodeFieldsActionableRequiresAllThreeFlags() {
        assertTrue(Fixtures.node().isActionable)
        assertFalse(Fixtures.node(clickable = false).isActionable)
        assertFalse(Fixtures.node(enabled = false).isActionable)
        assertFalse(Fixtures.node(visible = false).isActionable)
    }

    @Test
    fun snapshotTextFieldIsLimitedTo256Chars() {
        val long = "跳".repeat(300)
        assertEquals(SkipLimits.MAX_FIELD_CHARS, NodeText.limit(long).length)
        assertEquals("跳过", NodeText.limit("跳过"))
        assertEquals("跳过", NodeText.normalize("  跳过  "))
    }
}
