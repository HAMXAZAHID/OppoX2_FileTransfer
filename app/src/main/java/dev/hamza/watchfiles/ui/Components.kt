package dev.hamza.watchfiles.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipColors
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Standard watch screen: time at the top, a scrolling list that works with the crown. */
@Composable
fun ListScreen(content: ScalingLazyListScope.() -> Unit) {
    val state = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = state) },
    ) {
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            autoCentering = AutoCenteringParams(itemIndex = 0),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 28.dp),
            content = content,
        )
    }
}

fun ScalingLazyListScope.header(text: String) {
    item {
        ListHeader {
            Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

fun ScalingLazyListScope.note(text: String, color: Color = Color(0xFFBDC1C6)) {
    item {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.caption2,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun ActionChip(
    label: String,
    @DrawableRes icon: Int,
    secondary: String? = null,
    colors: ChipColors = ChipDefaults.secondaryChipColors(),
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Chip(
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = secondary?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        icon = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(ChipDefaults.IconSize)) },
    )
}

/** Text box that opens the watch keyboard when tapped. */
@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    placeholder: String = "",
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.caption2, color = Color(0xFFBDC1C6), modifier = Modifier.padding(start = 10.dp, bottom = 2.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
            cursorBrush = SolidColor(Color.White),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF303134), RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, color = Color(0xFF80868B), fontSize = 14.sp)
                    }
                    inner()
                }
            },
        )
    }
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.getDefault(), if (v < 10) "%.1f %s" else "%.0f %s", v, units[i])
}

fun formatDate(millis: Long): String =
    if (millis <= 0) "" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
