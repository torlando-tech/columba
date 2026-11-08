package network.columba.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import network.columba.app.R

/**
 * Out-of-bar indicator for a search filter that outlives its search surface.
 *
 * Material 3 keeps the query visible in a *collapsed* search bar; this app
 * animates the bar away entirely, so a dismissed-but-live query would otherwise
 * keep filtering the list with no cue. M3 models a filter that persists outside
 * its input surface as a removable chip — same contract as
 * [AnnounceFilterChips]' collapsed "Filtering: …" pill.
 *
 * Tapping the chip — or its close icon — removes the filter. Re-opening the
 * search bar for editing stays a single, unambiguous affordance: the app bar's
 * search action, which shows the retained query. A second click target inside a
 * 32dp chip is not reliably hittable, so the chip does one thing only.
 *
 * @param query The live search query the list is currently filtered by
 * @param matchCount Filtered result count, or null while loading / unavailable
 * @param onClearSearch Removes the filter (blanks the query)
 */
@Composable
fun SearchFilterSummary(
    query: String,
    matchCount: Int?,
    onClearSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (query.isBlank()) return

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InputChip(
            selected = false,
            onClick = onClearSearch,
            label = { Text(stringResource(R.string.search_filter_chip, query)) },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.search_app_bar_clear_search),
                    modifier = Modifier.size(InputChipDefaults.IconSize),
                )
            },
        )
        if (matchCount != null) {
            Text(
                text = pluralStringResource(R.plurals.search_filter_match_count, matchCount, matchCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Empty state for "the filter matched nothing", distinct from "there is nothing
 * here". Mirrors the discovered-interfaces `NoFilterMatchesCard`: quote the
 * query, explain, and offer the one action that unblocks the user.
 *
 * @param titleRes Resource quoting the query and naming what was searched, e.g.
 *   [R.string.search_no_results_title_conversations]. Callers pass the resource
 *   so the noun stays translatable instead of smuggled in as a String.
 * @param query The live search query that matched nothing
 * @param onClearSearch Removes the filter (blanks the query)
 */
@Composable
fun SearchNoResultsState(
    @StringRes titleRes: Int,
    query: String,
    onClearSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.SearchOff,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(titleRes, query),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.search_no_results_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onClearSearch) {
            Text(stringResource(R.string.search_no_results_clear))
        }
    }
}
