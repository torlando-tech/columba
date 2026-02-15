package com.lxmf.messenger.ui.components

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun AttachmentPanel(
    panelHeight: Dp,
    recentPhotos: List<Uri>,
    hasMediaPermission: Boolean,
    onRequestMediaPermission: () -> Unit,
    onPhotoSelected: (Uri) -> Unit,
    onGalleryClick: () -> Unit,
    onFileClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .height(panelHeight),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            // Photo grid or permission prompt
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                if (!hasMediaPermission) {
                    // Permission prompt
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .size(48.dp)
                                    .padding(bottom = 8.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Allow access to show recent photos",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onRequestMediaPermission,
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text("Allow access")
                        }
                    }
                } else if (recentPhotos.isEmpty()) {
                    // No photos found
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No photos found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    // Photo grid
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(recentPhotos, key = { it.toString() }) { uri ->
                            AsyncImage(
                                model =
                                    ImageRequest
                                        .Builder(context)
                                        .data(uri)
                                        .crossfade(true)
                                        .size(256)
                                        .build(),
                                contentDescription = "Photo",
                                modifier =
                                    Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { onPhotoSelected(uri) },
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // Action buttons row
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = onGalleryClick) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "Gallery",
                    )
                }
                Text(
                    text = "Gallery",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )

                FilledTonalIconButton(onClick = onFileClick) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = "File",
                    )
                }
                Text(
                    text = "File",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
