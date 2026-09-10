package com.joetr.andy.mobile.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileNavKeyTest {
    @Test
    fun sessionDependentRoutes() {
        assertTrue(MobileNavKey.NewChat().isSessionDependent())
        assertTrue(MobileNavKey.Chat("abc").isSessionDependent())
        assertFalse(MobileNavKey.Tabs().isSessionDependent())
        assertFalse(MobileNavKey.EditHost(null).isSessionDependent())
        assertFalse(MobileNavKey.EditHost("h1").isSessionDependent())
    }

    @Test
    fun popSessionRoutesLeavesTabsAndEditHost() {
        val stack = mutableListOf<MobileNavKey>(
            MobileNavKey.Tabs(MobileTab.Projects),
            MobileNavKey.EditHost("h1"),
            MobileNavKey.NewChat(),
            MobileNavKey.Chat("c1"),
        )
        while (stack.lastOrNull()?.isSessionDependent() == true) {
            stack.removeLast()
        }
        assertTrue(stack.last() is MobileNavKey.EditHost)
        assertTrue(stack.first() is MobileNavKey.Tabs)
    }

    @Test
    fun replaceNewChatWithChat() {
        val stack = mutableListOf<MobileNavKey>(
            MobileNavKey.Tabs(MobileTab.Projects),
            MobileNavKey.NewChat(),
        )
        stack.removeLast()
        stack.add(MobileNavKey.Chat("started"))
        assertTrue(stack.last() is MobileNavKey.Chat)
        assertFalse(stack.any { it is MobileNavKey.NewChat })
    }
}
