package com.zhousl.aether

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundWorkPolicyTest {
    @Test fun `disabled task background setting does not stop market work`() {
        assertTrue(shouldKeepAetherForeground(1, false, true))
        assertFalse(shouldKeepAetherForeground(1, false, false))
    }

    @Test fun `service stops only after every foreground reason is gone`() {
        assertTrue(shouldKeepAetherForeground(1, true, false))
        assertTrue(shouldKeepAetherForeground(0, true, true))
        assertFalse(shouldKeepAetherForeground(0, true, false))
    }
}
