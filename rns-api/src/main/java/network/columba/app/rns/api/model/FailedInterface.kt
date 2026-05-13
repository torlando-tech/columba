package network.columba.app.rns.api.model

/**
 * Information about an interface that failed to initialize.
 */
data class FailedInterface(
    val name: String,
    val error: String,
    val recoverable: Boolean = true,
) {
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
                        name = item.optString("name", "Unknown"),
                        error = item.optString("error", "Unknown error"),
                        recoverable = item.optBoolean("recoverable", true),
                    ),
                )
            }
            return failedList
        }
    }
}
