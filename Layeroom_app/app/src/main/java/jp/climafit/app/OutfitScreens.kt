package jp.climafit.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs

@Composable
internal fun OutfitsScreen(model: LayeRoomViewModel) {
    var mode by rememberSaveable { mutableStateOf(TemperatureMode.ALL) }
    var custom by rememberSaveable { mutableStateOf("18") }
    val temperature = when (mode) {
        TemperatureMode.ALL -> null
        TemperatureMode.OUTDOOR -> model.outdoorTemperature
        TemperatureMode.CURRENT -> model.room.temperature
        TemperatureMode.CUSTOM -> custom.toDoubleOrNull()?.takeIf { it.isFinite() && it in -50.0..60.0 }
    }
    val visible = model.logs.filter { log -> mode == TemperatureMode.ALL || (temperature != null &&
        (if (mode == TemperatureMode.CURRENT) log.indoorTemperature else log.outdoorTemperature)
            ?.let { abs(it - temperature) <= 2.0 } == true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("あの日の一着を、今日のヒントに。", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        item {
            UiCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(R.drawable.ic_filter), contentDescription = null, tint = Ink, modifier = Modifier.size(20.dp))
                    Text("気温から、コーデを探す", style = MaterialTheme.typography.titleMedium)
                }
                // 丸いチップ（選択中は赤、ほかはグレー）
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TemperatureMode.entries.forEach { option ->
                        FilterChip(selected = mode == option, onClick = { mode = option }, label = { Text(option.label, fontWeight = FontWeight.SemiBold) },
                            shape = RoundedCornerShape(10.dp), border = null,
                            colors = FilterChipDefaults.filterChipColors(containerColor = Tile, labelColor = Ink,
                                selectedContainerColor = Ink, selectedLabelColor = Color.White, selectedLeadingIconColor = Color.White),
                            leadingIcon = { if (mode == option) Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp)) })
                    }
                }
                if (mode == TemperatureMode.CURRENT) {
                    Selector(
                        label = "室温を確認する場所",
                        selected = model.roomName,
                        options = model.rooms.map { it.name },
                        onSelect = model::selectRoom,
                    )
                }
                if (mode == TemperatureMode.CUSTOM) {
                    TextField(custom, { custom = it }, label = { Text("指定の気温（℃）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                        isError = temperature == null, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Card, unfocusedContainerColor = Card, errorContainerColor = Card,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, errorIndicatorColor = Color.Transparent,
                            focusedLabelColor = Ink, cursorColor = Ink))
                }
                Text(when {
                    mode == TemperatureMode.ALL -> "${visible.size}コーデ"
                    temperature == null -> "−50〜60℃の温度を入力してください。"
                    mode == TemperatureMode.CURRENT -> "${model.roomName}の室温 ${formatTemp(temperature)}℃ ±2℃"
                    mode == TemperatureMode.CUSTOM -> "指定の気温 ${formatTemp(temperature)}℃ ±2℃"
                    else -> "金沢の気温 ${formatTemp(temperature)}℃ ±2℃"
                }, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
        if (visible.isEmpty()) item { UiCard { Text(
            if (model.connected && model.userName == null) "右上の歯車からユーザー登録をして、コーデを保存するとここに表示されます。"
            else if (model.logs.isEmpty()) "保存したコーデはまだありません。ホームの「写真でコーデ診断」から保存できます。"
            else "条件に合うコーデがありません。温度条件を変えてみてください。", color = Muted) } }
        items(visible, key = { it.id }) { outfit ->
            // 「Recently Played」風：大きなアートワークと、その下の太字のタイトル
            Surface(color = Card, shape = RoundedCornerShape(16.dp)) {
                Column {
                    Box {
                        val photoModifier = Modifier.fillMaxWidth().height(320.dp)
                            .background(temperaturePalette(outfit.outdoorTemperature ?: 22.0).background)
                        val image = outfit.image
                        if (image != null) {
                            Image(image.asImageBitmap(), contentDescription = "${outfit.title}の写真", modifier = photoModifier, contentScale = ContentScale.Fit)
                        } else {
                            Image(painterResource(outfit.photo), contentDescription = "${outfit.title}の写真", modifier = photoModifier, contentScale = ContentScale.Fit)
                        }
                        outfit.outdoorTemperature?.let { t ->
                            Box(Modifier.padding(14.dp).align(Alignment.TopStart)) { Badge("金沢 ${formatTemp(t)}℃", temperaturePalette(t)) }
                        }
                    }
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(outfit.title, style = MaterialTheme.typography.titleLarge, color = Ink)
                        Text("${outfit.date} · ${outfit.location}", style = MaterialTheme.typography.bodyMedium, color = Muted)
                        outfit.indoorTemperature?.let { t -> Badge("室温 ${formatTemp(t)}℃", temperaturePalette(t)) }
                        Text(outfit.comment, style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
            }
        }
    }
}
internal fun formatTemp(value: Double): String = String.format(Locale.JAPAN, "%.1f", value)
