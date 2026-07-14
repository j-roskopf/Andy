package app.andy.latencyprobe;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaCodec;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import java.io.IOException;

/**
 * A latency target with a fixed upper-left probe region. A host tap anywhere in the view flips
 * that region on the next display frame. Andy can therefore correlate a control write with the
 * first decoded frame containing the new color, without sharing clocks with Android.
 */
public final class LatencyProbeActivity extends Activity {
    private static final String TAG = "AndyLatencyProbe";
    public static final String CONTROL_SOCKET = "andy-latency-probe";
    private ProbeView probeView;
    private LocalServerSocket controlServer;
    private Thread controlThread;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        logEncoderCapabilities();
        probeView = new ProbeView();
        setContentView(probeView);
        startControlSocket();
    }

    @Override protected void onDestroy() {
        if (controlServer != null) {
            try {
                controlServer.close();
            } catch (IOException ignored) {
                // Closing the activity intentionally unblocks accept().
            }
            controlServer = null;
        }
        if (controlThread != null) {
            controlThread.interrupt();
            controlThread = null;
        }
        super.onDestroy();
    }

    /**
     * ADB forwards this abstract socket during latency diagnostics. It deliberately avoids the
     * control-injection path so tests can separately measure the Android capture/encode floor.
     */
    private void startControlSocket() {
        final LocalServerSocket server;
        try {
            server = new LocalServerSocket(CONTROL_SOCKET);
            controlServer = server;
        } catch (IOException error) {
            Log.w(TAG, "Unable to bind latency control socket", error);
            return;
        }
        controlThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (LocalSocket socket = server.accept()) {
                    while (socket.getInputStream().read() != -1) {
                        ProbeView view = probeView;
                        if (view != null) view.post(view::toggleOnNextFrame);
                    }
                } catch (IOException error) {
                    if (controlServer != null) Log.w(TAG, "Latency control socket stopped", error);
                    return;
                }
            }
        }, "AndyLatencyProbeControl");
        controlThread.start();
    }

    /**
     * The fixture records the device's real vendor keys so latency experiments do not guess
     * which Qualcomm encoder knobs exist. This is diagnostic only; the app never changes codec
     * configuration itself.
     */
    private void logEncoderCapabilities() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        MediaCodec codec = null;
        try {
            codec = MediaCodec.createByCodecName("c2.qti.avc.encoder");
            Log.i(TAG, "c2.qti.avc.encoder vendorParameters=" + codec.getSupportedVendorParameters());
        } catch (Exception error) {
            Log.i(TAG, "Unable to query c2.qti.avc.encoder vendor parameters: " + error);
        } finally {
            if (codec != null) codec.release();
        }
    }

    private final class ProbeView extends View implements Choreographer.FrameCallback {
        // Coordinates are intentionally fixed in the portrait content surface, before density.
        static final int PROBE_LEFT_DP = 24;
        static final int PROBE_TOP_DP = 120;
        static final int PROBE_SIZE_DP = 192;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean bright = false;
        private long sequence = 0;
        private long animationFrame = 0;
        private boolean animating = false;

        ProbeView() {
            super(LatencyProbeActivity.this);
            setBackgroundColor(Color.BLACK);
            // The probe animates continuously, so it is a valid high-refresh source on panels
            // that support it. Request the platform scheduler's high mode for latency
            // validation without changing the user's global refresh preference. At 60 Hz,
            // Android input, composition, capture, and encode can each consume a full vsync.
            if (Build.VERSION.SDK_INT >= 35) {
                setRequestedFrameRate(120f);
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            final float density = getResources().getDisplayMetrics().density;
            final float left = PROBE_LEFT_DP * density;
            final float top = PROBE_TOP_DP * density;
            final float size = PROBE_SIZE_DP * density;
            paint.setColor(bright ? Color.WHITE : Color.rgb(16, 16, 16));
            canvas.drawRect(left, top, left + size, top + size, paint);
            paint.setColor(bright ? Color.BLACK : Color.WHITE);
            paint.setTextSize(28 * density);
            canvas.drawText("Andy probe " + sequence, left + 12 * density, top + size / 2, paint);

            // Force a new encoded frame on every display tick without touching the probe region.
            // This separates sustained presentation throughput from probe transition latency.
            final float trackTop = getHeight() - 72 * density;
            final float trackWidth = Math.max(1, getWidth() - 32 * density);
            final float markerLeft = 16 * density + (animationFrame % (long) trackWidth);
            paint.setColor(Color.rgb(0, 229, 255));
            canvas.drawRect(markerLeft, trackTop, Math.min(getWidth() - 16 * density, markerLeft + 24 * density), trackTop + 28 * density, paint);
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            animating = true;
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onDetachedFromWindow() {
            animating = false;
            Choreographer.getInstance().removeFrameCallback(this);
            super.onDetachedFromWindow();
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!animating) return;
            animationFrame += 12;
            postInvalidateOnAnimation();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return true;
            toggleOnNextFrame();
            return true;
        }

        void toggleOnNextFrame() {
            // Post through Choreographer so the state transition maps to a known device frame.
            Choreographer.getInstance().postFrameCallback(frameTimeNanos -> {
                bright = !bright;
                sequence++;
                postInvalidateOnAnimation();
            });
        }
    }
}
