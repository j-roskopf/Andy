package app.andy.desktop.service.mirror

import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Robot
import java.awt.event.InputEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for multi-presenter Metal mirrors: Live stays non-black, touch must not
 * bury the overlay, and pop-out geometry must not steal the Live presenter's surface.
 */
class GpuMirrorPresenterLifecycleTest {
    @AfterTest
    fun tearDown() {
        GpuMirrorSessions.clear()
    }

    @Test
    fun attachPresentsColoredFrameSoHostIsNotBlack() {
        if (!isMacArm64() || !GpuMirrorJni.isAvailable()) return

        lateinit var host: Canvas
        lateinit var pipeline: GpuMirrorPipeline
        SwingUtilities.invokeAndWait {
            host = realizedCanvas("gpu-live-color")
            pipeline = GpuMirrorSessions.createAndBind("gpu-live-color")!!
            val presenter = pipeline.createPresenter()!!
            assertTrue(presenter.attach(host, fillHost = true))
            presenter.setContentSize(1080, 1920)
            assertTrue(pipeline.presentSolidBgra(64, 128, blue = 40, green = 90, red = 220))
        }
        flushEdt()
        Thread.sleep(100)

        assertTrue(pipeline.framesPresented() > 0, "Expected hub frames to be presented")
        assertTrue(
            mirrorHostContainsNonBlackPixels(host),
            "Attached GPU presenter must paint non-black pixels into the Live host",
        )

        SwingUtilities.invokeAndWait {
            GpuMirrorSessions.release("gpu-live-color")
            disposeCanvas(host)
        }
    }

    @Test
    fun latePresenterAttachStillShowsFramesAlreadyDecoded() {
        if (!isMacArm64() || !GpuMirrorJni.isAvailable()) return

        // Mirrors the iOS sim race: SimulatorKit pixels arrive before Compose attaches.
        lateinit var host: Canvas
        lateinit var pipeline: GpuMirrorPipeline
        SwingUtilities.invokeAndWait {
            host = realizedCanvas("gpu-late-attach")
            pipeline = GpuMirrorSessions.createAndBind("gpu-late-attach")!!
            assertTrue(pipeline.presentSolidBgra(64, 128, blue = 20, green = 200, red = 240))
            assertTrue(
                pipeline.framesPresented() == 0L,
                "Frames must not count as presented with zero presenters",
            )
            val presenter = pipeline.createPresenter()!!
            assertTrue(presenter.attach(host, fillHost = true))
            presenter.setContentSize(64, 128)
            // Ongoing capture after attach (SimulatorKit keeps producing surfaces).
            assertTrue(pipeline.presentSolidBgra(64, 128, blue = 20, green = 200, red = 240))
        }
        flushEdt()
        val deadline = System.nanoTime() + 2_000_000_000L
        while (pipeline.framesPresented() <= 0L && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }

        assertTrue(pipeline.framesPresented() > 0, "Late attach must present retained/follow-up pixels")
        assertTrue(
            mirrorHostContainsNonBlackPixels(host),
            "iOS-style late presenter attach left the host black",
        )

        SwingUtilities.invokeAndWait {
            GpuMirrorSessions.release("gpu-late-attach")
            disposeCanvas(host)
        }
    }

    @Test
    fun rapidVisibilityAndFocusGeometryDoNotFlashHostBlack() {
        if (!isMacArm64() || !GpuMirrorJni.isAvailable()) return

        lateinit var host: Canvas
        lateinit var pipeline: GpuMirrorPipeline
        lateinit var presenter: GpuMirrorPresenter
        SwingUtilities.invokeAndWait {
            host = realizedCanvas("gpu-no-flash")
            pipeline = GpuMirrorSessions.createAndBind("gpu-no-flash")!!
            presenter = pipeline.createPresenter()!!
            assertTrue(presenter.attach(host, fillHost = true))
            presenter.setContentSize(720, 1280)
            assertTrue(pipeline.presentSolidBgra(48, 96, blue = 30, green = 180, red = 60))
        }
        flushEdt()
        Thread.sleep(100)
        assertTrue(mirrorHostContainsNonBlackPixels(host), "Precondition: colored frame visible")

        // Mimic click/focus: visibility nudge + geometry invalidate must not clear to black.
        repeat(12) {
            SwingUtilities.invokeAndWait {
                presenter.setVisible(true)
                presenter.invalidateGeometry()
                presenter.updateGeometry(host)
            }
            flushEdt()
            assertTrue(
                mirrorHostContainsNonBlackPixels(host),
                "Host flashed black on focus/geometry #$it",
            )
        }

        SwingUtilities.invokeAndWait {
            GpuMirrorSessions.release("gpu-no-flash")
            disposeCanvas(host)
        }
    }

