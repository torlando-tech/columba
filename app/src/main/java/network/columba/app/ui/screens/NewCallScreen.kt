package network.columba.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import network.columba.app.R
import network.columba.app.ui.components.ProfileIcon
import network.columba.app.viewmodel.NewCallInputMode
import network.columba.app.viewmodel.NewCallPickerViewModel
import network.columba.app.viewmodel.NewCallRecentDestination

/**
 * Entry screen for manually dialing a destination.
 *
 * Offers two lookup modes: by Reticulum identity hash (the lxst.telephony
 * destination is derived from it) or by LXST destination hash directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewCallScreen(
    onBackClick: () -> Unit,
    onModeSelected: (NewCallInputMode) -> Unit,
    onRecentSelected: (NewCallRecentDestination) -> Unit,
    viewModel: NewCallPickerViewModel = hiltViewModel(),
) {
    val recentDestinations by viewModel.recentDestinations.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.new_call_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp)
                    .padding(top = 16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (recentDestinations.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.new_call_recent),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
                recentDestinations.forEach { recent ->
                    AssistChip(
                        onClick = { onRecentSelected(recent) },
                        label = {
                            Text(
                                text = recent.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingIcon = {
                            ProfileIcon(
                                iconName = recent.iconName,
                                foregroundColor = recent.iconForegroundColor,
                                backgroundColor = recent.iconBackgroundColor,
                                size = 24.dp,
                                fallbackHash = recent.hash.callHistoryIdenticonBytes(),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Button(
                onClick = { onModeSelected(NewCallInputMode.IDENTITY_HASH) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.new_call_find_by_identity_hash))
            }
            Button(
                onClick = { onModeSelected(NewCallInputMode.LXST_DESTINATION_HASH) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.new_call_find_by_lxst_destination_hash))
            }
        }
    }
}
