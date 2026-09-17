package com.perryhertler.cragmap.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perryhertler.cragmap.data.ClimbEntity

/**
 * Shown right after a successful capture when the sheet it was taken from
 * had climbs to offer (see MapScreen's photoTagChooser). A photo taken
 * standing in front of one wall often covers several routes a few meters
 * apart — this lets a single capture get tagged to all of them instead of
 * forcing a separate photo per climb. The button/climb that triggered the
 * capture is always included in the saved targets regardless of what's
 * checked here, so "Save" is never a no-op.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoTagSheet(
    candidates: List<ClimbEntity>,
    initiallySelected: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(initiallySelected) }
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("Which climbs does this photo show?", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                "A photo a few meters back often covers more than one route — check every climb visible in it.",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(candidates, key = { it.uuid }) { climb ->
                    val checked = climb.uuid in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("photo-tag-${climb.uuid}")
                            .clickable { selected = if (checked) selected - climb.uuid else selected + climb.uuid }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text(climb.name, fontWeight = FontWeight.Medium)
                            if (!climb.ydsGrade.isNullOrBlank()) {
                                Text(climb.ydsGrade, fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("Discard photo") }
                TextButton(onClick = { onConfirm(selected) }) { Text("Save") }
            }
        }
    }
}