    @Test
    fun mousePressOnHostKeepsPresenterNonBlack() {
        if (!isMacArm64() || !GpuMirrorJni.isAvailable()) return

        lateinit var host: Canvas
        lateinit var pipeline: GpuMirrorPipeline
        lateinit var presenter: GpuMirrorPresenter
        SwingUtilities.invokeAndWait {
            host = realizedCanvas("gpu-touch-color")
            pipeline = GpuMirrorSessions.createAndBind("gpu-touch-color")!!
            presenter = pipeline.createPresenter()!!
            assertTrue(presenter.attach(host, fillHost = true))
            presenter.setContentSize(720, 1280)
            assertTrue(pipeline.presentSolidBgra(48, 96, blue = 30, green = 180, red = 60))
        }
        flushEdt()
        Thread.sleep(100)
        assertTrue(mirrorHostContainsNonBlackPixels(host), "Precondition: host must show color before touch")

        // Clicking the Canvas focuses it; parenting must keep Metal above the black peer.
        val loc = host.locationOnScreen
        Robot().apply {
            autoDelay = 20
            mouseMove(loc.x + host.width / 2, loc.y + host.height / 2)
            mousePress(InputEvent.BUTTON1_DOWN_MASK)
            mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        }
        SwingUtilities.invokeAndWait {
            presenter.invalidateGeometry()
            presenter.updateGeometry(host)
            assertTrue(pipeline.presentSolidBgra(48, 96, blue = 30, green = 180, red = 60))
        }
        flushEdt()
        Thread.sleep(100)

        assertTrue(
            mirrorHostContainsNonBlackPixels(host),
            "GPU presenter must stay non-black after mouse press / focus on the host Canvas",
        )

        SwingUtilities.invokeAndWait {
            GpuMirrorSessions.release("gpu-touch-color")
            disposeCanvas(host)
        }
    }

    @Test
    fun liveAndPopOutPresentersAreIndependentAndStayNonBlack() {
        if (!isMacArm64() || !GpuMirrorJni.isAvailable()) return

        lateinit var live: Canvas
        lateinit var popOut: Canvas
        lateinit var pipeline: GpuMirrorPipeline
        lateinit var livePresenter: GpuMirrorPresenter
        lateinit var popPresenter: GpuMirrorPresenter
        SwingUtilities.invokeAndWait {
            live = realizedCanvas("gpu-live-dual")
            popOut = realizedCanvas("gpu-pop-out-dual")
            pipeline = GpuMirrorSessions.createAndBind("gpu-dual")!!
            livePresenter = pipeline.createPresenter()!!
            popPresenter = pipeline.createPresenter()!!
            assertTrue(livePresenter.attach(live, fillHost = false))
            assertTrue(popPresenter.attach(popOut, fillHost = true))
            livePresenter.setContentSize(1080, 1920)
            popPresenter.setContentSize(1080, 1920)
            assertNotEquals(livePresenter.presenterId, popPresenter.presenterId)
            assertTrue(pipeline.presentSolidBgra(80, 160, blue = 200, green = 40, red = 40))
        }
        flushEdt()
        Thread.sleep(120)

        assertTrue(mirrorHostContainsNonBlackPixels(live), "Live host stayed black with shared decoder")
        assertTrue(mirrorHostContainsNonBlackPixels(popOut), "Pop-out host stayed black with shared decoder")

        // Moving pop-out geometry must not unregister or blank the Live presenter.
        SwingUtilities.invokeAndWait {
            val frame = SwingUtilities.getWindowAncestor(popOut) as JFrame
            frame.setLocation(frame.x + 40, frame.y + 40)
            popPresenter.updateGeometry(popOut)
            livePresenter.updateGeometry(live)
            assertTrue(pipeline.presentSolidBgra(80, 160, blue = 200, green = 40, red = 40))
        }
        flushEdt()
        Thread.sleep(120)

        assertTrue(
            mirrorHostContainsNonBlackPixels(live),
            "Live must stay non-black after pop-out window moves",
        )
        assertTrue(
            mirrorHostContainsNonBlackPixels(popOut),
            "Pop-out must stay non-black after its own geometry update",
        )

        SwingUtilities.invokeAndWait {
            popPresenter.close()
            assertNotNull(GpuMirrorHostRegistry.presenterFor(live))
            assertTrue(pipeline.presentSolidBgra(80, 160, blue = 200, green = 40, red = 40))
        }
        flushEdt()
        Thread.sleep(100)

        assertTrue(
            mirrorHostContainsNonBlackPixels(live),
            "Closing pop-out must leave Live presenter presenting",
        )

        SwingUtilities.invokeAndWait {
            GpuMirrorSessions.release("gpu-dual")
            disposeCanvas(popOut)
            disposeCanvas(live)
        }
    }

