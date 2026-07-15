package app.andy.ui.actions

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.components.Button
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.components.secondaryButtonColors
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private data class IntroductionPage(
    val eyebrow: String,
    val title: String,
    val body: String,
    val accent: Color,
    val diagram: List<IntroductionDiagramRow>,
)

private data class IntroductionDiagramRow(
    val lead: String,
    val title: String,
    val detail: String,
)

private val introductionPages = listOf(
    IntroductionPage(
        eyebrow = "PROJECTS IN ANDY",
        title = "A deliberate home for work that matters.",
        body = "Projects keep the decisions, commands, notes, and AI work for one codebase in the same place. Start with a workspace, then let the work gather useful context as it moves.",
        accent = Rust,
        diagram = listOf(
            IntroductionDiagramRow("01", "Create a project", "Choose its name and local workspace."),
            IntroductionDiagramRow("02", "Keep work together", "Tasks, commands, notes, and sessions stay attached."),
            IntroductionDiagramRow("03", "Return with context", "The project becomes the durable record of the work."),
        ),
    ),
    IntroductionPage(
        eyebrow = "THE WORKING MEMORY",
        title = "Turn recurring commands and loose thoughts into leverage.",
        body = "Add your repeatable commands to the runbook, then use the scratchpad for constraints, links, observations, and decisions that should travel with future work.",
        accent = Cyan,
        diagram = listOf(
            IntroductionDiagramRow("RUN", "Runbook", "Store build, test, debug, and deploy actions."),
            IntroductionDiagramRow("NOTE", "Scratchpad", "Capture the context that does not belong in a commit."),
            IntroductionDiagramRow("USE", "At the right moment", "Run actions beside the task and keep its output close."),
        ),
    ),
    IntroductionPage(
        eyebrow = "THE DELIVERY LOOP",
        title = "Create a spec, then move it through the work with intention.",
        body = "A spec establishes the plan. From there, build work, review it against the actual workspace, and validate it against the requirements before calling it done.",
        accent = Green,
        diagram = listOf(
            IntroductionDiagramRow("PLAN", "Spec", "Define the implementation and success criteria."),
            IntroductionDiagramRow("MAKE", "Build and review", "Implement, then make code quality a clear gate."),
            IntroductionDiagramRow("PROVE", "Validate", "Run focused checks against the frozen plan."),
        ),
    ),
    IntroductionPage(
        eyebrow = "AI, IN CONTEXT",
        title = "Use sessions as a working conversation, not a detached prompt.",
        body = "Start a session from the project dock whenever you need a collaborator. Andy keeps the conversation alongside the workflow, runbook, scratchpad, and terminal output it needs to understand.",
        accent = Rust,
        diagram = listOf(
            IntroductionDiagramRow("ASK", "Start a session", "Give the agent the task from the project work dock."),
            IntroductionDiagramRow("WATCH", "Follow the work", "Open the session without leaving the project context."),
            IntroductionDiagramRow("BUILD", "Carry context forward", "Use project notes and workflow state to make the next step sharper."),
        ),
    ),
)

@Composable
internal fun ProjectsIntroduction(onComplete: () -> Unit, modifier: Modifier = Modifier) {
    val pagerState = rememberPagerState(pageCount = { introductionPages.size })
    val scope = rememberCoroutineScope()
    val page = introductionPages[pagerState.currentPage]
    val isLastPage = pagerState.currentPage == introductionPages.lastIndex

    Box(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(AndyRadius.R4))
            .background(AndyColors.Neutral850)
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R4)),
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .width(360.dp)
                .height(360.dp)
                .background(page.accent.copy(alpha = 0.08f), RoundedCornerShape(180.dp)),
        )
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .width(280.dp)
                .height(280.dp)
                .background(page.accent.copy(alpha = 0.05f), RoundedCornerShape(140.dp)),
        )
        Column(Modifier.fillMaxSize().padding(32.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("PROJECTS", color = TextPrimary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onComplete, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("Skip introduction", fontFamily = MonoFont, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().weight(1.45f, fill = false)) { index ->
                IntroductionChapter(introductionPages[index])
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IntroductionProgress(current = pagerState.currentPage, total = introductionPages.size, accent = page.accent)
                Spacer(Modifier.weight(1f))
                if (pagerState.currentPage > 0) {
                    OutlinedButton(onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } }, colors = secondaryButtonColors()) {
                        Text("Back")
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    onClick = {
                        if (isLastPage) onComplete()
                        else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    },
                    colors = primaryButtonColors(),
                ) {
                    Text(if (isLastPage) "Open projects" else "Continue")
                }
            }
        }
    }
}

@Composable
private fun IntroductionChapter(page: IntroductionPage) {
    AnimatedContent(
        targetState = page,
        transitionSpec = { fadeIn(tween(280, easing = FastOutSlowInEasing)) togetherWith fadeOut(tween(160)) },
        label = "projects-introduction-page",
    ) { current ->
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(current.eyebrow, color = current.accent, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.2.sp)
                Text(
                    current.title,
                    color = TextPrimary,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 40.sp,
                    lineHeight = 46.sp,
                )
                Text(current.body, color = TextSecondary, fontFamily = MonoFont, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.widthIn(max = 610.dp))
            }
            IntroductionDiagram(current, Modifier.weight(0.9f).fillMaxHeight())
        }
    }
}

@Composable
private fun IntroductionDiagram(page: IntroductionPage, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(AndyColors.Neutral900.copy(alpha = 0.72f), RoundedCornerShape(AndyRadius.R5))
            .border(1.dp, page.accent.copy(alpha = 0.32f), RoundedCornerShape(AndyRadius.R5))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        page.diagram.forEachIndexed { index, row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(if (index == 1) page.accent.copy(alpha = 0.13f) else Color.Transparent, RoundedCornerShape(AndyRadius.R3))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(row.lead, color = page.accent, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(row.title, color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Text(row.detail, color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun IntroductionProgress(current: Int, total: Int, accent: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { index ->
            Box(
                Modifier
                    .width(if (index == current) 30.dp else 7.dp)
                    .height(7.dp)
                    .background(if (index == current) accent else AndyColors.Neutral600, RoundedCornerShape(AndyRadius.Pill)),
            )
        }
        Text("${current + 1} / $total", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp, textAlign = TextAlign.End, modifier = Modifier.padding(start = 4.dp))
    }
}
