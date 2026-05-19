// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.sample.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.skrdzavac.rtspnative.sample.streams.StreamEntry

@Composable
fun SingleStreamScreen(
    entry: StreamEntry,
    modifier: Modifier = Modifier,
) {
    Text(text = "SingleStreamScreen: ${entry.url}", modifier = modifier.fillMaxSize())
}
