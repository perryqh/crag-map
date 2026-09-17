package com.perryhertler.cragmap.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perryhertler.cragmap.data.AreaEntity
import com.perryhertler.cragmap.data.ClimbEntity

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

@OptIn(ExperimentalMaterial3Api::class)
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
    onCaptureAreaPin: () -> Unit = {},
    onCaptureClimbPin: (ClimbEntity) -> Unit = {},
    onExportOverrides: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState()
    val siblingIndex = content.siblings.indexOfFirst { it.uuid == content.area.uuid }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
            item {
                if (content.breadcrumb.isNotEmpty()) {
                    Text(
                        text = content.breadcrumb.joinToString(" › ") { it.name },
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { onNavigateSibling(-1) },
                        enabled = siblingIndex > 0
                    ) {
                        Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous")
                    }
                    Text(
                        text = content.area.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    IconButton(
                        onClick = { onNavigateSibling(1) },
                        enabled = siblingIndex in 0 until content.siblings.lastIndex
                    ) {
                        Icon(Icons.Filled.ChevronRight, contentDescription = "Next")
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
                            TextButton(onClick = onExportOverrides, enabled = overrideCount > 0) {
                                Text("Export ($overrideCount)", fontSize = 12.sp)
                            }
                        }
                        TextButton(onClick = onCaptureAreaPin, modifier = Modifier.padding(top = 2.dp)) {
                            Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                            Text("Set ${content.area.name}'s pin to my location")
                        }
                    }
                    Divider(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                }
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(
                                text = buildString {
                                    append(climb.name)
                                    if (!climb.ydsGrade.isNullOrBlank()) append("  ·  ${climb.ydsGrade}")
                                    if (!climb.climbType.isNullOrBlank()) append("  ·  ${climb.climbType}")
                                },
                                fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal
                            )
                            if (!climb.description.isNullOrBlank()) {
                                Text(climb.description)
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
                        }
                    }
                    Divider()
                }
            }
        }
    }
}
