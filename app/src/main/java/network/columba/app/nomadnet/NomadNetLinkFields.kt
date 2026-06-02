package network.columba.app.nomadnet

import org.json.JSONObject

/**
 * Split [rawPath] on the first backtick into its clean page path and the
 * pipe-separated link-field tokens that follow, returned as
 * `(path, fieldNames)`.
 *
 * NomadNet link destinations may carry request variables after a backtick, e.g.
 * `/page/forum/thread.mu`cat=help|thread=how-to-rngit`. The backtick block is
 * NOT part of the path — the fields are submitted as request data so the node's
 * dynamically-rendered page can read them. Empty field tokens are dropped; with
 * no backtick the field list is empty and the path is returned unchanged.
 *
 * This is the deep-link / URL-bar counterpart to the in-page micron link parser
 * (`MicronParser.parseLink`), which already splits the same backtick/pipe field
 * syntax out of `[label`destination`f1|f2]` link markup. The `Pair` return
 * mirrors the sibling resolver [PartialManager.resolveNomadNetUrl].
 */
fun splitNomadNetPathFields(rawPath: String): Pair<String, List<String>> {
    val backtickIdx = rawPath.indexOf('`')
    if (backtickIdx < 0) return rawPath to emptyList()
    val path = rawPath.substring(0, backtickIdx)
    val fieldNames =
        rawPath.substring(backtickIdx + 1)
            .split('|')
            .filter { it.isNotEmpty() }
    return path to fieldNames
}

/**
 * Build the request `data` JSON for a set of NomadNet link [fieldNames], or null
 * when there is nothing to submit (no fields). Mirrors NomadNet's link-field
 * semantics, and is shared by both in-page link taps
 * (`NomadNetBrowserViewModel.navigateToLink`) and deep-link / URL-bar page loads
 * (`NomadNetBrowserViewModel.loadPage`):
 *  - `key=value` → inline variable, sent as `var_key` (split on the first `=`);
 *  - `name`      → value pulled from the current page's [formFields];
 *  - `*`         → submit every current form field not already set.
 */
fun buildNomadNetRequestData(
    fieldNames: List<String>,
    formFields: Map<String, String>,
): String? {
    if (fieldNames.isEmpty()) return null
    val data = JSONObject()
    val submitAll = "*" in fieldNames
    for (fieldEntry in fieldNames) {
        if (fieldEntry == "*") continue
        val eqIdx = fieldEntry.indexOf('=')
        if (eqIdx >= 0) {
            // Inline variable: "key=value" → "var_key".
            data.put("var_${fieldEntry.substring(0, eqIdx)}", fieldEntry.substring(eqIdx + 1))
        } else {
            // Form field reference: pull the value from current form state.
            data.put(fieldEntry, formFields[fieldEntry] ?: "")
        }
    }
    if (submitAll) {
        for ((key, value) in formFields) {
            if (!data.has(key)) data.put(key, value)
        }
    }
    return data.toString()
}
