package app.andy.desktop.service.agents

internal val ANDY_ORCHESTRATION_SKILL: String =
    """
    ---
    name: andy-orchestration
    description: Reference for orchestrating other Andy agents via chat.* MCP tools and the andy CLI. Read by andy-handoff, andy-loop, andy-advisor, andy-committee before they do anything.
    user-invocable: false
    ---

    # Andy Orchestration Reference

    Andy is both the app you're running inside and an MCP server. If this task has
    `attachAndyMcp` on, you have direct MCP tool access to `chat.*` tools. If not,
    or if your provider doesn't expose MCP tools directly, use the `andy` CLI —
    it hits the same tools over `~/.andy/andyd.sock`.

    If this task does not already have `attachAndyMcp` on, none of the orchestration
    tools below are reachable — say so plainly and stop. Restart as a new task with
    one of the andy-orchestration skills selected from the new-task composer (that
    auto-attaches Andy MCP).

    ## Starting an agent

    MCP: `chat.start` — required `prompt`, `agent` (one of ClaudeCode, Codex, Cursor,
    Antigravity, OpenCode, Pi, Hermes, OpenClaw, Goose, Ollama, LMStudio). Optional: `title`, `projectId`,
    `directory`, `model`, `autonomy` (ReadOnly | Standard | Full), `callerTaskId`,
    `useWorktree`, `existingWorktreePath`. For Ollama and LM Studio, `runtime`
    (OpenCode | Pi | Goose) and `model` are required.

    **Autonomy inheritance:** when `autonomy` is omitted, Andy inherits the parent's
    dial from `callerTaskId` or the MCP session's `andyTaskId` (wired automatically
    when this task has Andy MCP attached). The `andy` CLI also injects
    `ANDY_TASK_ID` as `callerTaskId`. So a Full-permission orchestrator spawns
    Full workers without re-prompting — unless you override (e.g. verifiers must
    set `autonomy: "ReadOnly"`). If no parent is known, default is Standard.

    CLI: `andy chat start --agent <Kind> [--title <t>] [--directory <path>] "<prompt>"`
    for the common case. For `autonomy`, `model`, or worktree params, use the escape
    hatch: `andy tool call chat.start --json-args '{"prompt":"...","agent":"Codex","autonomy":"ReadOnly"}'`.

    ## Checking status / waiting

    There is no push notification back into your own session when a spawned task
    finishes — Andy has no async callback into an arbitrary running agent process.
    Poll instead: `andy chat status <task_id>` (or MCP `chat.status`), on a sleep
    loop you run yourself (e.g. `while ...; do sleep 15; andy chat status ${'$'}ID; done`
    from your own shell tool). Don't hammer it — 10-30s between checks is plenty;
    these runs take minutes.

    ## Following up / resuming

    MCP `chat.resume` or CLI `andy chat resume <task_id> "<follow_up>"`.

    ## Picking a provider

    1. User named one explicitly in this request → use it.
    2. Otherwise read `~/.andy/orchestration-preferences.json` (`providers` map:
       `impl`, `ui`, `research`, `planning`, `audit` → AgentKind name). Configure the
       same file in Andy Settings → Agents → Orchestration. If missing, use
       `impl=Codex, ui=ClaudeCode, research=ClaudeCode, planning=Codex, audit=Codex`
       and say once that you're using defaults.
    3. Before using a mapped provider, confirm it's actually usable: MCP
       `chat.composer_options` (or `andy tool call chat.composer_options`) returns
       `ready`/`available` per agent. If the preferred provider isn't ready, pick
       another ready one and say so. If a preferences value is not a valid AgentKind
       name, treat it as missing and fall back to the defaults above.
    4. If nothing is ready, say so and stop — do not call `chat.start` blind.

    Example preferences file:

    ```json
    {
      "providers": {
        "impl": "Codex",
        "ui": "ClaudeCode",
        "research": "ClaudeCode",
        "planning": "Codex",
        "audit": "Codex"
      },
      "preferences": [
        "Claude is the right choice for anything artistic or human-skill-oriented: copywriting, naming, UX copy, visual design, styling. Codex is the workhorse for mechanical work."
      ]
    }
    ```

    Weave any `preferences` strings into spawned agent prompts contextually.

    Optional per-role launch settings live in the `settings` map. For example:

    ```json
    "settings": {
      "impl": {"model": "gpt-5.6-sol", "autonomy": "Full"},
      "audit": {"model": "sonnet", "autonomy": "ReadOnly"}
    }
    ```

    Pass a configured `model` to `chat.start`. Pass `autonomy` only when that role
    has an explicit value; when it is unset, omit it so Andy inherits the parent
    task's permission dial. Andy also inherits the caller's `projectId` and working
    directory when those `chat.start` fields are omitted, so child chats stay under
    the project that launched the orchestration.

    ## No-edits suffix

    For any agent that should only analyze, not change anything, append to its
    prompt:

        This is analysis only. Do NOT edit, create, or delete any files. Do NOT write code.

    ...and additionally set `autonomy: "ReadOnly"` on `chat.start` — Andy enforces
    this at the tool level, not just the prompt.
    """.trimIndent() + "\n"

