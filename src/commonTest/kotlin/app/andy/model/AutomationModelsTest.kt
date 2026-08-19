package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutomationModelsTest {
    @Test
    fun parsesLastAndyStopTag() {
        val text = """
            Still working on the PR.
            ANDY_STOP=NO
            Actually the PR is ready.
            ANDY_STOP=YES
        """.trimIndent()
        assertEquals(true, parseAndyStopTag(text))
        assertEquals(false, parseAndyStopTag("notes\nANDY_STOP=NO"))
        assertNull(parseAndyStopTag("no tag here"))
    }

    @Test
    fun consecutiveFailuresPauseAndSuccessResetsStreak() {
        val base = sampleAutomation()
        val failed = applyAutomationWorkOutcome(base, workFailed = true, stopWhenYes = false)
        assertEquals(1, failed.consecutiveFailures)
        assertEquals(1, failed.fireCount)
        assertEquals(false, failed.paused)

        val second = applyAutomationWorkOutcome(
            base.copy(consecutiveFailures = 1, fireCount = 1),
            workFailed = true,
            stopWhenYes = false,
        )
        assertEquals(2, second.consecutiveFailures)

        val third = applyAutomationWorkOutcome(
            base.copy(consecutiveFailures = 2, fireCount = 2),
            workFailed = true,
            stopWhenYes = false,
        )
        assertTrue(third.paused)
        assertEquals("Stopped after 3 consecutive failures", third.pauseReason)

        val recovered = applyAutomationWorkOutcome(
            base.copy(consecutiveFailures = 2, fireCount = 2),
            workFailed = false,
            stopWhenYes = false,
        )
        assertEquals(0, recovered.consecutiveFailures)
        assertEquals(false, recovered.paused)
    }

    @Test
    fun stopWhenYesPausesWithoutCountingFailure() {
        val result = applyAutomationWorkOutcome(sampleAutomation(), workFailed = false, stopWhenYes = true)
        assertTrue(result.paused)
        assertEquals("Stop condition met", result.pauseReason)
        assertEquals(0, result.consecutiveFailures)
        assertEquals(1, result.fireCount)
    }

    @Test
    fun maxIterationsCountsScheduleFires() {
        val automation = sampleAutomation().copy(maxIterations = AutomationMaxIterations.Runs10, fireCount = 9)
        val result = applyAutomationWorkOutcome(automation, workFailed = false, stopWhenYes = false)
        assertTrue(result.paused)
        assertEquals("Reached 10 runs", result.pauseReason)
    }

    @Test
    fun keepRunningDoesNotPauseOnFailures() {
        val automation = sampleAutomation().copy(
            failurePolicy = AutomationFailurePolicy.KeepRunning,
            consecutiveFailures = 40,
        )
        val result = applyAutomationWorkOutcome(automation, workFailed = true, stopWhenYes = false)
        assertEquals(false, result.paused)
        assertEquals(41, result.consecutiveFailures)
    }

    @Test
    fun stopAfterOneFailurePausesImmediately() {
        val result = applyAutomationWorkOutcome(
            sampleAutomation().copy(failurePolicy = AutomationFailurePolicy.StopAfter1),
            workFailed = true,
            stopWhenYes = false,
        )
        assertTrue(result.paused)
        assertEquals("Stopped after 1 consecutive failure", result.pauseReason)
    }

    @Test
    fun evaluatorPromptAsksForAndyStopTag() {
        val prompt = evaluatorStopWhenPrompt("the PR is merged")
        assertTrue("the PR is merged" in prompt)
        assertTrue("ANDY_STOP=YES" in prompt)
        assertTrue("ANDY_STOP=NO" in prompt)
    }

    @Test
    fun templatesAutofillAndyNativePrompts() {
        assertEquals(3, AutomationTemplates.size)
        assertTrue(AutomationTemplates.any { it.title == "Triage new crashes" && "list_crashes" in it.prompt })
        assertTrue(AutomationTemplates.any { it.title == "Update dependencies" })
        assertTrue(AutomationTemplates.any { it.title == "Daily standup summary" && "Kanban" in it.prompt })
    }

    @Test
    fun resolvesUsTimezoneAliases() {
        assertEquals("America/Chicago", resolveAutomationTimeZoneId("Central"))
        assertEquals("America/Chicago", resolveAutomationTimeZoneId("US/Central"))
        assertEquals("America/New_York", resolveAutomationTimeZoneId("eastern"))
        assertEquals("UTC", resolveAutomationTimeZoneId("  "))
        assertEquals("America/Chicago", resolveAutomationTimeZoneId("America/Chicago"))
    }

    @Test
    fun clockTwelveHourRoundTrip() {
        assertEquals(0, clockToHour24(12, isPm = false))
        assertEquals(12, clockToHour24(12, isPm = true))
        assertEquals(15, clockToHour24(3, isPm = true))
        assertEquals(3, clockToHour24(3, isPm = false))
        assertEquals(3 to true, hour24ToClock(15))
        assertEquals(12 to false, hour24ToClock(0))
        assertEquals(12 to true, hour24ToClock(12))
    }

    @Test
    fun onceCadenceIncludesTime() {
        assertEquals(
            "Once at 15:30",
            sampleAutomation().copy(
                schedule = AutomationSchedule.Once(1L),
                runHour = 15,
                runMinute = 30,
            ).cadenceLabel(),
        )
    }

    private fun sampleAutomation() = Automation(
        id = "auto-1",
        projectId = "garden",
        title = "Triage",
        prompt = "look",
        timeZone = "UTC",
        launch = AutomationLaunchSnapshot(agent = AgentKind.Codex.name),
        createdAtMillis = 1,
        updatedAtMillis = 1,
    )
}
