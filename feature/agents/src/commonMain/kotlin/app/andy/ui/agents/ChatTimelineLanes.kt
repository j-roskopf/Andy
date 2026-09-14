package app.andy.ui.agents

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.TimelineAxis
import app.andy.model.TimelineLane
import app.andy.model.TimelineModel
import app.andy.model.projectedInterval
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.PaneDividerTint
import app.andy.ui.theme.TextSecondary
import kotlin.math.abs

private val LaneInputColor = Color(0xFF5B8DEF)
private val LaneModelColor = Color(0xFF9B7EDE)
private val LaneToolsColor = Color(0xFFE0A15A)
private val BrushFill = Color(0x335B8DEF)
private val BrushStroke = Color(0xFF5B8DEF)
private val LaneLabelWidth = 44.dp
private val LaneRowHeight = 22.dp

@Composable
fun ChatTimelineLanes(
    model: TimelineModel,
    axis: TimelineAxis,
    durationMode: Boolean,
    brushStart: Float?,
    brushEnd: Float?,
    onBrushChange: (Float?, Float?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lanes = TimelineLane.entries
    Row(
        modifier
            .fillMaxWidth()
            .background(AndyColors.SurfaceRaised.copy(alpha = 0.35f))
            .padding(horizontal = AndySpace.Space2, vertical = AndySpace.Space2)
            .testTag("chat-timeline-lanes"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            Modifier.width(LaneLabelWidth).height(LaneRowHeight * lanes.size + 4.dp * (lanes.size - 1)),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            lanes.forEach { lane ->
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        lane.name,
                        color = TextSecondary,
                        fontFamily = MonoFont,
                        fontSize = 10.sp,
                    )
                }
            }
        }
        // Brush must share the track width only — not the lane labels — so fractions match bars.
        Box(
            Modifier
                .weight(1f)
                .height(LaneRowHeight * lanes.size + 4.dp * (lanes.size - 1)),
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lanes.forEach { lane ->
                    TimelineLaneTrack(
                        color = when (lane) {
                            TimelineLane.Input -> LaneInputColor
                            TimelineLane.Model -> LaneModelColor
                            TimelineLane.Tools -> LaneToolsColor
                        },
                        model = model,
                        axis = axis,
                        durationMode = durationMode,
                        lane = lane,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
            TimelineBrushOverlay(
                model = model,
                axis = axis,
                durationMode = durationMode,
                brushStart = brushStart,
                brushEnd = brushEnd,
                onBrushChange = onBrushChange,
                modifier = Modifier.fillMaxSize().testTag("chat-timeline-brush"),
            )
        }
    }
}

@Composable
private fun TimelineLaneTrack(
    color: Color,
    model: TimelineModel,
    axis: TimelineAxis,
    durationMode: Boolean,
    lane: TimelineLane,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.fillMaxHeight()) {
        val trackH = size.height.coerceAtLeast(1f)
        val barH = (trackH * 0.82f).coerceAtLeast(4f)
        val top = (trackH - barH) / 2f
        drawRect(PaneDividerTint.copy(alpha = 0.35f), size = Size(size.width, trackH))
        val minCellWidth = if (durationMode) 3f else 1f
        model.rows.asSequence().filter { it.lane == lane }.forEach { row ->
            val (startF, endF) = row.projectedInterval(model, axis, durationMode)
            val left = startF * size.width
            val right = endF * size.width
            val width = (right - left).coerceAtLeast(minCellWidth)
            val alpha = if (row.timingApproximate && durationMode) 0.55f else 0.9f
            drawRect(
                color = color.copy(alpha = alpha),
                topLeft = Offset(left, top),
                size = Size(width, barH),
            )
        }
    }
}

