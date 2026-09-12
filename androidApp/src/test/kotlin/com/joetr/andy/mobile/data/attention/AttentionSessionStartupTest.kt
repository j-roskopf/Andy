package com.joetr.andy.mobile.data.attention

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionSessionStartupTest {
    @Test
    fun foregroundAndConnectionDoNotWaitForEncryptedPersistence() = runTest {
        val persistenceStarted = CompletableDeferred<Unit>()
        val releasePersistence = CompletableDeferred<Unit>()
        var foregroundStarted = false
        var connected = false

        val persistence = startAttentionSession(
            scope = backgroundScope,
            persist = {
                persistenceStarted.complete(Unit)
                releasePersistence.await()
            },
            startForeground = { foregroundStarted = true },
            connect = { connected = true },
        )

        assertTrue(foregroundStarted)
        assertTrue(connected)
        assertFalse(persistence.isCompleted)
        persistenceStarted.await()
        releasePersistence.complete(Unit)
    }
}
