package app.andy.domain

import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.model.AgentUserInputRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentChatEligibilityTest {
    private fun task(
        id: String,
        projectId: String? = null,
        status: AgentStatus? = AgentStatus.Done,
        archived: Boolean = false,
        startedAtMillis: Long? = 1,
        finishedAtMillis: Long? = null,
        createdAtMillis: Long = 1,
        userInputRequest: AgentUserInputRequest? = null,
    ) = AgentTask(
        id = id,
        title = "chat $id",
        prompt = "p",
        agent = AgentKind.Codex,
        projectId = projectId,
        status = status,
        archived = archived,
        startedAtMillis = startedAtMillis,
        finishedAtMillis = finishedAtMillis,
        createdAtMillis = createdAtMillis,
        userInputRequest = userInputRequest,
    )

    @Test
    fun offersChatsFromTheSameProjectAndTheInbox() {
        val sameProject = task("same-project", projectId = "proj-1")
        val inbox = task("inbox-chat", projectId = null)
        val otherProject = task("other-project", projectId = "proj-2")

        val eligible = eligibleAgentChatsForContext(
            tasks = listOf(sameProject, inbox, otherProject),
            projectId = "proj-1",
        )

        assertEquals(setOf("same-project", "inbox-chat"), eligible.map { it.taskId }.toSet())
    }

    @Test
    fun excludesArchivedAndNotYetLaunchedChats() {
        val archived = task("archived-chat", archived = true)
        val queued = task("queued-chat", status = null, startedAtMillis = null)
        val eligibleChat = task("eligible-chat")

        val eligible = eligibleAgentChatsForContext(
            tasks = listOf(archived, queued, eligibleChat),
            projectId = null,
        )

        assertEquals(listOf("eligible-chat"), eligible.map { it.taskId })
    }

    @Test
    fun sortsActiveChatsBeforeFinishedChatsThenByMostRecentActivity() {
        val oldFinished = task("old-finished", status = AgentStatus.Done, finishedAtMillis = 100)
        val newFinished = task("new-finished", status = AgentStatus.Done, finishedAtMillis = 300)
        val active = task("active-chat", status = AgentStatus.Working, finishedAtMillis = null, startedAtMillis = 50)

        val eligible = eligibleAgentChatsForContext(
            tasks = listOf(oldFinished, newFinished, active),
            projectId = null,
        )

        assertEquals(listOf("active-chat", "new-finished", "old-finished"), eligible.map { it.taskId })
    }

    @Test
    fun recommendsQueueFollowUpForAnActiveChatWithoutAPendingQuestion() {
        val working = task("working-chat", status = AgentStatus.Working)
        val eligibility = agentChatEligibility(working)
        assertEquals(AgentChatAttachAction.QueueFollowUp, eligibility.action)
        assertNull(eligibility.blockedReason)
    }

    @Test
    fun recommendsResumeForAFinishedChat() {
        val done = task("done-chat", status = AgentStatus.Done)
        val eligibility = agentChatEligibility(done)
        assertEquals(AgentChatAttachAction.Resume, eligibility.action)
        assertNull(eligibility.blockedReason)
    }

    @Test
    fun recommendsResumeForAFailedChat() {
        val errored = task("errored-chat", status = AgentStatus.Error)
        val eligibility = agentChatEligibility(errored)
        assertEquals(AgentChatAttachAction.Resume, eligibility.action)
    }

    @Test
    fun blocksAChatWaitingOnADecisionCheckpoint() {
        val waiting = task(
            "blocked-chat",
            status = AgentStatus.Blocked,
            userInputRequest = AgentUserInputRequest(id = "req-1", questions = emptyList()),
        )
        val eligibility = agentChatEligibility(waiting)
        assertEquals(AgentChatAttachAction.Blocked, eligibility.action)
        assertNotNull(eligibility.blockedReason)
        assertTrue(eligibility.blockedReason!!.isNotBlank())
    }

    @Test
    fun preservesTaskIdentityFieldsInTheEligibilityResult() {
        val chat = task("identity-chat", projectId = "proj-9", status = AgentStatus.Working)
        val eligibility = agentChatEligibility(chat)
        assertEquals("identity-chat", eligibility.taskId)
        assertEquals("chat identity-chat", eligibility.title)
        assertEquals("proj-9", eligibility.projectId)
    }
}
