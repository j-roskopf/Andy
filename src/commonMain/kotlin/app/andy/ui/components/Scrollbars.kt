package app.andy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Pixel-based scrollbar metrics for a [LazyListState]. */
internal data class LazyScrollbarMetrics(
    val contentSize: Int,
    val scrollOffset: Int,
    val maxScrollOffset: Int,
) {
    val canScroll: Boolean get() = maxScrollOffset > 0
}

internal data class LazyScrollbarTarget(
    val itemIndex: Int,
    val itemScrollOffset: Int,
)

internal data class ScrollbarThumb(
    val top: Float,
    val height: Float,
)

/** Exact scrollbar metrics for a regular [ScrollState]. */
internal fun scrollStateScrollbarMetrics(
    viewportSize: Int,
    scrollValue: Int,
    maxScrollValue: Int,
): LazyScrollbarMetrics {
    val maxScrollOffset = maxScrollValue.coerceAtLeast(0)
    return LazyScrollbarMetrics(
        contentSize = viewportSize.coerceAtLeast(0) + maxScrollOffset,
        scrollOffset = scrollValue.coerceIn(0, maxScrollOffset),
        maxScrollOffset = maxScrollOffset,
    )
}

/**
 * Builds scrollbar geometry from pixels rather than item indexes. Keeping the measured
 * size of every item we encounter means that scrolling within one very tall message is
 * represented on the thumb, instead of appearing stationary until the next item begins.
 */
internal fun lazyScrollbarMetrics(
    totalItems: Int,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    measuredItemSizes: Map<Int, Int>,
    viewportSize: Int,
    itemSpacing: Int,
    reverseLayout: Boolean = false,
): LazyScrollbarMetrics {
    if (totalItems <= 0 || viewportSize <= 0) return LazyScrollbarMetrics(0, 0, 0)

    val averageItemSize = measuredItemSizes.values
        .filter { it > 0 }
        .average()
        .takeIf { !it.isNaN() }
        ?.roundToInt()
        ?.coerceAtLeast(1)
        ?: (viewportSize / totalItems).coerceAtLeast(1)
    val spacing = itemSpacing.coerceAtLeast(0)

    // O(measured) instead of O(totalItems): unmeasured items share averageItemSize.
    val baseSum = totalItems.toLong() * averageItemSize +
        (totalItems - 1).coerceAtLeast(0).toLong() * spacing
    val measuredDiff = measuredItemSizes.entries
        .filter { it.key in 0 until totalItems }
        .sumOf { (_, size) -> (size.coerceAtLeast(1) - averageItemSize).toLong() }
    val contentSize = (baseSum + measuredDiff).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    val maxScrollOffset = (contentSize - viewportSize).coerceAtLeast(0)
    val currentIndex = firstVisibleItemIndex.coerceIn(0, totalItems - 1)
    val baseBefore = currentIndex.toLong() * averageItemSize +
        currentIndex.toLong() * spacing
    val measuredBeforeDiff = measuredItemSizes.entries
        .filter { it.key in 0 until currentIndex }
        .sumOf { (_, size) -> (size.coerceAtLeast(1) - averageItemSize).toLong() }
    val distanceFromStart = (baseBefore + measuredBeforeDiff + firstVisibleItemScrollOffset)
        .coerceIn(0, maxScrollOffset.toLong())
        .toInt()
    val scrollOffset = if (reverseLayout) maxScrollOffset - distanceFromStart else distanceFromStart
    return LazyScrollbarMetrics(contentSize, scrollOffset, maxScrollOffset)
}

