package app.andy.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
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
            session != null && IosTargetRegistry.isIosTarget(session.serial) -> ios.status
            // Prefer the active Android engine's status while connecting / failing before a
            // session is published (e.g. remote SSH scrcpy bridge errors).
            session != null -> androidEngine.flatMapLatest { it.status }
            else -> androidEngine.flatMapLatest { engine ->
                engine.status.map { engineStatus ->
                    val trimmed = engineStatus.trim()
                    if (trimmed.isEmpty() ||
                        trimmed.equals("Disconnected", ignoreCase = true) ||
                        trimmed.equals("Ready for embedded mirror", ignoreCase = true)
                    ) {
                        "Disconnected"
                    } else {
                        trimmed
                    }
                }
            }
        }
    }
    override val presenting: StateFlow<Boolean> = _session.flatMapLatest { session ->
        when {
            session != null && IosTargetRegistry.isIosTarget(session.serial) -> ios.presenting
            else -> androidEngine.flatMapLatest { it.presenting }
        }
    }.stateIn(scope, SharingStarted.Eagerly, true)

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

    /**
     * Both backends count the hold. A Live surface stays composed across an Android ↔ iOS switch,
     * so the engine that takes over must already know a viewer is watching.
     */
    override fun acquirePresentation() {
        android().acquirePresentation()
        ios.acquirePresentation()
    }

    override fun releasePresentation() {
        android().releasePresentation()
        ios.releasePresentation()
    }

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
        // Keep the placeholder session until the engine publishes one so status/frames stay
        // routed to the owner during async scrcpy startup (and remote SSH bridge failures).
        _session.value = owner.session.value ?: _session.value
        if (!result.isSuccess && owner.session.value == null) {
            _session.value = null
        }
        return result
    }

    override suspend fun disconnect(immediate: Boolean) {
        android().disconnect(immediate)
        ios.disconnect(immediate)
        _session.value = null
    }

    override suspend fun restartForDisplayChange(serial: String, config: MirrorVideoConfig): CommandResult {
        val owner = engineFor(serial)
        val result = owner.restartForDisplayChange(serial, config)
        _session.value = owner.session.value
        return result
    }

    override suspend fun sendInput(input: MirrorInput): CommandResult {
        val active = _session.value?.serial ?: android().session.value?.serial ?: ios.session.value?.serial
        return if (active != null) engineFor(active).sendInput(input) else CommandResult.failure("No active mirror")
    }

    override suspend fun screenshot(serial: String): ByteArray? = engineFor(serial).screenshot(serial)
}
