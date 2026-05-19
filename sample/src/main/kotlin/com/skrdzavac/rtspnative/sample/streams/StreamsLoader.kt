// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.sample.streams

import android.content.Context
import java.io.FileNotFoundException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object StreamsLoader {

    fun fromAssets(context: Context, fileName: String = "streams.json"): StreamsResult {
        val text = try {
            context.assets.open(fileName).bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            return StreamsResult.Error(
                "No $fileName found in assets. Add $fileName at the sample " +
                    "module root with [{\"url\":..., \"username\":..., \"password\":...}, ...]."
            )
        }
        return parse(text)
    }

    fun parse(json: String): StreamsResult {
        val array = try {
            JSONArray(json)
        } catch (e: JSONException) {
            return StreamsResult.Error(
                "streams.json must be a JSON array: ${e.message}"
            )
        }

        val out = ArrayList<StreamEntry>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
                ?: return StreamsResult.Error(
                    "streams.json entry $i is not a JSON object"
                )
            val entry = parseEntry(i, obj) ?: return entryError(i, obj)
            out += entry
        }
        return StreamsResult.Ok(out)
    }

    private fun parseEntry(index: Int, obj: JSONObject): StreamEntry? {
        val url = obj.optString("url", "").trim()
        if (url.isBlank()) return null
        val username = if (obj.isNull("username")) "" else obj.optString("username", "")
        val password = if (obj.isNull("password")) "" else obj.optString("password", "")
        return StreamEntry(url = url, username = username, password = password)
    }

    private fun entryError(index: Int, obj: JSONObject): StreamsResult.Error {
        val hasUrlKey = obj.has("url")
        val reason = if (!hasUrlKey) "missing \"url\"" else "blank \"url\""
        return StreamsResult.Error("streams.json entry $index has $reason")
    }
}
