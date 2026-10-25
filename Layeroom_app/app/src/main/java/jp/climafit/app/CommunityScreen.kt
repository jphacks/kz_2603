package jp.climafit.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommunityScreen(model: LayeRoomViewModel, selectedLocation: String?, onChooseLocation: () -> Unit) {
    var showForm by rememberSaveable { mutableStateOf(false) }
    var location by rememberSaveable(selectedLocation) { mutableStateOf(selectedLocation ?: model.roomName) }
    var feel by rememberSaveable { mutableStateOf("快適") }
    var comment by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val reviews = model.reviews.filter { it.location == selectedLocation }
    // バックエンド接続時は、場所を選んだらその場所の投稿を読み込む
    LaunchedEffect(selectedLocation) { selectedLocation?.let(model::loadReviews) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("community-screen"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("教室の「ちょっと寒い」を、シェアしよう。", Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.bodyMedium, color = Muted)
                    // グレーの丸ボタン（場所を選ぶ・変える）
                    Button(onClick = onChooseLocation, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("community-location"),
                        shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Tile, contentColor = Ink)) {
                        Icon(painterResource(R.drawable.ic_pin), contentDescription = null, tint = Ink, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(selectedLocation?.let { "$it · 場所を変更" } ?: "口コミを見る場所を選ぶ", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (selectedLocation == null) {
                item { UiCard { Text("場所を選ぶと、その場所の口コミだけを表示します。", color = Muted) } }
            } else {
                item { SectionHeader("${selectedLocation}の口コミ · ${reviews.size}件") }
                if (reviews.isEmpty()) item { UiCard { Text("この場所の口コミはまだありません。", color = Muted) } }
                items(reviews, key = { it.id }) { review ->
                    UiCard(modifier = Modifier.testTag("review-${review.id}"), border = feelPalette(review.feel).border) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(review.location, style = MaterialTheme.typography.titleMedium)
                            Badge(review.feel, feelPalette(review.feel))
                        }
                        Text(review.comment, style = MaterialTheme.typography.bodyLarge, color = Ink)
                        Text(review.date, style = MaterialTheme.typography.labelMedium, color = Muted)
                    }
                }
            }
        }
        if (selectedLocation != null) {
            // タブの上の黒いボタン（Neo の「Review →」風）
            Surface(color = Paper, modifier = Modifier.fillMaxWidth()) {
                Surface(onClick = { showForm = true }, shape = RoundedCornerShape(14.dp), color = Ink,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp).fillMaxWidth().heightIn(min = 56.dp).testTag("community-post")) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("いまの体感を投稿する", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Icon(painterResource(R.drawable.ic_arrow_forward), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
    if (showForm) {
        ModalBottomSheet(onDismissRequest = { showForm = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Paper) {
            Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CenteredHeading("いまの体感をシェア", "どこで、どんなふうに感じた？", R.drawable.ic_nav_people)
                Selector("場所", location, model.rooms.map { it.name }) { location = it }
                Selector("体感", feel, listOf("快適", "少し寒い", "かなり寒い", "少し暑い")) { feel = it }
                TextField(comment, { comment = it }, label = { Text("ひとこと") },
                    placeholder = { Text("窓際は少しひんやり、など") }, supportingText = { Text("${comment.text.length} / 500文字") },
                    keyboardOptions = JapaneseKeyboardOptions, isError = comment.text.length > 500,
                    colors = TextFieldDefaults.colors(focusedContainerColor = Card, unfocusedContainerColor = Card, errorContainerColor = Card,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, errorIndicatorColor = Color.Transparent,
                        focusedLabelColor = Ink, cursorColor = Ink),
                    modifier = Modifier.fillMaxWidth(), minLines = 3, shape = RoundedCornerShape(16.dp))
                PrimaryButton("投稿する", enabled = comment.text.isNotBlank() && comment.text.length <= 500, onClick = {
                    model.postReview(location, feel, comment.text.trim())
                    comment = TextFieldValue()
                    showForm = false
                })
            }
        }
    }
}
