package jp.climafit.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun HomeScreen(model: LayeRoomViewModel, onPhoto: () -> Unit, onChooseRoom: () -> Unit, onSuggest: () -> Unit) {
    val room = model.room
    val guide = PreviewData.kanazawaClothingGuide
    LazyColumn(Modifier.fillMaxSize().testTag("home-screen"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionHeader("今日の服装の目安", "金沢の天気に合わせた、今日の3つ。") }
        item {
            // 白いカード（影なし）に、グレーの四角の服
            Surface(shape = RoundedCornerShape(16.dp), color = Card, modifier = Modifier.fillMaxWidth().testTag("home-recommendation")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ClothingGuideTile("トップス", guide.top, R.drawable.guide_tshirt)
                        ClothingGuideTile("ボトムス", guide.bottom, R.drawable.guide_trousers)
                        ClothingGuideTile("羽織り", guide.layer, R.drawable.guide_cardigan)
                    }
                    HorizontalDivider(color = Outline)
                    Text(guide.note, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = Ink)
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
        item {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 左：写真でコーデ診断（黒。Neo の「Pay」風）
                Surface(onClick = onPhoto, modifier = Modifier.weight(1f).fillMaxHeight().testTag("home-photo"),
                    color = Ink, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        RoundSymbol(R.drawable.ic_camera, Color.White, Ink)
                        Text("写真で\nコーデ診断", Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, color = Color.White, textAlign = TextAlign.Center)
                        Surface(color = Color.White.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp)) {
                            Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                                Text("カメラで撮るか、\n写真を選んで診断", style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
                            }
                        }
                        ActionLabel("カメラを開く", Color.White)
                    }
                }
                // 右：場所別のおすすめ（白）
                Surface(onClick = onSuggest, modifier = Modifier.weight(1f).fillMaxHeight().testTag("home-suggestion"),
                    color = Card, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        RoundSymbol(R.drawable.ic_nav_sun, Ink, Tile)
                        Text("${room.name}の\nおすすめ", Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, color = Ink, textAlign = TextAlign.Center)
                        Button(onClick = onChooseRoom,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp).testTag("home-room-picker")
                                .semantics { contentDescription = "おすすめの場所を選ぶ" },
                            shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Tile, contentColor = Ink)) {
                            Icon(painterResource(R.drawable.ic_pin), contentDescription = null, modifier = Modifier.size(18.dp))
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("場所を選ぶ", style = MaterialTheme.typography.labelSmall, color = Muted)
                                Text(room.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            }
                            Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        ActionLabel("提案を見る", Ink)
                    }
                }
            }
        }
    }
}

/** カードの上の丸いアイコン（Neo の「Pay」の矢印風）。 */
@Composable
private fun RoundSymbol(symbol: Int, background: Color, tint: Color) {
    Box(Modifier.size(44.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(symbol), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun RowScope.ClothingGuideTile(label: String, clothing: String, image: Int) {
    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // グレーの四角に、白黒の線画の服
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Tile, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Image(painterResource(image), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(0.66f))
        }
        Text(label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium, color = Muted, textAlign = TextAlign.Center)
        Text(clothing, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold, color = Ink, textAlign = TextAlign.Center)
    }
}
