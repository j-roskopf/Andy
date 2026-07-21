package app.andy.model

enum class ProjectTaskKind(val label: String) {
    Spec("spec"),
    Build("build"),
    Review("review"),
    Verification("verification"),
}

enum class ProjectTaskState {
    Draft,
    Queued,
    Running,
    Waiting,
    Paused,
    Disabled,
    NeedsAttention,
    Failed,
    Completed,
    Cancelled,
}

enum class ProjectWorkflowStage {
    Spec,
    Build,
    Review,
    Verification,
}

enum class ProjectReviewStatus {
    Approved,
    ChangesRequested,
}

enum class ProjectReviewFindingSeverity {
    Blocking,
    Warning,
    Nit,
}

enum class ProjectVerificationStatus {
    Passed,
    Failed,
}

data class ProjectAgentProfile(
    val agent: AgentKind = AgentKind.Codex,
    val model: String? = null,
    val reasoningEffort: AgentReasoningEffort? = null,
    val fastMode: Boolean = false,
    val autonomy: AgentAutonomy = AgentAutonomy.Standard,
    val sandboxMode: AgentSandboxMode? = null,
    val useWorktree: Boolean = false,
    val attachAndyMcp: Boolean = false,
    val maxBudgetUsd: Double? = null,
)

data class ProjectPlanVersion(
    val version: Int,
    val text: String,
    val runId: String,
    val createdAtMillis: Long,
)

data class ProjectPlanSnapshot(
    val text: String,
    val sourceSpecTaskId: String? = null,
    val sourceVersion: Int? = null,
    val sourceLabel: String = "external plan",
)

data class ProjectVerificationVerdict(
    val status: ProjectVerificationStatus,
    val summary: String,
    val evidence: List<String> = emptyList(),
    val failures: List<String> = emptyList(),
    val runId: String,
    val createdAtMillis: Long,
    val reviewedBuildRunId: String? = null,
    val reviewGeneration: Int = 0,
)

data class ProjectReviewFinding(
    val severity: ProjectReviewFindingSeverity,
    val title: String,
    val details: String,
    val file: String? = null,
    val line: Int? = null,
)

data class ProjectReviewVerdict(
    val status: ProjectReviewStatus,
    val summary: String,
    val findings: List<ProjectReviewFinding> = emptyList(),
    val runId: String,
    val reviewedBuildRunId: String,
    val reviewGeneration: Int,
    val createdAtMillis: Long,
)

data class ProjectTaskAttempt(
    val runId: String,
    val stage: ProjectWorkflowStage,
    val attempt: Int,
    val prompt: String,
    val profile: ProjectAgentProfile,
    val scratchpadSnapshot: String? = null,
    val createdAtMillis: Long,
    val reviewedBuildRunId: String? = null,
    val reviewGeneration: Int = 0,
    /** A user-directed fix thread added after the original workflow completed. */
    val isRecoveryFollowUp: Boolean = false,
)

data class ProjectTask(
    val id: String,
    val projectId: String,
    val kind: ProjectTaskKind,
    val title: String,
    val instructions: String,
    val profile: ProjectAgentProfile,
    val includeScratchpad: Boolean,
    /** Local images attached to a Spec brief (or other task instructions). */
    val imagePaths: List<String> = emptyList(),
    val state: ProjectTaskState = ProjectTaskState.Draft,
    val linkedSpecTaskId: String? = null,
    val linkedBuildTaskId: String? = null,
    val linkedReviewTaskId: String? = null,
    val linkedVerificationTaskId: String? = null,
    val planVersions: List<ProjectPlanVersion> = emptyList(),
    val planSnapshot: ProjectPlanSnapshot? = null,
    val grillMeEnabled: Boolean = false,
    val buildNotes: String = "",
    val reviewEnabled: Boolean = false,
    val reviewInstructions: String = "",
    val reviewGeneration: Int = 0,
    val maxReviewFailures: Int = 5,
    val reviewReopenedCompleted: Boolean = false,
    /** True while a completed workflow is collecting manually tested fix threads. */
    val recoveryMode: Boolean = false,
    /** The latest approval no longer covers the workspace after a recovery follow-up. */
    val reviewStale: Boolean = false,
    val verificationInstructions: String = "",
    val maxVerificationAttempts: Int = 5,
    val maxBudgetUsd: Double? = null,
    val paused: Boolean = false,
    val workspacePath: String? = null,
    val worktreePath: String? = null,
    val branchName: String? = null,
    val worktreeOwnerRunId: String? = null,
    val attempts: List<ProjectTaskAttempt> = emptyList(),
    val reviewVerdicts: List<ProjectReviewVerdict> = emptyList(),
    val verdicts: List<ProjectVerificationVerdict> = emptyList(),
    val lastError: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    val isActive: Boolean get() = state == ProjectTaskState.Queued || state == ProjectTaskState.Running || state == ProjectTaskState.Waiting
}

data class ProjectWorkflowState(
    val projectId: String,
    val scratchpad: String = "",
    val profiles: Map<ProjectTaskKind, ProjectAgentProfile> = emptyMap(),
    val tasks: List<ProjectTask> = emptyList(),
    val legacyNotesMigrated: Boolean = false,
)

data class ProjectSpecDraft(
    val projectId: String,
    val title: String,
    val brief: String,
    val profile: ProjectAgentProfile,
    val includeScratchpad: Boolean = false,
    val grillMeEnabled: Boolean = false,
    val taskId: String? = null,
    val imagePaths: List<String> = emptyList(),
)

data class ProjectBuildPairDraft(
    val projectId: String,
    val title: String,
    val plan: ProjectPlanSnapshot,
    val buildNotes: String,
    val verificationInstructions: String,
    val buildProfile: ProjectAgentProfile,
    val verificationProfile: ProjectAgentProfile,
    val includeScratchpadInBuild: Boolean = false,
    val includeScratchpadInVerification: Boolean = false,
    val maxBudgetUsd: Double? = null,
    val buildTaskId: String? = null,
    val reviewEnabled: Boolean = false,
    val reviewInstructions: String = "",
    val reviewProfile: ProjectAgentProfile = ProjectAgentProfile(),
    val includeScratchpadInReview: Boolean = false,
)

fun AgentProviderDefaults.toProjectProfile(agent: AgentKind): ProjectAgentProfile = ProjectAgentProfile(
    agent = agent,
    model = model,
    reasoningEffort = reasoningEffort,
    fastMode = fastMode,
    autonomy = autonomy,
    sandboxMode = sandboxMode,
    useWorktree = useWorktree,
    attachAndyMcp = attachAndyMcp,
    maxBudgetUsd = maxBudgetUsd,
)
