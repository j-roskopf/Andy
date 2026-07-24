package app.andy.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Routes mirror operations to the Android or iOS engine. Each backend owns its own GPU decode
 * pipeline; multiple presenters (Live + pop-outs) can fan out from the same pipeline per device.
 *
 * The Android backend can be [replaceAndroidEngine] swapped so a live scrcpy session can move to a
 * pop-out pool without tearing down the process — Live then gets a fresh Android engine for the
 * next device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutingMirrorEngine(
    android: MirrorEngine,
    private val ios: MirrorEngine,
) : MirrorEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val androidEngine = MutableStateFlow(android)
    private val _session = MutableStateFlow<MirrorSession?>(null)
    override val session: StateFlow<MirrorSession?> = _session

    override val frames: Flow<MirrorFrame> = _session.flatMapLatest { session ->
        when {
            session != null && IosTargetRegistry.isIosTarget(session.serial) -> ios.frames
            else -> androidEngine.flatMapLatest { it.frames }
        }
    }
    override val encodedVideo: Flow<EncodedVideoAccessUnit> = merge(
        androidEngine.flatMapLatest { it.encodedVideo },
        ios.encodedVideo,
    )
    override val status: Flow<String> = _session.flatMapLatest { session ->
        when {
            session == null -> flowOf("Disconnected")
            IosTargetRegistry.isIosTarget(session.serial) -> ios.status
            else -> androidEngine.flatMapLatest { it.status }
        }
    }

    init {
        scope.launch {
            androidEngine.flatMapLatest { it.session }.collect { session ->
                if (session != null) {
                    if (IosTargetRegistry.isIosTarget(session.serial)) return@collect
                    _session.value = session
                    return@collect
                }
                // Ignore stale disconnects once the Android engine has already reconnected.
                val current = _session.value
                if (current != null &&
                    !IosTargetRegistry.isIosTarget(current.serial) &&
                    android().session.value == null
                ) {
                    _session.compareAndSet(current, null)
                }
            }
        }
        scope.launch {
            ios.session.collect { session ->
                if (session != null) {
                    if (!IosTargetRegistry.isIosTarget(session.serial)) return@collect
                    _session.value = session
                    return@collect
                }
                // Ignore stale disconnects once the iOS engine has already reconnected.
                val current = _session.value
                if (current != null &&
                    IosTargetRegistry.isIosTarget(current.serial) &&
                    ios.session.value == null
                ) {
                    _session.compareAndSet(current, null)
                }
            }
        }
    }

    private fun android(): MirrorEngine = androidEngine.value

    private fun engineFor(udid: String): MirrorEngine =
        if (IosTargetRegistry.isIosTarget(udid)) ios else android()

    /**
     * Installs [replacement] as the Android backend and returns the previous engine (session and
     * all). Used to hand a live Android mirror to a pop-out without killing scrcpy.
     */
    fun replaceAndroidEngine(replacement: MirrorEngine): MirrorEngine {
        val previous = androidEngine.value
        androidEngine.value = replacement
        val current = _session.value
        if (current != null && !IosTargetRegistry.isIosTarget(current.serial)) {
            _session.value = replacement.session.value
        }
        return previous
    }

    override suspend fun connect(serial: String, config: MirrorVideoConfig): CommandResult {
        val owner = engineFor(serial)
        val other = if (owner === ios) android() else ios
        other.disconnect(immediate = true)
        // Route frame Flow to the owner before connect finishes so the first metadata /
        // SimulatorKit frames reach Compose and can attach GPU presenters in time.
        _session.value = MirrorSession(
            serial = serial,
            requestedMode = config.rendererMode,
            backend = MirrorBackend(MirrorBackendKind.NativeHardware),
            width = 0,
            height = 0,
        )
        val result = owner.connect(serial, config)
        _session.value = owner.session.value
        return result
    }

    override suspend fun disconnect(immediate: Boolean) {
        android().disconnect(immediate)
        ios.disconnect(immediate)
        _session.value = null
    }

    override suspend fun sendInput(input: MirrorInput): CommandResult {
        val active = _session.value?.serial ?: android().session.value?.serial ?: ios.session.value?.serial
        return if (active != null) engineFor(active).sendInput(input) else CommandResult.failure("No active mirror")
    }

    override suspend fun screenshot(serial: String): ByteArray? = engineFor(serial).screenshot(serial)
}