internal val ANDY_HANDOFF_SKILL: String =
    """
    ---
    name: andy-handoff
    description: Hand off the current task to another agent with full context. Use when the user says "handoff", "hand off", "hand this to", or wants to pass work to another agent.
    user-invocable: true
    argument-hint: "[--provider <name>] [--worktree] <task description>"
    ---

    # Andy Handoff

    Transfer the current task — context, decisions, failed attempts, constraints — to a fresh agent. The receiving agent starts with **zero context**, so the handoff prompt must be a self-contained briefing.

    **User's arguments:** ${'$'}ARGUMENTS

    ## Prerequisites

    Read the **andy-orchestration** skill first. Before choosing a provider, read
    `~/.andy/orchestration-preferences.json` unless the user explicitly named a
    provider in this request. Do not create the receiving agent until you have
    resolved the provider (and confirmed it is ready via `chat.composer_options`).

    If this task does not already have `attachAndyMcp` on, none of the orchestration
    tools below are reachable — say so plainly and stop. Restart as a new task with
    the andy-orchestration skill's MCP attach requirement in mind.

    ## Parsing arguments

    1. **Provider** — explicit user request first; otherwise resolve from `impl`
       preference (or `ui` if the task is styling-only).
    2. **Isolation** — "in a worktree" / "worktree" → pass `useWorktree: true` on
       `chat.start`. To reuse an existing worktree path, pass `existingWorktreePath`.
       There is no separate `create_workspace` step — worktree isolation is a
       `chat.start` param.
    3. **Task description** — anything else the user said.

    Use the `impl` role's `settings` entry for the worker (or `ui` for a styling-only
    handoff) unless the user explicitly chose another provider. Pass its configured
    model and autonomy when present; otherwise omit autonomy to inherit the current
    task's permission dial.

    ## The handoff prompt

    The receiving agent has zero context. Include:

    ```
    ## Task
    [Imperative description.]

    ## Context
    [Why this task exists, required context.]

    ## Relevant files
    - `path/to/file.ts` — [what it is and why it matters]

    ## Current state
    [What's done, what works, what doesn't.]

    ## What was tried
    - [Approach] — [why it failed or was abandoned]

    ## Decisions
    - [Decision — rationale]

    ## Acceptance criteria
    - [ ] [Criterion]

    ## Constraints
    - [Must-not / must-preserve]
    ```

    **Preserve task semantics.** Investigate-only → "DO NOT edit files." Fix →
    "implement the fix." Refactor → "refactor, not rewrite." Carry the user's
    exact intent.

    ## Launch

    1. Resolve provider + readiness (andy-orchestration).
    2. Call `chat.start` with:
       - `agent`: resolved AgentKind
       - `title`: `[Handoff] ` + short summary
       - `prompt`: the full briefing
       - omit `autonomy` so the receiver inherits this task's dial (Full stays Full),
         unless the handoff is investigate-only — then `autonomy: "ReadOnly"` +
         no-edits suffix
       - `useWorktree: true` when worktree isolation was requested
       - `directory` / `projectId` from the current task context when available;
         Andy also fills these from the caller when they are omitted
       - `existingWorktreePath` only when reusing a known worktree
    3. Tell the user the new task id and how to follow along:
       `andy chat status <id>`, or open it in Andy. There is no finish callback —
       do not promise one. Don't wait by default; the user decides whether to
       follow along or move on.

    If `chat.start` fails (for example, worktree requested without a project
    directory), relay the error — do not retry blindly.
    """.trimIndent() + "\n"

