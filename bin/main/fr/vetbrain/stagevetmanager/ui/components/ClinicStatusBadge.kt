package fr.vetbrain.stagevetmanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import fr.vetbrain.stagevetmanager.model.ClinicStatus

val STATUS_COLORS = mapOf(
    ClinicStatus.OK          to Color(0xFF2E7D32),
    ClinicStatus.WATCH       to Color(0xFFF57F17),
    ClinicStatus.BLACKLISTED to Color(0xFFB71C1C),
)

@Composable
fun ClinicStatusDot(status: ClinicStatus, modifier: Modifier = Modifier) {
    if (status == ClinicStatus.OK) return
    val color = STATUS_COLORS[status] ?: return
    Box(
        modifier = modifier
            .size(8.dp)
            .background(color, CircleShape),
    )
}

/** Ligne horizontale : [dot] [content] */
@Composable
fun RowWithStatusDot(
    status: ClinicStatus,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (status != ClinicStatus.OK) {
            ClinicStatusDot(status)
            Spacer(Modifier.width(4.dp))
        }
        content()
    }
}
