package jp.climafit.app

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------- 色（Neo 風。薄いグレーの背景に白いカード、白黒が中心） ----------
internal val Paper = Color(0xFFF0F1F0)          // 画面の背景
internal val Card = Color(0xFFFFFFFF)           // カード
internal val Tile = Color(0xFFE4E5E5)           // アイコンの入れ物・グレーのボタン・入力欄
internal val Ink = Color(0xFF0E0F0F)
internal val Muted = Color(0xFF777B7A)          // 見出しラベル・説明のグレー
internal val Faint = Color(0xFFA6AAA9)          // 選ばれていないタブ
internal val Accent = Color(0xFF0E0F0F)         // 主な操作は黒
internal val Positive = Color(0xFF3E9C6B)       // 小さな緑の点など
// 気温・体感の色分け（意味は従来どおり。22℃未満=水色系、それ以上=オレンジ系、快適=緑系）。落ち着いた色にする
internal val Orange = Color(0xFFA4511F)
internal val OrangeSoft = Color(0xFFF6E9DF)
internal val OrangeLine = Color(0xFFE5C4AA)
internal val Blue = Color(0xFF2E5E80)
internal val BlueSoft = Color(0xFFE3ECF2)
internal val BlueLine = Color(0xFFB8CDDB)
internal val Green = Color(0xFF2F7351)
internal val GreenSoft = Color(0xFFE2EFE7)
internal val Outline = Color(0xFFE6E7E7)        // 区切り線

internal data class ClimatePalette(val background: Color, val foreground: Color, val border: Color)
// Presentation colors only; this does not calculate comfort or clothing recommendations.
internal fun temperaturePalette(temperature: Double) = if (temperature < 22.0)
    ClimatePalette(BlueSoft, Blue, BlueLine) else ClimatePalette(OrangeSoft, Orange, OrangeLine)
internal fun feelPalette(feel: String) = when {
    "寒" in feel -> ClimatePalette(BlueSoft, Blue, BlueLine)
    "暑" in feel -> ClimatePalette(OrangeSoft, Orange, OrangeLine)
    else -> ClimatePalette(GreenSoft, Green, Color(0xFFB7D6C3))
}

@Composable
internal fun LayeRoomTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = Accent, onPrimary = Color.White, primaryContainer = Tile,
        onPrimaryContainer = Ink, secondary = Blue, secondaryContainer = BlueSoft,
        onSecondaryContainer = Blue, background = Paper, surface = Paper,
        onSurface = Ink, onSurfaceVariant = Muted, outline = Outline, outlineVariant = Outline,
        surfaceVariant = Tile, tertiary = Green,
        surfaceContainerLowest = Card, surfaceContainerLow = Card, surfaceContainer = Tile,
        surfaceContainerHigh = Color(0xFFDCDDDD), surfaceContainerHighest = Color(0xFFD4D5D5),
    ), shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(24.dp),
    ), typography = Typography(
        // すっきりした太字の見出し（「Welcome,」）と、落ち着いた本文
        headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
        headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
        headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
        titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
        bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
    ), content = content)
}

/**
 * カード。白で角丸、影も枠線もなし（Neo のカード風）。
 * 色付き（BlueSoft など）は面として使う。体感の色など border を明示したときだけ、細い色の線を引く。
 */
@Composable
internal fun UiCard(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    border: Color = Outline,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = color,
        border = if (border != Outline && border != BlueLine) BorderStroke(1.dp, border.copy(alpha = 0.8f)) else null) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** 小さなラベル（気温・体感など）。 */
@Composable
internal fun Badge(text: String, palette: ClimatePalette = ClimatePalette(OrangeSoft, Orange, OrangeLine)) {
    Surface(color = palette.background, shape = RoundedCornerShape(8.dp)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = palette.foreground)
    }
}

/** プルダウン（Neo の「From」欄風：灰色のラベルの下に、白い行と ⌄）。 */
@Composable
internal fun Selector(label: String, selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.bodyMedium, color = Muted)
        Box {
            Surface(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Card) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(selected, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = Ink)
                    Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, tint = Muted, modifier = Modifier.size(22.dp))
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = RoundedCornerShape(14.dp), containerColor = Card) {
                options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onSelect(option) }) }
            }
        }
    }
}

/** アイコンの入ったグレーの角丸の四角（Neo の一覧のアイコン風）。 */
@Composable
internal fun SymbolBadge(@DrawableRes symbol: Int, color: Color = Ink, background: Color = Tile) {
    Surface(shape = RoundedCornerShape(10.dp), color = background) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(symbol), contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        }
    }
}

/** 「写真を選ぶ →」のような操作の案内。 */
@Composable
internal fun ActionLabel(label: String, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = color)
        Icon(painterResource(R.drawable.ic_arrow_forward), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
    }
}

/**
 * シートや結果画面の見出し（Neo の「Send money」風）。グレーの四角のアイコン、太字の見出し、灰色の説明を中央にそろえる。
 */
@Composable
internal fun CenteredHeading(title: String, subtitle: String, @DrawableRes symbol: Int,
    color: Color = Ink, background: Color = Tile) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SymbolBadge(symbol, color, background)
        Spacer(Modifier.height(2.dp))
        Text(title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall, color = Ink, textAlign = TextAlign.Center)
        if (subtitle.isNotBlank()) Text(subtitle, Modifier.fillMaxWidth(), color = Muted,
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

/** 一覧の上の灰色の小さな見出し（「Credit cards」「Cash」風）。trailing は右端の灰色の文字（「$5.05」風）。 */
@Composable
internal fun SectionHeader(title: String, subtitle: String = "", trailing: String = "") {
    Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = Muted)
            if (trailing.isNotBlank()) Text(trailing, style = MaterialTheme.typography.bodyLarge, color = Muted)
        }
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Faint)
    }
}

/** 黒い角丸のボタン（「Review →」風）。 */
@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    @DrawableRes icon: Int? = null, arrow: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White,
            disabledContainerColor = Tile, disabledContentColor = Faint),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (arrow && icon == null) {
            Spacer(Modifier.width(8.dp))
            Icon(painterResource(R.drawable.ic_arrow_forward), contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

/** グレーの角丸のボタン（「+ Add products」風）。 */
@Composable
internal fun PlainButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null) {
    Button(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Tile, contentColor = Ink)) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
    }
}

/**
 * 右上のグレーの角丸のボタン（Neo の人のアイコン風）。
 * 登録したら名前の頭文字（黒い丸）、未登録なら歯車（案内文の「右上の歯車」）。
 */
@Composable
internal fun Avatar(name: String?, modifier: Modifier = Modifier) {
    Box(modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Tile), contentAlignment = Alignment.Center) {
        val initial = name?.trim()?.takeIf { it.isNotEmpty() }?.let { it.substring(0, it.offsetByCodePoints(0, 1)) }
        if (initial != null) {
            Box(Modifier.size(32.dp).background(Ink, CircleShape), contentAlignment = Alignment.Center) {
                Text(initial.uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        } else {
            Icon(painterResource(R.drawable.ic_settings), contentDescription = null, tint = Ink, modifier = Modifier.size(24.dp))
        }
    }
}
