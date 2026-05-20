// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.batchops.struct

import android.os.Parcel
import android.os.Parcelable
import io.github.muntashirakon.AppManager.history.JsonDeserializer
import kotlinx.parcelize.Parcelize
import org.json.JSONException
import org.json.JSONObject

/**
 * Tag options for batch tag editing
 */
@Parcelize
data class BatchTagOptions(
    val tags: String?
) : IBatchOpOptions, Parcelable {

    @Throws(JSONException::class)
    constructor(jsonObject: JSONObject) : this(
        tags = if (jsonObject.has("tags")) jsonObject.getString("tags") else null
    ) {
        require(jsonObject.getString("tag") == TAG) { "Invalid tag" }
    }

    @Throws(JSONException::class)
    override fun serializeToJson(): JSONObject {
        return JSONObject().apply {
            put("tag", TAG)
            if (tags != null) put("tags", tags)
        }
    }

    companion object {
        const val TAG = "BatchTagOptions"

        @JvmField
        val DESERIALIZER = JsonDeserializer.Creator { jsonObject: JSONObject ->
            BatchTagOptions(jsonObject)
        }
    }
}
