package com.perryhertler.cragmap.map

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import android.widget.Toast
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.perryhertler.cragmap.data.AreaEntity
import com.perryhertler.cragmap.data.ClimbEntity
import com.perryhertler.cragmap.data.PinOverrideEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the bottom sheet shows for one area: either its child areas (tap a
 * non-leaf area — bluff, subarea, or any of the previously-invisible
 * intermediate levels) or its climbs (tap a formation). Both carry a
 * breadcrumb and the sorted sibling list prev/next navigates through.
 */
sealed class SheetContent {
    abstract val area: AreaEntity
    abstract val breadcrumb: List<AreaEntity>
    abstract val siblings: List<AreaEntity>

    data class Children(
        override val area: AreaEntity,
        override val breadcrumb: List<AreaEntity>,
        override val siblings: List<AreaEntity>,
        val children: List<AreaEntity>
    ) : SheetContent()

    data class Climbs(
        override val area: AreaEntity,
        override val breadcrumb: List<AreaEntity>,
        override val siblings: List<AreaEntity>,
        val climbs: List<ClimbEntity>,
        val highlightedClimbUuid: String?
    ) : SheetContent()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AreaSheet(
    content: SheetContent,
    onSelectArea: (AreaEntity) -> Unit,
    onNavigateSibling: (Int) -> Unit,
    onDismiss: () -> Unit,
    // Phase 3 (East Rampart baseline pack) field-survey capture. Only
    // meaningful when editModeEnabled — see MapScreen's edit-mode toggle.
    editModeEnabled: Boolean = false,
    overrideCount: Int = 0,
    // Existing field pins keyed by target uuid — Review already loads the full
    // list; AreaSheet only needed counts before. Enough to show "Captured:
    // lat, lng" for the open area (and each climb row) without a bigger refactor.
    pinOverridesByUuid: Map<String, PinOverrideEntity> = emptyMap(),
    onCaptureAreaPin: () -> Unit = {},
    onCaptureClimbPin: (ClimbEntity) -> Unit = {},
    // Photo capture — same Edit Mode gating as pins (see the blueprint's
    // "make photos available" thread).
    photoCount: Int = 0,
    onCaptureAreaPhoto: () -> Unit = {},
    onCaptureClimbPhoto: (ClimbEntity) -> Unit = {},
    onOpenOverrideReview: () -> Unit = {},
    // Ephemeral sun-readable line after the latest pin capture (MapScreen).
    lastCaptureFeedback: CaptureFeedback? = null,
) {
    // Half-expanded by default so the map (and selected pin) stay visible
    // above the sheet — full expand is still available via drag.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val siblingIndex = content.siblings.indexOfFirst { it.uuid == content.area.uuid }
    LaunchedEffect(content.area.uuid) {
        // Re-assert half-expanded when paging siblings so the sheet doesn't
        // jump to full height and cover the map mid-browse.
        runCatching { sheetState.partialExpand() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
            item {
                if (content.breadcrumb.isNotEmpty()) {
                    // Ancestors only (root-first); current area is the title below.
                    // Each segment navigates up via onSelectArea — separators stay inert.
                    FlowRow(
                        modifier = Modifier.padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.Start,
                        verticalArrangement = Arrangement.Center
                    ) {
                        content.breadcrumb.forEachIndexed { index, area ->
                            if (index > 0) {
                                Text(
                                    text = " › ",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }
                            Text(
                                text = area.name,
                                fontSize = 12.sp,
                                color = Color.Gray,
                                fontWeight = FontWeight.Medium,
                                textDecoration = TextDecoration.Underline,
                                modifier = Modifier
                                    .testTag("breadcrumb_${area.uuid}")
                                    .clickable { onSelectArea(area) }
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(content.area.uuid, siblingIndex) {
                            var total = 0f
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    when {
                                        total < -80f && siblingIndex in 0 until content.siblings.lastIndex ->
                                            onNavigateSibling(1)
                                        total > 80f && siblingIndex > 0 ->
                                            onNavigateSibling(-1)
                                    }
                                    total = 0f
                                },
                                onHorizontalDrag = { _, dragAmount -> total += dragAmount },
                            )
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { onNavigateSibling(-1) },
                        enabled = siblingIndex > 0,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Filled.ChevronLeft,
                            contentDescription = "Previous wall",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    Text(
                        text = content.area.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                    )
                    IconButton(
                        onClick = { onNavigateSibling(1) },
                        enabled = siblingIndex in 0 until content.siblings.lastIndex,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = "Next wall",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
                Divider(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))

                if (editModeEnabled) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFFF3E0), RoundedCornerShape(6.dp))
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "EDIT MODE — field survey capture",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF9A5B00)
                            )
                            TextButton(
                                onClick = onOpenOverrideReview,
                                enabled = overrideCount > 0 || photoCount > 0
                            ) {
                                Text("Review → Export (${overrideCount + photoCount})", fontSize = 12.sp)
                            }
                        }
                        lastCaptureFeedback?.let { feedback ->
                            val coords = formatCapturedCoordinates(feedback.lat, feedback.lng)
                            val clipboard = LocalClipboardManager.current
                            val ctx = LocalContext.current
                            Text(
                                text = formatCaptureFeedbackLine(feedback),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF9A5B00),
                                modifier = Modifier
                                    .padding(top = 6.dp, bottom = 2.dp)
                                    .clickable {
                                        clipboard.setText(AnnotatedString(coords))
                                        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                                    },
                            )
                        }
                        TextButton(onClick = onCaptureAreaPin, modifier = Modifier.padding(top = 2.dp)) {
                            Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                            Text("Set ${content.area.name}'s pin to my location")
                        }
                        pinOverridesByUuid[content.area.uuid]?.let { CapturedCoordsLine(it) }
                        TextButton(onClick = onCaptureAreaPhoto) {
                            Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                            Text("Take a photo of ${content.area.name}")
                        }
                    }
                    Divider(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                }

                // Bundled photos from a prior field trip (merged into this
                // area's assets/photos/<uuid>/ folder — see
                // tools/merge_photo_overrides.py). Shown regardless of Edit
                // Mode, since viewing isn't a survey action.
                BundledPhotoRow(targetUuid = content.area.uuid)
            }

