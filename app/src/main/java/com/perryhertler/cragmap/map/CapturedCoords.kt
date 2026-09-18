package com.perryhertler.cragmap.map

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import com.perryhertler.cragmap.data.PinOverrideEntity
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Garmin-friendly lat/lng display. Six decimal places is ~0.11 m at mid
 * latitudes — enough to cross-check a handheld without drowning in noise.
 * Locale.US so the separator is always `.` regardless of device locale.
 */
fun formatCapturedCoordinates(lat: Double, lng: Double): String =
    String.format(Locale.US, "%.6f, %.6f", lat, lng)

/** One-line Edit Mode summary: "Captured: lat, lng" plus optional heading. */
fun formatCapturedPinLine(override: PinOverrideEntity): String {
    val coords = formatCapturedCoordinates(override.lat, override.lng)
    val heading = override.headingDegrees?.let { " · ${it.roundToInt()}°" }.orEmpty()
    return "Captured: $coords$heading"
}

/**
 * Tappable "Captured: …" line for Edit Mode sheets. Copies just the lat/lng
 * pair (no "Captured:" prefix) so it pastes straight into a Garmin waypoint.
 */
@Composable
fun CapturedCoordsLine(
    override: PinOverrideEntity,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF9A5B00)
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val coords = formatCapturedCoordinates(override.lat, override.lng)
    Text(
        text = formatCapturedPinLine(override),
        fontSize = 12.sp,
        color = color,
        modifier = modifier.clickable {
            clipboard.setText(AnnotatedString(coords))
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        }
    )
}
