package com.perryhertler.cragmap.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perryhertler.cragmap.orientation.RankedFormation

/**
 * Compact "Under me" shortlist of nearby formations from cliff corridors.
 */
@Composable
fun UnderMeCard(
    ranked: List<RankedFormation>,
    emptyMessage: String?,
    onSelect: (RankedFormation) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(0.72f),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.92f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = "Under me",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = Color(0xFF1F2328)
            )
            if (ranked.isEmpty()) {
                Text(
                    text = emptyMessage ?: "Nothing nearby",
                    fontSize = 12.sp,
                    color = Color(0xFF656D76),
                    modifier = Modifier.padding(top = 2.dp)
                )
            } else {
                ranked.forEach { item ->
                    Text(
                        text = "${item.name}  ·  ${item.distanceM.toInt()} m",
                        fontSize = 13.sp,
                        color = Color(0xFF0969da),
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable { onSelect(item) }
                    )
                }
            }
        }
    }
}
