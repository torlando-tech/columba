package network.columba.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import network.columba.app.data.db.entity.BlockedPeerEntity
import network.columba.app.viewmodel.BlockedUsersViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedUsersScreen(
    onBackClick: () -> Unit,
    viewModel: BlockedUsersViewModel = hiltViewModel(),
) {
    val blockedPeers by viewModel.blockedPeers.collectAsState()
    val context = LocalContext.current

    var peerToUnblock by remember { mutableStateOf<BlockedPeerEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Blocked Users") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        if (blockedPeers.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        text = "No blocked users",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding =
                    androidx.compose.foundation.layout
                        .PaddingValues(16.dp),
            ) {
                items(blockedPeers, key = { it.peerHash }) { peer ->
                    BlockedPeerCard(
                        peer = peer,
                        onUnblock = { peerToUnblock = peer },
                        onToggleBlackhole = { viewModel.toggleBlackhole(peer, !peer.isBlackholeEnabled) },
                    )
                }
            }
        }

        // Unblock confirmation dialog
        peerToUnblock?.let { peer ->
            AlertDialog(
                onDismissRequest = { peerToUnblock = null },
                title = { Text("Unblock ${peer.displayName ?: peer.peerHash.take(16)}?") },
                text = {
                    Text("They will be able to send you messages again. Their conversation will reappear if it wasn't deleted.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.unblockUser(peer)
                            peerToUnblock = null
                            Toast
                                .makeText(
                                    context,
                                    "Unblocked ${peer.displayName ?: peer.peerHash.take(16)}",
                                    Toast.LENGTH_SHORT,
                                ).show()
                        },
                    ) {
                        Text("Unblock")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { peerToUnblock = null }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@Composable
private fun BlockedPeerCard(
    peer: BlockedPeerEntity,
    onUnblock: () -> Unit,
    onToggleBlackhole: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.displayName ?: peer.peerHash.take(16) + "...",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Blocked ${dateFormat.format(Date(peer.blockedTimestamp))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Switch(
                        checked = peer.isBlackholeEnabled,
                        onCheckedChange = { onToggleBlackhole() },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(
                        text = "Blackhole (don't relay announces)",
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (peer.isBlackholeEnabled) {
                                MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }

            TextButton(
                onClick = onUnblock,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
            ) {
                Text("Unblock")
            }
        }
    }
}