internal val ANDY_LOOP_SKILL: String =
    """
    ---
    name: andy-loop
    description: Run an agent loop until an exit condition is met, worker/verifier cycle. Use for "loop", "babysit", "keep trying until", "watch this until X".
    user-invocable: true
    argument-hint: "[--provider <name>] [--verify-provider <name>] [--max-iterations N] [--max-time <dur>] <goal>"
    ---

    # Andy Loop

    Read the **andy-orchestration** skill first.

    Andy has no daemon-side loop primitive (unlike some other orchestrators) — you
    are the loop. Each iteration you personally: start or resume a worker, wait for
    it, check verification, decide whether to continue.

    **User's arguments:** ${'$'}ARGUMENTS

    If this task does not already have `attachAndyMcp` on, none of the orchestration
    tools below are reachable — say so plainly and stop. Restart as a new task with
    the andy-orchestration skill's MCP attach requirement in mind.

    ## Before you start

    1. Parse **worker prompt** (self-contained, concrete about what counts as
       progress this iteration), **verify shape** (a shell command you can run
       yourself and check the exit code of, and/or a second agent whose job is to
       judge and report `done=true/false` with cited evidence), **providers**
       (worker + verifier from preferences, different families when both are used),
       **stop conditions**: max-iterations (default 10 if the user didn't say) and/or
       max-time (default 45 minutes if the user didn't say). **Always set both** —
       an open-ended loop is how runaways happen, and unlike a daemon-managed loop,
       nothing outside your own context will stop you if you don't.

    2. Track iteration count and elapsed time yourself in your own scratch notes
       (there's no persisted loop state — if your session ends, the loop ends).

    3. Resolve worker/verifier providers and their optional `settings` entries from
       `~/.andy/orchestration-preferences.json` (`impl` for worker, `audit` or a
       contrasting family for verifier) unless the user named them. Confirm readiness
       via `chat.composer_options`.

    ## Each iteration

    1. Start the worker (`chat.start` first iteration, `chat.resume` after) with a
       concrete, self-contained instruction for this iteration. Do **not** pass
       `autonomy: "ReadOnly"` or `autonomy: "Standard"` for the worker — omit
       `autonomy` so Andy inherits this loop task's dial (Full stays Full), unless the
       `impl` role has an explicit configured autonomy. Pass the configured `impl`
       model when present. Only override the configured role when the user asked for
       a different worker.
    2. Wait (poll `chat.status`, see andy-orchestration).
    3. Verify: run the shell check yourself and/or spawn/resume a verifier agent
       with `autonomy: "ReadOnly"` and the no-edits suffix, asking it to cite the
       command and outcome, not suggest fixes.
    4. If verified done → stop, report the outcome and the worker's task id.
    5. If not done and under both limits → next iteration with a worker prompt that
       includes what failed and what to try differently.
    6. If either limit is hit → stop, report where it left off and why, don't keep going.

    ## Prompt rules

    **Worker** — self-contained, concrete (commands, files, branches, tests, PRs,
    systems), explicit about what counts as progress this iteration. Same
    permissions as this parent task (inherited when `autonomy` is omitted).

    **Verifier** — checks facts, doesn't suggest fixes, cites commands/outputs/file
    evidence, specific about what "done" means. Always `autonomy: "ReadOnly"` +
    no-edits suffix, even if the configured audit role uses a different permission.
    Pass the configured audit model when present.
    """.trimIndent() + "\n"

