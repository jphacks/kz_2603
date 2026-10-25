package jp.climafit.app

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.intl.LocaleList

// Used by the community form. The AI feature deliberately has no text input.
internal val JapaneseKeyboardOptions = KeyboardOptions(
    keyboardType = KeyboardType.Text,
    imeAction = ImeAction.Default,
    hintLocales = LocaleList("ja"),
)
