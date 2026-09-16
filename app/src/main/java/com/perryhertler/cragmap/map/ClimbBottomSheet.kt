package com.perryhertler.cragmap.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.perryhertler.cragmap.data.ClimbEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClimbBottomSheet(
    areaName: String,
    climbs: List<ClimbEntity>,
    highlightedClimbUuid: String?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
            if (areaName.isNotBlank()) {
                item {
                    Text(areaName, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            items(climbs) { climb ->
                val highlighted = climb.uuid == highlightedClimbUuid
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
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
                Divider()
            }
        }
    }
}