/** Converts a pixel scroll offset back into the [LazyListState.scrollToItem] coordinate system. */
internal fun lazyScrollbarTarget(
    scrollOffset: Int,
    totalItems: Int,
    measuredItemSizes: Map<Int, Int>,
    viewportSize: Int,
    itemSpacing: Int,
    reverseLayout: Boolean = false,
): LazyScrollbarTarget {
    val metrics = lazyScrollbarMetrics(
        totalItems = totalItems,
        firstVisibleItemIndex = 0,
        firstVisibleItemScrollOffset = 0,
        measuredItemSizes = measuredItemSizes,
        viewportSize = viewportSize,
        itemSpacing = itemSpacing,
        reverseLayout = reverseLayout,
    )
    if (totalItems <= 0) return LazyScrollbarTarget(0, 0)

    val averageItemSize = measuredItemSizes.values
        .filter { it > 0 }
        .average()
        .takeIf { !it.isNaN() }
        ?.roundToInt()
        ?.coerceAtLeast(1)
        ?: (viewportSize / totalItems).coerceAtLeast(1)
    val spacing = itemSpacing.coerceAtLeast(0)
    val step = (averageItemSize + spacing).coerceAtLeast(1)

    val clampedOffset = scrollOffset.coerceIn(0, metrics.maxScrollOffset)
    var remaining = (if (reverseLayout) metrics.maxScrollOffset - clampedOffset else clampedOffset).toLong()
    val measured = measuredItemSizes.entries
        .filter { it.key in 0 until totalItems }
        .sortedBy { it.key }
    var lastIndex = -1

    for (entry in measured) {
        val measuredIndex = entry.key
        val unmeasuredCount = measuredIndex - (lastIndex + 1)
        if (unmeasuredCount > 0) {
            val unmeasuredExtent = unmeasuredCount.toLong() * step
            if (remaining < unmeasuredExtent) {
                val skip = (remaining / step).toInt()
                val targetIndex = lastIndex + 1 + skip
                val offset = (remaining % step).toInt()
                val extent = averageItemSize + if (targetIndex < totalItems - 1) spacing else 0
                return LazyScrollbarTarget(targetIndex, offset.coerceAtMost((extent - 1).coerceAtLeast(0)))
            }
            remaining -= unmeasuredExtent
        }

        val extent = entry.value.coerceAtLeast(1) + if (measuredIndex < totalItems - 1) spacing else 0
        if (remaining < extent || measuredIndex == totalItems - 1) {
            return LazyScrollbarTarget(
                measuredIndex,
                remaining.toInt().coerceIn(0, (extent - 1).coerceAtLeast(0)),
            )
        }
        remaining -= extent
        lastIndex = measuredIndex
    }

    val trailingUnmeasured = totalItems - (lastIndex + 1)
    if (trailingUnmeasured > 0) {
        val skip = (remaining / step).toInt().coerceAtMost(trailingUnmeasured - 1)
        val targetIndex = lastIndex + 1 + skip
        val offset = (remaining % step).toInt()
        val extent = averageItemSize + if (targetIndex < totalItems - 1) spacing else 0
        return LazyScrollbarTarget(targetIndex, offset.coerceAtMost((extent - 1).coerceAtLeast(0)))
    }

    return LazyScrollbarTarget(totalItems - 1, 0)
}

internal fun scrollbarThumb(
    trackHeight: Float,
    minThumbHeight: Float,
    metrics: LazyScrollbarMetrics,
): ScrollbarThumb {
    if (trackHeight <= 0f) return ScrollbarThumb(0f, 0f)
    val visibleFraction = if (metrics.contentSize == 0) 1f else {
        ((metrics.contentSize - metrics.maxScrollOffset).toFloat() / metrics.contentSize).coerceIn(0f, 1f)
    }
    val height = (trackHeight * visibleFraction).coerceIn(minThumbHeight.coerceAtMost(trackHeight), trackHeight)
    val travel = trackHeight - height
    val top = if (metrics.maxScrollOffset == 0 || travel == 0f) 0f else {
        (metrics.scrollOffset.toFloat() / metrics.maxScrollOffset) * travel
    }
    return ScrollbarThumb(top, height)
}

/**
 * Exact scrollbar for a regular [ScrollState]. Unlike a lazy list, a [ScrollState] knows
 * its complete pixel range, so the thumb stays smooth while moving through long content.
 */
