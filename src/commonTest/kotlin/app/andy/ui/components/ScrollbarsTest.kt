package app.andy.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScrollbarsTest {
    @Test
    fun longFirstItemAdvancesScrollbarBeforeTheNextItemIsVisible() {
        val metrics = lazyScrollbarMetrics(
            totalItems = 3,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 900,
            measuredItemSizes = mapOf(0 to 2_000, 1 to 120, 2 to 120),
            viewportSize = 400,
            itemSpacing = 10,
        )

        assertTrue(metrics.maxScrollOffset > 0)
        assertEquals(900, metrics.scrollOffset)
        assertTrue(scrollbarThumb(400f, 28f, metrics).top > 0f)
    }

    @Test
    fun scrollbarOffsetInsideLongItemMapsBackToThatItem() {
        val target = lazyScrollbarTarget(
            scrollOffset = 900,
            totalItems = 3,
            measuredItemSizes = mapOf(0 to 2_000, 1 to 120, 2 to 120),
            viewportSize = 400,
            itemSpacing = 10,
        )

        assertEquals(LazyScrollbarTarget(itemIndex = 0, itemScrollOffset = 900), target)
    }

    @Test
    fun reverseLayoutPlacesLiveEdgeThumbAtTheBottom() {
        val metrics = lazyScrollbarMetrics(
            totalItems = 3,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            measuredItemSizes = mapOf(0 to 2_000, 1 to 120, 2 to 120),
            viewportSize = 400,
            itemSpacing = 10,
            reverseLayout = true,
        )

        assertEquals(metrics.maxScrollOffset, metrics.scrollOffset)
        val thumb = scrollbarThumb(400f, 28f, metrics)
        assertEquals(400f - thumb.height, thumb.top)
    }

    @Test
    fun reverseLayoutScrollbarBottomMapsToItemZero() {
        val metrics = lazyScrollbarMetrics(
            totalItems = 3,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            measuredItemSizes = mapOf(0 to 2_000, 1 to 120, 2 to 120),
            viewportSize = 400,
            itemSpacing = 10,
            reverseLayout = true,
        )
        val target = lazyScrollbarTarget(
            scrollOffset = metrics.maxScrollOffset,
            totalItems = 3,
            measuredItemSizes = mapOf(0 to 2_000, 1 to 120, 2 to 120),
            viewportSize = 400,
            itemSpacing = 10,
            reverseLayout = true,
        )

        assertEquals(LazyScrollbarTarget(itemIndex = 0, itemScrollOffset = 0), target)
    }

    @Test
    fun scrollbarUsesAUsableMinimumThumbSize() {
        val thumb = scrollbarThumb(
            trackHeight = 600f,
            minThumbHeight = 28f,
            metrics = LazyScrollbarMetrics(contentSize = 20_000, scrollOffset = 3_000, maxScrollOffset = 19_400),
        )

        assertEquals(28f, thumb.height)
        assertTrue(thumb.top > 0f)
    }

    @Test
    fun scrollStateMetricsUseTheExactPixelRange() {
        val metrics = scrollStateScrollbarMetrics(
            viewportSize = 400,
            scrollValue = 900,
            maxScrollValue = 1_600,
        )

        assertEquals(2_000, metrics.contentSize)
        assertEquals(900, metrics.scrollOffset)
        assertEquals(1_600, metrics.maxScrollOffset)
    }
}
