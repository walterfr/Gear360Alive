package com.simoneluconi.revive360.link

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StockCameraControllerTest {

    @Test
    fun initialCameraState() {
        val state = StockCameraState()
        assertEquals(StockMode.IOS, state.mode)
        assertEquals(WifiLayerState.Disconnected, state.wifi)
        assertEquals(CameraHealthState.Unknown, state.health)
        assertEquals(SessionLayerState.Idle, state.session)
    }

    @Test
    fun stateCopyAndLayerTransitions() {
        var state = StockCameraState()

        // Transition 1: Wi-Fi connecting
        state = state.copy(wifi = WifiLayerState.Connecting)
        assertEquals(WifiLayerState.Connecting, state.wifi)

        // Transition 2: Wi-Fi connected
        state = state.copy(wifi = WifiLayerState.Connected(host = "192.168.43.1", frequencyMhz = 2412, linkMbps = 72))
        assertTrue(state.wifi is WifiLayerState.Connected)
        val connectedWifi = state.wifi as WifiLayerState.Connected
        assertEquals("192.168.43.1", connectedWifi.host)
        assertEquals(2412, connectedWifi.frequencyMhz)
        assertEquals(72, connectedWifi.linkMbps)

        // Transition 3: Session live preview
        state = state.copy(session = SessionLayerState.LivePreview)
        assertEquals(SessionLayerState.LivePreview, state.session)

        // Transition 4: back to idle (no countdown: the session is kept, the Wi-Fi stays)
        state = state.copy(session = SessionLayerState.Idle)
        assertEquals(SessionLayerState.Idle, state.session)

        // Transition 5: Wi-Fi lost
        val lostTime = 1700000000000L
        state = state.copy(wifi = WifiLayerState.Lost(lostTime), lastDropReason = "Wi-Fi lost")
        assertEquals(lostTime, (state.wifi as WifiLayerState.Lost).atMillis)
        assertEquals("Wi-Fi lost", state.lastDropReason)
    }
}
