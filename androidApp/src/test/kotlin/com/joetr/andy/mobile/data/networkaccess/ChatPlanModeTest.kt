package com.joetr.andy.mobile.data.networkaccess

import com.joetr.andy.mobile.data.networkaccess.ChatDto
import com.joetr.andy.mobile.data.networkaccess.ChatEventDto
import com.joetr.andy.mobile.data.networkaccess.PlanEntryDto
import com.joetr.andy.mobile.data.networkaccess.awaitingPlanConfirmation
import com.joetr.andy.mobile.data.networkaccess.displayStatusLabel
import com.joetr.andy.mobile.data.networkaccess.hasListAttentionMarker
import com.joetr.andy.mobile.data.networkaccess.latestPlanHasPendingEntries
import com.joetr.andy.mobile.data.networkaccess.showImplementPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPlanModeTest {
    @Test
    fun donePlanModeShowsPlanReady() {
        val chat = ChatDto(id = "1", status = "Done", planMode = true)
        assertTrue(chat.awaitingPlanConfirmation())
        assertTrue(chat.showImplementPlan())
        assertEquals("plan ready", chat.displayStatusLabel())
    }

    @Test
    fun pendingPlanEntriesShowPlanReadyWithoutPlanMode() {
        val chat = ChatDto(id = "1", status = "Done", planMode = false)
        val events = listOf(
            ChatEventDto(
                type = "plan",
                entries = listOf(
                    PlanEntryDto("Switch RemoteDisplay mapping", "pending"),
                    PlanEntryDto("Rewrite handleViewerGesture", "pending"),
                ),
            ),
        )
        assertTrue(events.latestPlanHasPendingEntries())
        assertTrue(chat.awaitingPlanConfirmation(hasPendingPlanEntries = true))
        assertEquals("plan ready", chat.displayStatusLabel(hasPendingPlanEntries = true))
    }

    @Test
    fun specStageHidesImplement() {
        val chat = ChatDto(
            id = "1",
            status = "Done",
            planMode = true,
            workflowStage = "Spec",
        )
        assertTrue(chat.awaitingPlanConfirmation())
        assertFalse(chat.showImplementPlan())
    }

    @Test
    fun workingPlanModeIsNotAwaiting() {
        val chat = ChatDto(id = "1", status = "Working", planMode = true)
        assertFalse(chat.awaitingPlanConfirmation())
        assertEquals("Working", chat.displayStatusLabel())
    }

    @Test
    fun listAttentionOnlyForInterestingStates() {
        assertFalse(ChatDto(id = "1", status = "Done").hasListAttentionMarker())
        assertFalse(ChatDto(id = "1", status = "Working").hasListAttentionMarker())
        assertTrue(ChatDto(id = "1", status = "Blocked").hasListAttentionMarker())
        assertTrue(ChatDto(id = "1", status = "Error").hasListAttentionMarker())
        assertTrue(ChatDto(id = "1", status = "Done", planMode = true).hasListAttentionMarker())
    }
}
