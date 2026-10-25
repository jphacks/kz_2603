package jp.climafit.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** クローゼット：保存した服の一覧（上のタブで上の服・下の服を切り替え）と削除。服が増えるのは写真でコーデ診断で保存したときだけ。 */
@Composable
internal fun ClosetScreen(model: LayeRoomViewModel) {
    var confirming by rememberSaveable { mutableStateOf<String?>(null) }
    var slot by rememberSaveable { mutableStateOf("upper") }
    // 開くたびに読み直す（写真でコーデ診断で保存した服を反映するため）
    LaunchedEffect(Unit) { model.loadCloset() }
    val closet = model.closet
    val label = if (slot == "upper") "上の服" else "下の服"
    val clothes = closet.filter { it.slot == slot }
    Column(Modifier.fillMaxSize()) {
        // 上の服・下の服を切り替えるタブ（グレーの入れ物に、選んだほうだけ黒）
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp).fillMaxWidth()
            .background(Tile, RoundedCornerShape(14.dp)).padding(4.dp).selectableGroup()) {
            for ((value, name) in listOf("upper" to "上の服", "lower" to "下の服")) {
                val selected = slot == value
                Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (selected) Ink else Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab, onClick = { slot = value })
                    .testTag("closet-slot-$value"), contentAlignment = Alignment.Center) {
                    Text("$name · ${closet.count { it.slot == value }}枚", style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold, color = if (selected) Color.White else Muted)
                }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("closet-screen"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (clothes.isEmpty()) {
                item(key = "empty-$slot") {
                    UiCard(Modifier.testTag("closet-empty")) {
                        if (model.loadingCloset) CircularProgressIndicator()
                        else Text("${label}はまだありません。ホームの「写真でコーデ診断」で保存すると、ここに服が増えます。", color = Muted)
                    }
                }
            }
            // 2列に並べる
            items(clothes.chunked(2), key = { row -> "$slot-${row.first().id}" }) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { item ->
                        ClothingTile(item, deleting = model.deletingClothing == item.id, modifier = Modifier.weight(1f)) {
                            confirming = item.id
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    val target = confirming?.let { id -> closet.firstOrNull { it.id == id } }
    if (target != null) {
        AlertDialog(onDismissRequest = { confirming = null }, modifier = Modifier.testTag("closet-delete-dialog"),
            title = { Text("「${target.title}」を削除しますか？") },
            text = { Text("クローゼットとおすすめから外れます。気温別コーデの記録は残ります。") },
            confirmButton = {
                TextButton(onClick = { model.deleteClothing(target.id); confirming = null },
                    modifier = Modifier.testTag("closet-delete-confirm")) { Text("削除する", color = Ink, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("やめる", color = Muted) } },
            containerColor = Card)
    }
}

@Composable
private fun ClothingTile(item: ClosetItemUi, deleting: Boolean, modifier: Modifier, onDelete: () -> Unit) {
    Surface(color = Card, shape = RoundedCornerShape(16.dp), modifier = modifier.testTag("closet-item-${item.id}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Tile, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                val image = item.image
                if (image != null) Image(image.asImageBitmap(), contentDescription = "${item.title}の写真",
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(6.dp))
                else Image(painterResource(item.photo), contentDescription = "${item.title}の写真",
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(0.66f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (deleting) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else IconButton(onClick = onDelete, modifier = Modifier.size(40.dp).testTag("closet-delete-${item.id}")) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = "「${item.title}」を削除", tint = Muted,
                        modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}