internal val ANDY_ADVISOR_SKILL: String =
    """
    ---
    name: andy-advisor
    description: Spin up a single agent as an advisor — second opinion on the current task. Use when the user says "advisor", "second opinion", "what does X think", or wants an outside take without delegating the work itself.
    user-invocable: true
    argument-hint: "[--provider <name>] <question>"
    ---

    # Andy Advisor

    Single agent. Reads the situation you're in. Gives a judgment. You decide what
    to do — the advisor doesn't drive the work.

    **User's request:** ${'$'}ARGUMENTS

    ## Prerequisites

    Read the **andy-orchestration** skill first. Before choosing a provider, read
    `~/.andy/orchestration-preferences.json` unless the user explicitly named a
    provider in this request. Do not create the advisor until you have read it.

    If this task does not already have `attachAndyMcp` on, none of the orchestration
    tools below are reachable — say so plainly and stop. Restart as a new task with
    the andy-orchestration skill's MCP attach requirement in mind.

    ## Picking the advisor

    1. **User named one** (`--provider Codex`) → use it.
    2. **Otherwise** resolve from preferences — pick the category that matches the question:
       - Design / approach question → `planning`
       - "Did I miss something" review → `audit`
       - "Is this even right" → `research`
    3. **Contrast helps.** If your own provider matches what preferences would pick,
       swap to a different family on purpose — fresh perspective is the point.
    4. Use the selected role's `settings` entry for its configured model. Keep the
       advisor `autonomy` at `ReadOnly` regardless of that setting.
    5. Confirm readiness via `chat.composer_options` before launching.

    ## The briefing

    The advisor has zero context. Make it self-contained:

    - The question, sharply.
    - What you've considered and what you've ruled out.
    - Relevant files by path (don't paste — let the agent read).
    - Explicit ask: "give me a recommendation, with reasoning."

    End with the no-edits suffix:

    ```
    This is analysis only. Do NOT edit, create, or delete any files. Do NOT write code.
    ```

    ## Forwarded skills

    If ${'$'}ARGUMENTS contains another skill reference — `/unslop`, `/unslop-risk`,
    `${'$'}unslop`, etc. — the user is asking the advisor to run that skill against the
    current task. Examples:

    - `/andy-advisor /unslop` → advisor runs `/unslop` on the current diff.
    - `/andy-advisor /unslop-risk` → advisor does an unslop-risk review.
    - `/andy-advisor ${'$'}diagnose this build failure` → advisor invokes `/diagnose`.

    Parse the forwarded skill name out of ${'$'}ARGUMENTS (`/<name>` or `${'$'}<name>`). In the
    briefing, tell the advisor explicitly:

    ```
    Invoke the `<name>` skill against this task. Load it via the Skill tool before doing anything else.
    ```

    Pass through any remaining arguments after the skill name as the skill's own
    input. The advisor — not you — runs the skill; you're still just the
    orchestrator handing it the work.

    ## Launch and synthesize

    Create the advisor via `chat.start` with:
    - `title`: `[Advisor] ` + short summary
    - `prompt`: the briefing (including no-edits suffix)
    - `autonomy`: `"ReadOnly"` (required — Andy enforces this)
    - resolved `agent`

    Wait for it to finish (poll `chat.status`). Read its response. Synthesize for
    the user — the advisor's verdict + your recommendation.

    ## Persistent advisor

    If the user wants ongoing input ("keep this advisor for the next few
    decisions"), don't stop after the first reply. Send follow-ups with
    `chat.resume` when you need another take. Stop when the user says they're
    done, or when the topic shifts and a fresh context would serve better.
    """.trimIndent() + "\n"