@Composable
internal fun DraggableScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    if (scrollState.maxValue <= 0) return

    val scope = rememberCoroutineScope()
    val minThumbHeight = with(LocalDensity.current) { 28.dp.toPx() }
    var dragging by remember { mutableStateOf(false) }
    var dragPointerY by remember { mutableStateOf(0f) }
    var dragAnchorInThumb by remember { mutableStateOf(0f) }

    fun scrollToThumb(thumbTop: Float, trackHeight: Float) {
        val metrics = scrollStateScrollbarMetrics(
            viewportSize = trackHeight.roundToInt(),
            scrollValue = scrollState.value,
            maxScrollValue = scrollState.maxValue,
        )
        val thumb = scrollbarThumb(trackHeight, minThumbHeight, metrics)
        val travel = trackHeight - thumb.height
        val fraction = if (travel <= 0f) 0f else (thumbTop / travel).coerceIn(0f, 1f)
        scope.launch { scrollState.scrollTo((fraction * scrollState.maxValue).roundToInt()) }
    }

    val currentScrollToThumb by rememberUpdatedState(::scrollToThumb)
    Canvas(
        modifier
            .width(14.dp)
            .semantics { contentDescription = "Scrollbar" }
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(scrollState, minThumbHeight) {
                detectTapGestures { offset ->
                    val metrics = scrollStateScrollbarMetrics(
                        viewportSize = size.height,
                        scrollValue = scrollState.value,
                        maxScrollValue = scrollState.maxValue,
                    )
                    val thumb = scrollbarThumb(size.height.toFloat(), minThumbHeight, metrics)
                    currentScrollToThumb(offset.y - thumb.height / 2f, size.height.toFloat())
                }
            }
            .pointerInput(scrollState, minThumbHeight) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        dragPointerY = offset.y
                        val metrics = scrollStateScrollbarMetrics(
                            viewportSize = size.height,
                            scrollValue = scrollState.value,
                            maxScrollValue = scrollState.maxValue,
                        )
                        val thumb = scrollbarThumb(size.height.toFloat(), minThumbHeight, metrics)
                        dragAnchorInThumb = if (offset.y in thumb.top..(thumb.top + thumb.height)) {
                            offset.y - thumb.top
                        } else {
                            thumb.height / 2f
                        }
                        currentScrollToThumb(dragPointerY - dragAnchorInThumb, size.height.toFloat())
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, dragAmount ->
                    change.consume()
                    dragPointerY = (dragPointerY + dragAmount.y).coerceIn(0f, size.height.toFloat())
                    currentScrollToThumb(dragPointerY - dragAnchorInThumb, size.height.toFloat())
                }
            },
    ) {
        val metrics = scrollStateScrollbarMetrics(
            viewportSize = size.height.roundToInt(),
            scrollValue = scrollState.value,
            maxScrollValue = scrollState.maxValue,
        )
        val thumb = scrollbarThumb(size.height, minThumbHeight, metrics)
        val trackWidth = 3.dp.toPx().coerceAtMost(size.width)
        val thumbWidth = 6.dp.toPx().coerceAtMost(size.width)
        val trackLeft = (size.width - trackWidth) / 2f
        val thumbLeft = (size.width - thumbWidth) / 2f
        drawRoundRect(
            color = Border.copy(alpha = if (AndyColors.isLight) 0.78f else 0.92f),
            topLeft = Offset(trackLeft, 0f),
            size = Size(trackWidth, size.height),
            cornerRadius = CornerRadius(trackWidth / 2f, trackWidth / 2f),
        )
        drawRoundRect(
            color = Cyan.copy(alpha = if (dragging) 1f else 0.78f),
            topLeft = Offset(thumbLeft, thumb.top),
            size = Size(thumbWidth, thumb.height),
            cornerRadius = CornerRadius(thumbWidth / 2f, thumbWidth / 2f),
        )
    }
}

/**
 * An Andy-styled lazy-list scrollbar with an exact thumb position for every measured
 * list item. The 14dp hit target makes the narrow visual thumb easy to grab or to click
 * anywhere on the track.
 */
