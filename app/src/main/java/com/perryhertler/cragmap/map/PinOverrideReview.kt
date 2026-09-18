package com.perryhertler.cragmap.map

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perryhertler.cragmap.data.PhotoWithTargets
import com.perryhertler.cragmap.data.CaptureStance
import com.perryhertler.cragmap.data.PinOverrideEntity
import com.perryhertler.cragmap.data.STALE_FIX_THRESHOLD_MILLIS
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Review-before-you-send step for Phase 3's field capture: a fat-fingered tap
 * on the wrong climb has no other way to be corrected in the field (the DB
 * only supports upsert-by-target, not "remove this specific mistake"), so
 * this is where that recovery happens, right before the JSON actually leaves
 * the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinOverrideReviewSheet(
    overrides: List<PinOverrideEntity>,
    photos: List<PhotoWithTargets> = emptyList(),
    onDelete: (PinOverrideEntity) -> Unit,
    onDeletePhoto: (PhotoWithTargets) -> Unit = {},
    onExport: () -> Unit,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Captured pins (${overrides.size}) · photos (${photos.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                TextButton(onClick = onExport, enabled = overrides.isNotEmpty() || photos.isNotEmpty()) {
                    Text("Export")
                }
            }
            Divider(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            if (overrides.isEmpty() && photos.isEmpty()) {
                Text(
                    "No pins or photos captured yet.",
                    color = Color.Gray,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(overrides, key = { "pin-${it.targetUuid}" }) { o ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(o.targetName, fontWeight = FontWeight.Medium)
                                val stale = o.fixAgeMillis > STALE_FIX_THRESHOLD_MILLIS
                                val coords = formatCapturedCoordinates(o.lat, o.lng)
                                val subtitle = buildString {
                                    append(o.targetType)
                                    append(" · ")
                                    append(coords)
                                    append(" · ${CaptureStance.fromStorage(o.stance).label}")
                                    if (CaptureStance.fromStorage(o.stance) != CaptureStance.TOP) {
                                        o.headingDegrees?.let { append(" · ${it.roundToInt()}°") }
                                    }
                                    if (stale) append(" · fix was ${o.fixAgeMillis / 1000}s old — STALE")
                                }
                                Text(
                                    text = subtitle,
                                    fontSize = 12.sp,
                                    color = if (stale) Color(0xFFB00020) else Color.Gray,
                                    modifier = Modifier.clickable {
                                        clipboard.setText(AnnotatedString(coords))
                                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                            IconButton(onClick = { onDelete(o) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete pin for ${o.targetName}")
                            }
                        }
                        Divider()
                    }
                    items(photos, key = { "photo-${it.photo.id}" }) { p ->
                        val names = p.targets.joinToString(", ") { it.targetName }.ifBlank { "(untagged)" }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LocalPhotoThumbnail(
                                filePath = p.photo.filePath,
                                modifier = Modifier
                                    .size(48.dp)
                                    .padding(end = 8.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(names, fontWeight = FontWeight.Medium)
                                Text("photo", fontSize = 12.sp, color = Color.Gray)
                            }
                            IconButton(onClick = { onDeletePhoto(p) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete photo of $names")
                            }
                        }
                        Divider()
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalPhotoThumbnail(filePath: String, modifier: Modifier = Modifier) {
    var bitmap by remember(filePath) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(filePath) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(filePath, opts)
            }.getOrNull()
        }
    }
    bitmap?.let {
        Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = modifier)
    }
}
