package jp.climafit.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jp.climafit.backend.Review
import jp.climafit.backend.Suggestions

@Composable
internal fun AiStatusScreen(model: LayeRoomViewModel, mode: AiMode, onBack: () -> Unit, onRetryPhoto: () -> Unit) {
    val request = model.latestAiRequest?.takeIf { it.mode == mode }
    val location = request?.location ?: model.roomName
    Column(Modifier.fillMaxSize().testTag(if (mode == AiMode.PHOTO) "photo-diagnosis-screen" else "suggestion-screen")
        .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp).background(Tile, RoundedCornerShape(12.dp))) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = "ホームに戻る", tint = Ink)
            }
            Text(if (mode == AiMode.PHOTO) "コーデ診断" else "${location}のおすすめ",
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.headlineSmall, color = Ink)
            Spacer(Modifier.width(48.dp))
        }
        UiCard(color = Ink) {  // Neo の残高カード風の黒いカード
            Text("${location} · 室温 ${formatTemp(request?.indoorTemperature ?: model.room.temperature)}℃", color = Color.White,
                style = MaterialTheme.typography.titleMedium)
            Text("金沢の気温 ${formatTemp(request?.outdoorTemperature ?: model.outdoorTemperature)}℃", color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium)
        }
        if (mode == AiMode.PHOTO) {
            val diagnosis = model.diagnosis
            when {
                model.photoError != null -> UiCard {
                    Text("写真を読み込めませんでした", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(model.photoError.orEmpty(), color = Muted)
                    PrimaryButton("写真を選び直す", onClick = onRetryPhoto)
                }
                model.loadingPhoto -> UiCard {
                    CircularProgressIndicator()
                    Text("写真を読み込み中…", fontWeight = FontWeight.Bold)
                }
                diagnosis != null -> DiagnosisResult(model, diagnosis)
                model.diagnosisError != null -> UiCard {
                    Text("診断できませんでした", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(model.diagnosisError.orEmpty(), color = Muted)
                    PrimaryButton("写真を選び直す", onClick = onRetryPhoto)
                }
                model.photo != null && request != null -> UiCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                        Text("コーデを確認しています", fontWeight = FontWeight.Bold)
                    }
                    Image(model.photo!!.asImageBitmap(), contentDescription = "選択した診断用の写真",
                        modifier = Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Fit)
                }
                else -> UiCard {
                    Text("診断する写真を選んでください。", color = Muted)
                    PrimaryButton("写真を選び直す", onClick = onRetryPhoto)
                }
            }
        } else if (model.connected) {
            val suggestion = model.suggestion
            when {
                model.suggestionError != null -> UiCard {
                    Text("おすすめを出せませんでした", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(model.suggestionError.orEmpty(), color = Muted)
                }
                // 提案が届いたらすぐ出す（上下を1枚にした画像は、読み込めたら差し替わる）
                suggestion != null -> SuggestionResult(model, suggestion)
                model.suggesting -> UiCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                        Text("おすすめを考えています", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            UiCard {
                CenteredHeading("今日のおすすめコーデ", "", R.drawable.ic_nav_sun)
                Image(painterResource(R.drawable.outfit_warm), contentDescription = "おすすめのコーデ写真",
                    modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Fit)
                Text(model.rooms.firstOrNull { it.name == location }?.recommendationDescription.orEmpty(), color = Muted)
                PrimaryButton("このコーデを保存", onClick = { /* サンプル表示では保存しない */ }, icon = R.drawable.ic_save)
            }
        }
    }
}

/** 写真診断の結果（バックエンド接続時）。 */
@Composable
private fun DiagnosisResult(model: LayeRoomViewModel, r: Review) {
    UiCard(modifier = Modifier.testTag("diagnosis-result")) {
        CenteredHeading(r.verdictJa, "必要な暖かさ ${r.needClo} / 今の服 ${r.outfitClo}", R.drawable.ic_check)
        Text(r.advice, style = MaterialTheme.typography.bodyMedium)
        Text(r.tip, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Ink)
        r.outdoor?.let { Text(it.verdictJa, style = MaterialTheme.typography.bodySmall, color = Muted) }
        if (r.notice.isNotBlank()) Text(r.notice, style = MaterialTheme.typography.labelSmall, color = Muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            r.items.forEachIndexed { i, item ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ClothingImage(model.diagnosisImages.getOrNull(i), item.detail)
                    Text(if (item.slot == "upper") "上" else "下", style = MaterialTheme.typography.labelSmall, color = Muted)
                    Text(item.detail, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
        SaveButton(saved = model.diagnosisSaved, busy = model.savingDiagnosis, onClick = model::saveDiagnosis)
    }
}

/** 場所別のおすすめ（バックエンド接続時）。保存した服からの提案、無ければ気温だけの提案。 */
@Composable
private fun SuggestionResult(model: LayeRoomViewModel, s: Suggestions) {
    val first = s.suggestions.firstOrNull()
    if (first != null) {
        UiCard(modifier = Modifier.testTag("suggestion-result")) {
            CenteredHeading("今日のおすすめコーデ", first.title, R.drawable.ic_nav_sun)
            ClothingImage(model.suggestionImage, "おすすめのコーデ写真", height = 300)
            when {
                model.renderingMannequin -> Text("マネキンの着用イメージを作成中です（1分ほどかかります）",
                    style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.testTag("mannequin-rendering"))
                model.suggestionIsMannequin -> Text("AIが作った着用イメージです",
                    style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.testTag("mannequin-done"))
                model.mannequinError != null -> Text(model.mannequinError.orEmpty(),
                    style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.testTag("mannequin-error"))
            }
            Text(first.description, color = Muted)
            if (s.preference.isNotBlank()) Text(s.preference, style = MaterialTheme.typography.labelSmall, color = Muted)
            SaveButton(saved = model.suggestionSaved, busy = false, onClick = model::saveSuggestion)
        }
    } else {
        UiCard(modifier = Modifier.testTag("suggestion-result")) {
            CenteredHeading("今日のおすすめコーデ", "気温から選んだ定番の組み合わせ", R.drawable.ic_nav_sun)
            s.general.forEach { g ->
                Text(g.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(g.description, color = Muted)
            }
            Text(s.tip, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Ink)
            if (s.message.isNotBlank()) Text(s.message, style = MaterialTheme.typography.labelSmall, color = Muted)
        }
    }
}

@Composable
private fun ClothingImage(image: Bitmap?, description: String, height: Int = 140) {
    val modifier = Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(12.dp)).background(Tile)
    if (image != null) Image(image.asImageBitmap(), contentDescription = description, modifier = modifier, contentScale = ContentScale.Fit)
    else Image(painterResource(R.drawable.ic_image), contentDescription = description, modifier = modifier, contentScale = ContentScale.Inside)
}

@Composable
private fun SaveButton(saved: Boolean, busy: Boolean, onClick: () -> Unit) {
    // 赤い丸ボタン（保存したらグレーになり、チェックが付く）
    PrimaryButton(if (saved) "保存しました" else "このコーデを保存", onClick = onClick, enabled = !saved && !busy,
        icon = if (saved) R.drawable.ic_check else R.drawable.ic_save, modifier = Modifier.testTag("save-outfit"))
}