@Composable
private fun TimelineBrushOverlay(
    model: TimelineModel,
    axis: TimelineAxis,
    durationMode: Boolean,
    brushStart: Float?,
    brushEnd: Float?,
    onBrushChange: (Float?, Float?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keep gesture detector stable — including brushStart/End as pointerInput keys cancels mid-drag.
    val latestBrushStart by rememberUpdatedState(brushStart)
    val latestBrushEnd by rememberUpdatedState(brushEnd)
    val latestOnBrushChange by rememberUpdatedState(onBrushChange)
    val latestModel by rememberUpdatedState(model)
    val latestAxis by rememberUpdatedState(axis)
    val latestDurationMode by rememberUpdatedState(durationMode)

    Box(
        modifier
            .background(Color.Transparent)
            .pointerInput(Unit) {
                fun frac(x: Float): Float {
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    return (x / w).coerceIn(0f, 1f)
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val originX = frac(down.position.x)
                    val start = latestBrushStart
                    val end = latestBrushEnd
                    var edge: BrushEdge? = null
                    var moveOrigin = originX
                    if (start != null && end != null) {
                        val lo = minOf(start, end)
                        val hi = maxOf(start, end)
                        val handle = 0.02f
                        edge = when {
                            abs(originX - lo) <= handle -> BrushEdge.Start
                            abs(originX - hi) <= handle -> BrushEdge.End
                            originX in lo..hi -> BrushEdge.Move.also { moveOrigin = originX }
                            else -> null
                        }
                    }
                    if (edge == null) {
                        latestOnBrushChange(originX, originX)
                    }

                    var dragged = false
                    var lastX = originX
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUp()) {
                            change.consume()
                            break
                        }
                        if (change.pressed && change.positionChange() != Offset.Zero) {
                            change.consume()
                            dragged = true
                            val x = frac(change.position.x)
                            lastX = x
                            when (edge) {
                                BrushEdge.Start -> {
                                    val hi = maxOf(latestBrushStart ?: x, latestBrushEnd ?: x)
                                    latestOnBrushChange(x.coerceAtMost(hi), hi)
                                }
                                BrushEdge.End -> {
                                    val lo = minOf(latestBrushStart ?: x, latestBrushEnd ?: x)
                                    latestOnBrushChange(lo, x.coerceAtLeast(lo))
                                }
                                BrushEdge.Move -> {
                                    val curStart = latestBrushStart ?: continue
                                    val curEnd = latestBrushEnd ?: continue
                                    val delta = x - moveOrigin
                                    val lo = minOf(curStart, curEnd)
                                    val hi = maxOf(curStart, curEnd)
                                    val span = hi - lo
                                    val nextLo = (lo + delta).coerceIn(0f, 1f - span)
                                    moveOrigin = x
                                    latestOnBrushChange(nextLo, nextLo + span)
                                }
                                null -> {
                                    latestOnBrushChange(minOf(originX, x), maxOf(originX, x))
                                }
                            }
                        }
                    }

                    if (!dragged) {
                        // Tap: clear when outside an existing brush; otherwise snap to the cell under cursor.
                        if (start != null && end != null) {
                            val lo = minOf(start, end)
                            val hi = maxOf(start, end)
                            if (originX < lo || originX > hi) {
                                latestOnBrushChange(null, null)
                                return@awaitEachGesture
                            }
                        }
                        val hit = latestModel.rows.firstOrNull { row ->
                            val (a, b) = row.projectedInterval(latestModel, latestAxis, latestDurationMode)
                            originX in a..b || (abs(b - a) < 1e-4f && abs(originX - a) <= 0.02f)
                        }
                        if (hit != null) {
                            val (a, b) = hit.projectedInterval(latestModel, latestAxis, latestDurationMode)
                            latestOnBrushChange(a, b.coerceAtLeast(a + 1e-4f))
                        } else {
                            val pad = 0.02f
                            latestOnBrushChange(
                                (originX - pad).coerceAtLeast(0f),
                                (originX + pad).coerceAtMost(1f),
                            )
                        }
                    } else if (abs(lastX - originX) < 0.005f && edge == null) {
                        // Treat near-zero drag as a tap snap.
                        val hit = latestModel.rows.firstOrNull { row ->
                            val (a, b) = row.projectedInterval(latestModel, latestAxis, latestDurationMode)
                            originX in a..b
                        }
                        if (hit != null) {
                            val (a, b) = hit.projectedInterval(latestModel, latestAxis, latestDurationMode)
                            latestOnBrushChange(a, b.coerceAtLeast(a + 1e-4f))
                        }
                    }
                }
            },
    ) {
        val start = brushStart
        val end = brushEnd
        if (start != null && end != null) {
            Canvas(Modifier.fillMaxSize()) {
                val lo = minOf(start, end)
                val hi = maxOf(start, end)
                val left = lo * size.width
                val w = ((hi - lo) * size.width).coerceAtLeast(2f)
                drawRect(BrushFill, topLeft = Offset(left, 0f), size = Size(w, size.height))
                drawRect(BrushStroke.copy(alpha = 0.85f), topLeft = Offset(left, 0f), size = Size(2f, size.height))
                drawRect(
                    BrushStroke.copy(alpha = 0.85f),
                    topLeft = Offset(left + w - 2f, 0f),
                    size = Size(2f, size.height),
                )
            }
        }
    }
}

private sealed class BrushEdge {
    data object Start : BrushEdge()
    data object End : BrushEdge()
    data object Move : BrushEdge()
}
