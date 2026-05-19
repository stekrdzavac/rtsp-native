// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.sample.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.skrdzavac.rtspnative.sample.streams.StreamEntry
import com.skrdzavac.rtspnative.sample.streams.StreamsResult
import java.net.URI

@Composable
fun LandingScreen(
    result: StreamsResult,
    onOpenSingle: (StreamEntry) -> Unit,
    onOpenGrid: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = (result as? StreamsResult.Ok)?.entries.orEmpty()
    val gridEnabled = entries.size >= 4

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("RTSPKit sample", style = MaterialTheme.typography.titleLarge)

        Button(
            enabled = gridEnabled,
            onClick = onOpenGrid,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (gridEnabled) "Open 2x2 Grid" else "Open 2x2 Grid (need 4+ streams)")
        }

        HorizontalDivider()
        Text("Streams", style = MaterialTheme.typography.titleMedium)

        when (result) {
            is StreamsResult.Error -> ErrorMessage(result.message)
            is StreamsResult.Ok ->
                if (entries.isEmpty()) {
                    ErrorMessage("streams.json is empty - add at least one entry.")
                } else {
                    StreamsList(entries = entries, onTap = onOpenSingle)
                }
        }
    }
}

@Composable
private fun StreamsList(
    entries: List<StreamEntry>,
    onTap: (StreamEntry) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(entries) { entry ->
            StreamRow(entry = entry, onTap = { onTap(entry) })
        }
    }
}

@Composable
private fun StreamRow(entry: StreamEntry, onTap: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        val (title, subtitle) = displayLabel(entry.url)
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorMessage(message: String) {
    Text(
        text = message,
        color = Color.Red,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun displayLabel(url: String): Pair<String, String> {
    return try {
        val uri = URI(url)
        val host = uri.host ?: return url to ""
        val portSuffix = if (uri.port > 0) ":${uri.port}" else ""
        val path = uri.rawPath.orEmpty().ifBlank { "/" }
        (host + portSuffix) to path
    } catch (_: Exception) {
        url to ""
    }
}
