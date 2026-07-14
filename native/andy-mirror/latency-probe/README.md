# Andy latency probe

This tiny Android fixture changes a fixed 192 dp square at `(24 dp, 120 dp)` after a host-injected
tap. The square alternates nearly black and white, so it is straightforward to detect in a decoded
frame or by native pixel inspection. A separate animated strip at the bottom updates every display
tick to drive a sustained-frame-rate measurement without changing the probe signal.

Build and install it on a connected device:

```sh
./gradlew -p native/andy-mirror/latency-probe assembleDebug
adb install -r native/andy-mirror/latency-probe/build/outputs/apk/debug/andy-mirror-latency-probe-debug.apk
adb shell am start -n app.andy.latencyprobe/.LatencyProbeActivity
```

The latency test should inject a tap away from the probe, timestamp it on the host, then record the
first native-presented frame whose probe-region luminance crossed the black/white threshold. This
measures host input to visible frame without comparing host and Android clocks.
