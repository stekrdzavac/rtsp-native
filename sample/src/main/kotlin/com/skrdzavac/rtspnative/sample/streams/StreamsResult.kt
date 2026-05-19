// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.sample.streams

sealed interface StreamsResult {
    data class Ok(val entries: List<StreamEntry>) : StreamsResult
    data class Error(val message: String) : StreamsResult
}
