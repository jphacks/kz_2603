package jp.climafit.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * ユーザー登録。入力はシートの中だけで持ち、閉じると消える（登録はバックエンド接続時だけ）。
 * required = true（未登録のまま起動したとき）は、登録するまで閉じられない（閉じるボタン・スワイプ・戻るを無効にする）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RegistrationSheet(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    currentName: String? = null,
    required: Boolean = false,
    busy: Boolean = false,
    error: String? = null,
) {
    var name by remember { mutableStateOf(TextFieldValue()) }
    val ready = name.text.isNotBlank() && !busy
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { !required || it != SheetValue.Hidden })

    ModalBottomSheet(onDismissRequest = { if (!required) onDismiss() }, sheetState = sheetState,
        containerColor = Paper, properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !required)) {
        // Neo の「Send money」風：中央の見出し → 灰色のラベルと入力 → 黒いボタン → グレーのボタン
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 24.dp).testTag("registration-sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CenteredHeading("ユーザー登録",
                if (required) "はじめに、ユーザー名を登録してください。" else "あなたの名前で、今日のひろばへ。", R.drawable.ic_person)
            if (currentName != null) {
                Text("登録中：$currentName", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                    color = Muted, textAlign = TextAlign.Center)
            }
            TextField(name, { name = it }, label = { Text("ユーザー名") }, singleLine = true,
                leadingIcon = { Icon(painterResource(R.drawable.ic_person), contentDescription = null) },
                shape = RoundedCornerShape(14.dp), keyboardOptions = JapaneseKeyboardOptions,
                colors = TextFieldDefaults.colors(focusedContainerColor = Card, unfocusedContainerColor = Card,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedLabelColor = Ink, cursorColor = Ink, focusedLeadingIconColor = Ink),
                modifier = Modifier.fillMaxWidth().testTag("registration-name"))
            if (error != null) {
                Text(error, Modifier.fillMaxWidth().testTag("registration-error"), style = MaterialTheme.typography.bodySmall,
                    color = Muted, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(6.dp))
            PrimaryButton(if (busy) "登録しています…" else "登録する", onClick = { onSubmit(name.text.trim()) }, enabled = ready,
                modifier = Modifier.testTag("registration-submit"))
            if (!required) PlainButton("閉じる", onClick = onDismiss, icon = R.drawable.ic_close)
        }
    }
}