            when (content) {
                is SheetContent.Children -> items(content.children) { child ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectArea(child) }
                            .padding(vertical = 10.dp)
                    ) {
                        Text(child.name, fontWeight = FontWeight.Medium)
                        val subtitle = if (child.isLeaf == 0) {
                            "${child.totalClimbs} climbs · more areas inside"
                        } else {
                            "${child.totalClimbs} climbs"
                        }
                        Text(subtitle, fontSize = 12.sp, color = Color.Gray)
                    }
                    Divider()
                }

                is SheetContent.Climbs -> items(content.climbs) { climb ->
                    val highlighted = climb.uuid == content.highlightedClimbUuid
                    Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(
                                text = buildAnnotatedString {
                                    append(climb.name)
                                    if (!climb.ydsGrade.isNullOrBlank()) append("  ·  ${climb.ydsGrade}")
                                    if (!climb.climbType.isNullOrBlank()) append("  ·  ${climb.climbType}")
                                    // OpenBeta danger/runout (PG/PG13/R/X) — not quality stars.
                                    if (!climb.safetyRating.isNullOrBlank()) {
                                        append("  ·  ")
                                        withStyle(SpanStyle(color = Color(0xFFB00020), fontWeight = FontWeight.Bold)) {
                                            append(climb.safetyRating)
                                        }
                                    }
                                },
                                fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (editModeEnabled) {
                                pinOverridesByUuid[climb.uuid]?.let { CapturedCoordsLine(it) }
                            }
                        }
                        // Only worth setting when a route sits on a different face than its
                        // formation's main pin — see NearMe's honesty-vs-GPS-noise tradeoff.
                        // Not meant to be tapped for every climb.
                        if (editModeEnabled) {
                            IconButton(onClick = { onCaptureClimbPin(climb) }) {
                                Icon(
                                    Icons.Filled.MyLocation,
                                    contentDescription = "Set ${climb.name}'s pin to my location (different face only)",
                                    tint = Color(0xFF9A5B00)
                                )
                            }
                            IconButton(onClick = { onCaptureClimbPhoto(climb) }) {
                                Icon(
                                    Icons.Filled.CameraAlt,
                                    contentDescription = "Take a photo of ${climb.name}",
                                    tint = Color(0xFF9A5B00)
                                )
                            }
                        }
                    }
                    BundledPhotoRow(targetUuid = climb.uuid)
                    }
                    Divider()
                }
            }
        }
    }
}

private const val PHOTO_THUMBNAIL_SIZE_DP = 64

/**
 * Photos bundled into the app's assets for this area/climb uuid by
 * tools/merge_photo_overrides.py (assets/photos/<uuid>/ containing .jpg files — a plain
 * AssetManager.list(), not a Room table, per the blueprint's "how would we
 * make the photos available to view" discussion: this is a read-only bundled
 * snapshot exactly like devils_lake.db, not live/synced data). Absent for
 * almost every target today since no field trip has shipped photos yet —
 * renders nothing in that case.
 */
@Composable
private fun BundledPhotoRow(targetUuid: String) {
    val context = LocalContext.current
    var fileNames by remember(targetUuid) { mutableStateOf<List<String>>(emptyList()) }
    var enlarged by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(targetUuid) {
        fileNames = withContext(Dispatchers.IO) {
            runCatching { context.assets.list("photos/$targetUuid")?.sorted() ?: emptyList() }
                .getOrDefault(emptyList())
        }
    }

    if (fileNames.isEmpty()) return

    LazyRow(modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)) {
        items(fileNames) { name ->
            val assetPath = "photos/$targetUuid/$name"
            AssetThumbnail(
                assetPath = assetPath,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(PHOTO_THUMBNAIL_SIZE_DP.dp)
                    .clickable { enlarged = assetPath }
            )
        }
    }

    enlarged?.let { assetPath ->
        Dialog(onDismissRequest = { enlarged = null }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { enlarged = null },
                contentAlignment = Alignment.Center
            ) {
                AssetImage(assetPath = assetPath, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun AssetThumbnail(assetPath: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(assetPath) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(assetPath) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(assetPath).use { stream ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeStream(stream, null, opts)
                }
            }.getOrNull()
        }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            modifier = modifier
        )
    }
}

@Composable
private fun AssetImage(assetPath: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(assetPath) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(assetPath) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
    }
    bitmap?.let {
        Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = modifier)
    }
}
