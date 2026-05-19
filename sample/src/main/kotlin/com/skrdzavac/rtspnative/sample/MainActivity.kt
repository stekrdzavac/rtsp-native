// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.sample

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.skrdzavac.rtspnative.sample.streams.StreamEntry
import com.skrdzavac.rtspnative.sample.streams.StreamsLoader
import com.skrdzavac.rtspnative.sample.streams.StreamsResult
import com.skrdzavac.rtspnative.sample.ui.GridStreamScreen
import com.skrdzavac.rtspnative.sample.ui.LandingScreen
import com.skrdzavac.rtspnative.sample.ui.SingleStreamScreen

private sealed interface Screen {
    data object Landing : Screen
    data class Single(val entry: StreamEntry) : Screen
    data object Grid : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SampleApp()
                }
            }
        }
    }
}

@Composable
private fun SampleApp() {
    val context = LocalContext.current
    val streamsResult = remember { StreamsLoader.fromAssets(context) }
    var screen: Screen by remember { mutableStateOf(Screen.Landing) }

    BackHandler(enabled = screen !is Screen.Landing) {
        screen = Screen.Landing
    }

    when (val current = screen) {
        Screen.Landing -> LandingScreen(
            result = streamsResult,
            onOpenSingle = { screen = Screen.Single(it) },
            onOpenGrid = { screen = Screen.Grid },
            modifier = Modifier.fillMaxSize(),
        )
        is Screen.Single -> SingleStreamScreen(
            entry = current.entry,
            modifier = Modifier.fillMaxSize(),
        )
        Screen.Grid -> {
            val entries = (streamsResult as? StreamsResult.Ok)?.entries.orEmpty()
            GridStreamScreen(
                entries = entries.take(4),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
