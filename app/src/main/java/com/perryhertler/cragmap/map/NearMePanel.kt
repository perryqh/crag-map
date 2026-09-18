package com.perryhertler.cragmap.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * An honest shortlist, not an oracle: distances + rough direction from one
 * on-demand GPS fix, capped to a short radius (see NearMe.kt). Never claims
 * to know the exact route — tapping a result does exactly what tapping its
 * pin does.
 */
@Composable
fun NearMePanel(
    results: List<NearbyFormation>,
    onSelect: (NearbyFormation) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Phone compass heading in degrees, if known — enables a coarse facing hint. */
    deviceHeadingDegrees: Float? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text("Near me", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 2.dp))
        Text(
            "Shortlist by distance — not which climb you're under.",
            color = Color.Gray,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        if (results.isEmpty()) {
            Text("Nothing within range.", color = Color.Gray)
        } else {
            results.forEach { nearby ->
                val cardinal = cardinalDirection(nearby.bearingDegrees)
                val facing = facingHint(deviceHeadingDegrees, nearby.bearingDegrees)
                val facingBit = facing?.let { " · $it" } ?: ""
                Text(
                    text = "${nearby.area.name}  ·  ${nearby.distanceMeters.roundToInt()} m $cardinal$facingBit",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelect(nearby)
                            onDismiss()
                        }
                        .padding(vertical = 10.dp),
                )
            }
        }
        Text(
            text = "Dismiss",
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { onDismiss() }
                .padding(top = 4.dp),
        )
    }
}
