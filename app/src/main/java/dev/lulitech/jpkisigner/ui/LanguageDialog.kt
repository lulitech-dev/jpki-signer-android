package dev.lulitech.jpkisigner.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import dev.lulitech.jpkisigner.R

/**
 * Picks the app's language, independently of the system setting.
 *
 * Uses `AppCompatDelegate.setApplicationLocales` rather than the platform
 * `LocaleManager`, because the platform API only exists from API 33 and minSdk
 * here is 26. That is also why the activity extends `AppCompatActivity` and the
 * manifest declares `AppLocalesMetadataHolderService` with `autoStoreLocales`:
 * below 33 AppCompat has to persist the choice itself.
 *
 * The "follow the system" option matters. Without it a user who picks a language
 * once can never hand the decision back, and their app would stay pinned to a
 * language even after they change their phone's.
 */
@Composable
fun LanguageDialog(onDismiss: () -> Unit) {
    val current = AppCompatDelegate.getApplicationLocales()
    val currentTag = current.takeIf { !it.isEmpty }?.get(0)?.language

    // Follow-the-system first, since it is the default rather than a language,
    // then the languages sorted by their own label. Sorting here rather than
    // hardcoding the order means adding a language needs no thought about where
    // it belongs: "Deutsch" lands before "English", 中文 between English and
    // 日本語, because that is where their code points fall.
    val options = listOf(null to stringResource(R.string.language_system)) +
        listOf(
            "en" to stringResource(R.string.language_english),
            "ja" to stringResource(R.string.language_japanese),
        ).sortedBy { (_, label) -> label }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { (tag, label) ->
                    val selected = tag == currentTag
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                onClick = {
                                    // Recreates the activity, so the dialog is
                                    // dismissed first to avoid it reappearing.
                                    onDismiss()
                                    AppCompatDelegate.setApplicationLocales(
                                        if (tag == null) {
                                            LocaleListCompat.getEmptyLocaleList()
                                        } else {
                                            LocaleListCompat.forLanguageTags(tag)
                                        },
                                    )
                                },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}
