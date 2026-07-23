package app.andy.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Routes mirror operations to the Android or iOS engine. Both share the single Metal presenter,
 * so connecting to one target disconnects the other immediately.
 */
class RoutingMirrorEngine(
    private val android: MirrorEngine,
    private val ios: MirrorEngine,
) : MirrorEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _session = MutableStateFlow<MirrorSession?>(null)
    override val session: StateFlow<MirrorSession?> = _session

    override val frames: Flow<MirrorFrame> = _session.flatMapLatest { session ->
        when {
            session != null && IosTargetRegistry.isIosTarget(session.serial) -> ios.frames
            else -> android.frames
        }
    }
    override val encodedVideo: Flow<EncodedVideoAccessUnit> = merge(android.encodedVideo, ios.encodedVideo)
    override val status: Flow<String> = merge(android.status, ios.status)

    init {
        scope.launch {
            android.session.collect { session ->
                if (session != null && IosTargetRegistry.isIosTarget(session.serial)) return@collect
                if (session != null || _session.value?.let { !IosTargetRegistry.isIosTarget(it.serial) } != false) {
                    _session.value = session
                }
            }
        }
        scope.launch {
            ios.session.collect { session ->
                if (session != null && !IosTargetRegistry.isIosTarget(session.serial)) return@collect
                if (session != null || _session.value?.let { IosTargetRegistry.isIosTarget(it.serial) } != false) {
                    _session.value = session
                }
            }
        }
    }

    private fun engineFor(udid: String): MirrorEngine =
        if (IosTargetRegistry.isIosTarget(udid)) ios else android

    override suspend fun connect(serial: String, config: MirrorVideoConfig): CommandResult {
        val owner = engineFor(serial)
        val other = if (owner === ios) android else ios
        other.disconnect(immediate = true)
        val result = owner.connect(serial, config)
        _session.value = owner.session.value
        return result
    }

    override suspend fun disconnect(immediate: Boolean) {
        android.disconnect(immediate)
        ios.disconnect(immediate)
        _session.value = null
    }

    override suspend fun sendInput(input: MirrorInput): CommandResult {
        val active = _session.value?.serial ?: android.session.value?.serial ?: ios.session.value?.serial
        return if (active != null) engineFor(active).sendInput(input) else CommandResult.failure("No active mirror")
    }

    override suspend fun screenshot(serial: String): ByteArray? = engineFor(serial).screenshot(serial)
}