@Composable
internal fun DraggableScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    reverseLayout: Boolean = false,
    onScroll: () -> Unit = {},
) {
    val layoutInfo = listState.layoutInfo
    val measuredItemSizes = remember(listState) { mutableStateMapOf<Int, Int>() }
    val visibleItemSizes = layoutInfo.visibleItemsInfo.associate { it.index to it.size }
    LaunchedEffect(layoutInfo.totalItemsCount, visibleItemSizes) {
        measuredItemSizes.keys
            .filter { it >= layoutInfo.totalItemsCount }
            .forEach(measuredItemSizes::remove)
        visibleItemSizes.forEach { (index, size) ->
            if (size > 0) measuredItemSizes[index] = size
        }
    }

    val knownItemSizes = HashMap<Int, Int>(measuredItemSizes).apply { putAll(visibleItemSizes) }
    val viewportSize = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(0)
    val metrics = lazyScrollbarMetrics(
        totalItems = layoutInfo.totalItemsCount,
        firstVisibleItemIndex = listState.firstVisibleItemIndex,
        firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
        measuredItemSizes = knownItemSizes,
        viewportSize = viewportSize,
        itemSpacing = layoutInfo.mainAxisItemSpacing,
        reverseLayout = reverseLayout,
    )
    if (!metrics.canScroll) return

    val currentMetrics by rememberUpdatedState(metrics)
    val currentItemSizes by rememberUpdatedState(knownItemSizes)
    val currentViewportSize by rememberUpdatedState(viewportSize)
    val currentItemSpacing by rememberUpdatedState(layoutInfo.mainAxisItemSpacing)
    val scope = rememberCoroutineScope()
    val minThumbHeight = with(LocalDensity.current) { 28.dp.toPx() }
    var dragging by remember { mutableStateOf(false) }
    var dragPointerY by remember { mutableStateOf(0f) }
    var dragAnchorInThumb by remember { mutableStateOf(0f) }

    fun scrollToThumb(thumbTop: Float, trackHeight: Float) {
        val current = currentMetrics
        val thumb = scrollbarThumb(trackHeight, minThumbHeight, current)
        val travel = trackHeight - thumb.height
        val fraction = if (travel <= 0f) 0f else (thumbTop / travel).coerceIn(0f, 1f)
        val target = lazyScrollbarTarget(
            scrollOffset = (fraction * current.maxScrollOffset).roundToInt(),
            totalItems = listState.layoutInfo.totalItemsCount,
            measuredItemSizes = currentItemSizes,
            viewportSize = currentViewportSize,
            itemSpacing = currentItemSpacing,
            reverseLayout = reverseLayout,
        )
        onScroll()
        scope.launch { listState.scrollToItem(target.itemIndex, target.itemScrollOffset) }
    }

    val currentScrollToThumb by rememberUpdatedState(::scrollToThumb)
    Canvas(
        modifier
            .width(14.dp)
            .semantics { contentDescription = "Scrollbar" }
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(listState, reverseLayout) {
                detectTapGestures { offset ->
                    val thumb = scrollbarThumb(size.height.toFloat(), minThumbHeight, currentMetrics)
                    currentScrollToThumb((offset.y - thumb.height / 2f), size.height.toFloat())
                }
            }
            .pointerInput(listState, reverseLayout) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        dragPointerY = offset.y
                        val thumb = scrollbarThumb(size.height.toFloat(), minThumbHeight, currentMetrics)
                        dragAnchorInThumb = if (offset.y in thumb.top..(thumb.top + thumb.height)) {
                            offset.y - thumb.top
                        } else {
                            thumb.height / 2f
                        }
                        currentScrollToThumb(dragPointerY - dragAnchorInThumb, size.height.toFloat())
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, dragAmount ->
                    change.consume()
                    dragPointerY = (dragPointerY + dragAmount.y).coerceIn(0f, size.height.toFloat())
                    currentScrollToThumb(dragPointerY - dragAnchorInThumb, size.height.toFloat())
                }
            },
    ) {
        val thumb = scrollbarThumb(size.height, minThumbHeight, metrics)
        val trackWidth = 3.dp.toPx().coerceAtMost(size.width)
        val thumbWidth = 6.dp.toPx().coerceAtMost(size.width)
        val trackLeft = (size.width - trackWidth) / 2f
        val thumbLeft = (size.width - thumbWidth) / 2f
        drawRoundRect(
            color = Border.copy(alpha = if (AndyColors.isLight) 0.78f else 0.92f),
            topLeft = Offset(trackLeft, 0f),
            size = Size(trackWidth, size.height),
            cornerRadius = CornerRadius(trackWidth / 2f, trackWidth / 2f),
        )
        drawRoundRect(
            color = Cyan.copy(alpha = if (dragging) 1f else 0.78f),
            topLeft = Offset(thumbLeft, thumb.top),
            size = Size(thumbWidth, thumb.height),
            cornerRadius = CornerRadius(thumbWidth / 2f, thumbWidth / 2f),
        )
    }
}