internal val ANDY_COMMITTEE_SKILL: String =
    """
    ---
    name: andy-committee
    description: Form a committee of two high-reasoning agents to step back, do root cause analysis, and produce a plan. Use when stuck, looping, tunnel-visioning, or facing a hard planning problem.
    user-invocable: true
    argument-hint: "[--providers <a>,<b>] <problem context>"
    ---

    # Andy Committee

    Two agents from contrasting providers, fresh context, planning a solution in
    parallel. They stay alive for review after implementation.

    The purpose is to step back, not double down. The committee may propose a
    completely different approach.

    **User's additional context:** ${'$'}ARGUMENTS

    ## Prerequisites

    Read the **andy-orchestration** skill first. Before choosing committee members,
    read `~/.andy/orchestration-preferences.json` unless the user explicitly named
    providers in this request. Do not create committee agents until you have read it.

    Contrast is the point of a committee, so pick across providers deliberately
    using the configured preferences rather than hardcoded defaults. Confirm both
    members are ready via `chat.composer_options`.

    If this task does not already have `attachAndyMcp` on, none of the orchestration
    tools below are reachable — say so plainly and stop. Restart as a new task with
    the andy-orchestration skill's MCP attach requirement in mind.

    ## Composition

    Two members with different reasoning styles, selected from orchestration preferences:

    - one planning/research-strength provider (`planning` or `research`)
    - one contrasting high-reasoning provider (`audit` or `impl`, different family)

    Override only when the user explicitly asks for different members.

    Pass each selected role's configured model from the `settings` map. Committee
    members remain `autonomy: "ReadOnly"` regardless of configured permissions.

    ## Hard rules

    - **No edits.** Every prompt to a committee member ends with the no-edits suffix:

      ```
      This is analysis only. Do NOT edit, create, or delete any files. Do NOT write code.
      ```

      And every `chat.start` for a committee member uses `autonomy: "ReadOnly"`.

    - **Trust the wait.** Do not poll frantically, send hurry-ups, or interrupt.
      Long waits mean it found something worth thinking about. Poll `chat.status`
      every 15–30s.
    - **You are the middleman.** Drive plan → implement → review without yielding
      to the user, except for divergences that need their call.

    ## Phase 1: Plan

    Write a problem-level prompt:

    - High-level goal and acceptance criteria
    - Constraints
    - Symptoms (if a bug)
    - What you tried and why it failed
    - Explicit: "do root cause analysis"
    - Explicit: "state assumptions, ask why three levels deep, check whether you're patching a symptom or removing the problem"
    - The no-edits suffix

    Create both agents in parallel via `chat.start` with `[Committee] ` titles, the
    same prompt, and `autonomy: "ReadOnly"`. Wait for both — not just whichever
    finishes first.

    Read both responses. Challenge them — do not accept at face value:

    - "Why does happen? Symptom or cause?"
    - Verify any assumption the plan makes about the code.
    - "What did you consider and reject?"

    Send follow-ups (`chat.resume`) until the plan addresses root cause.

    Synthesize:

    - Convergence → unified plan.
    - Significant divergence → involve the user.

    Confirm the merged plan with both members. Multi-turn until consensus.

    ## Phase 2: Implement

    Default: implement yourself. If the user said **"delegate"**, launch one impl
    agent (omit `autonomy` so it inherits this task's dial) and pass the merged plan.

    The committee stays clean — not involved in implementation.

    ## Phase 3: Review

    Send the diff to the committee (still `autonomy: "ReadOnly"` + no-edits):

    > Implementation is done. Review changes against the plan. Flag drift or missing pieces.

    Apply feedback yourself, or send to the impl agent. Repeat 2 → 3 until consensus.

    After ~10 iterations without convergence, start a fresh committee with the full
    history of what was tried — the current committee's context may have drifted
    too far.
    """.trimIndent() + "\n"
