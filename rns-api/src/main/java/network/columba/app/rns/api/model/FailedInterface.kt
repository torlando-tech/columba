package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Information about an interface that failed to initialize.
 */
@Parcelize
data class FailedInterface(
    val name: String,
    val error: String,
    val recoverable: Boolean = true,
) : Parcelable {
    companion object {
        /**
         * Parse a JSON array string into a list of FailedInterface objects.
         */
        fun parseFromJson(jsonString: String): List<FailedInterface> {
            val jsonArray = org.json.JSONArray(jsonString)
            val failedList = mutableListOf<FailedInterface>()
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                failedList.add(
                    FailedInterface(
                        // Empty defaults: the UI fallback text ("Unknown" /
                        // "Unknown error") is resolved from string resources at
                        // the consumer (DebugViewModel), not baked in here.
                        name = item.optString("name"),
                        error = item.optString("error"),
                        recoverable = item.optBoolean("recoverable", true),
                    ),
                )
            }
            return failedList
        }
    }
}
