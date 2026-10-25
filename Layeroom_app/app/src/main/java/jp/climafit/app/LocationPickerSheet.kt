package jp.climafit.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocationPickerSheet(title: String, rooms: List<RoomUi>, selected: String?, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Paper) {
        LazyColumn(Modifier.fillMaxWidth().testTag("location-sheet"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                CenteredHeading(title, "登録されている場所から1つ選んでください。", R.drawable.ic_pin)
                Spacer(Modifier.height(12.dp))
            }
            // 白い行（グレーの四角のアイコン・場所名・室温と湿度）。Neo の口座一覧風
            items(rooms, key = { it.name }) { room ->
                val isSelected = selected == room.name
                ListItem(
                    leadingContent = { SymbolBadge(R.drawable.ic_pin, if (isSelected) Card else Ink, if (isSelected) Ink else Tile) },
                    headlineContent = { Text(room.name, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text("室温 ${formatTemp(room.temperature)}℃ · 湿度 ${room.humidity}%", color = Muted) },
                    trailingContent = { RadioButton(selected = isSelected, onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = Ink, unselectedColor = Faint)) },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).testTag("location-${room.name}")
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(room.name) }),
                    colors = ListItemDefaults.colors(containerColor = Card),
                )
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
