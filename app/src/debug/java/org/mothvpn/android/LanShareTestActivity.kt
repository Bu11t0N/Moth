package org.mothvpn.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mothvpn.core.LanProxy

/** Synthetic multi-address UI fixture, debug builds only. */
class LanShareTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = MonoTheme.dark, shapes = MonoTheme.shapes) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 32.dp)) {
                        LanShareDetails(10808, LanShare.State.Running(10808), listOf(
                            LanProxy.Endpoint(LanProxy.Kind.HOTSPOT, "ap0", "192.168.43.1:10808"),
                            LanProxy.Endpoint(LanProxy.Kind.WIFI, "wlan0", "192.168.1.2:10808")))
                    }
                }
            }
        }
    }
}