    @Test
    fun androidAndIosPipelinesCanPresentSimultaneously() {
        if (!isMacArm64() || !GpuMirrorJni.isAvailable()) return

        lateinit var androidHost: Canvas
        lateinit var iosHost: Canvas
        lateinit var androidPipeline: GpuMirrorPipeline
        lateinit var iosPipeline: GpuMirrorPipeline
        SwingUtilities.invokeAndWait {
            androidHost = realizedCanvas("gpu-android-pop")
            iosHost = realizedCanvas("gpu-ios-pop")
            androidPipeline = GpuMirrorSessions.createAndBind("emulator-5554")!!
            iosPipeline = GpuMirrorSessions.createAndBind("ios-sim-iphone")!!
            val androidPresenter = androidPipeline.createPresenter()!!
            val iosPresenter = iosPipeline.createPresenter()!!
            assertTrue(androidPresenter.attach(androidHost, fillHost = true))
            assertTrue(iosPresenter.attach(iosHost, fillHost = true))
            assertTrue(androidPipeline.presentSolidBgra(64, 128, blue = 20, green = 40, red = 220))
            assertTrue(iosPipeline.presentSolidBgra(64, 128, blue = 220, green = 40, red = 20))
        }
        flushEdt()
        Thread.sleep(120)

        assertTrue(mirrorHostContainsNonBlackPixels(androidHost), "Android pop-out stayed black")
        assertTrue(mirrorHostContainsNonBlackPixels(iosHost), "iOS pop-out stayed black")

        SwingUtilities.invokeAndWait {
            GpuMirrorSessions.release("emulator-5554")
            GpuMirrorSessions.release("ios-sim-iphone")
            disposeCanvas(iosHost)
            disposeCanvas(androidHost)
        }
    }

    @Test
    fun liveHostStaysNonBlackAcrossAndroidIosAndroidSwitch() {
        if (!isMacArm64() || !GpuMirrorJni.isAvailable()) return

        lateinit var host: Canvas
        SwingUtilities.invokeAndWait {
            host = realizedCanvas("gpu-device-switch")
        }

        fun attachAndPresent(key: String, blue: Int, green: Int, red: Int): GpuMirrorPipeline {
            lateinit var pipeline: GpuMirrorPipeline
            SwingUtilities.invokeAndWait {
                pipeline = GpuMirrorSessions.createAndBind(key)!!
                val presenter = pipeline.createPresenter()!!
                assertTrue(presenter.attach(host, fillHost = true))
                presenter.setContentSize(1080, 2400)
                assertTrue(pipeline.presentSolidBgra(64, 128, blue, green, red))
            }
            flushEdt()
            Thread.sleep(120)
            assertTrue(
                mirrorHostContainsNonBlackPixels(host),
                "$key left the reused Live host black",
            )
            return pipeline
        }

        attachAndPresent("android-first", blue = 30, green = 60, red = 220)
        SwingUtilities.invokeAndWait { GpuMirrorSessions.release("android-first") }

        attachAndPresent("ios-middle", blue = 220, green = 80, red = 30)
        SwingUtilities.invokeAndWait { GpuMirrorSessions.release("ios-middle") }

        val returnedAndroid = attachAndPresent("android-returned", blue = 40, green = 210, red = 70)
        assertTrue(returnedAndroid.framesPresented() > 0, "Android presentation did not resume after iOS")

        SwingUtilities.invokeAndWait {
            GpuMirrorSessions.release("android-returned")
            disposeCanvas(host)
        }
    }

    private fun flushEdt() {
        SwingUtilities.invokeAndWait { }
    }

    private fun realizedCanvas(title: String): Canvas {
        val canvas = Canvas()
        val frame = JFrame(title)
        frame.contentPane.layout = BorderLayout()
        frame.contentPane.add(canvas, BorderLayout.CENTER)
        frame.setSize(180, 320)
        frame.isVisible = true
        return canvas
    }

    private fun disposeCanvas(canvas: Canvas) {
        SwingUtilities.getWindowAncestor(canvas)?.dispose()
    }
}
