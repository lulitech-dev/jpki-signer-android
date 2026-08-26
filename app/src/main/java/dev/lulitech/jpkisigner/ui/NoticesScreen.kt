package dev.lulitech.jpkisigner.ui

import androidx.annotation.RawRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.lulitech.jpkisigner.R

/**
 * Full licence texts for this app and everything it is built on.
 *
 * A list of names is not enough. The reference implementation's licence requires
 * its copyright notice *and its permission notice* to accompany substantial
 * portions of the work, and we ported its PDF and CMS construction — so the
 * text itself has to ship, not a reference to it.
 *
 * Texts are raw resources rather than strings, so they are stored byte for byte
 * as published, with no XML escaping to garble them.
 */
@Composable
fun NoticesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val notices = remember {
        listOf(
            Notice(R.string.app_name, R.string.notice_app_subtitle, R.raw.license_app),
            Notice(
                R.string.notice_reference_heading,
                R.string.notice_reference_subtitle,
                R.raw.notice_jpki_pdf_signer,
            ),
            Notice(
                R.string.notice_bouncycastle_heading,
                R.string.notice_bouncycastle_subtitle,
                R.raw.notice_bouncy_castle,
            ),
            Notice(
                R.string.notice_apache_heading,
                R.string.notice_apache_subtitle,
                R.raw.notice_apache_2_0,
            ),
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(notices) { notice ->
            val text = remember(notice.textRes) { context.readRaw(notice.textRes) }
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        stringResource(notice.headingRes),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(notice.subtitleRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = text,
                        // Monospace: these texts are laid out with hard line
                        // breaks and indentation that a proportional font mangles.
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private data class Notice(
    val headingRes: Int,
    val subtitleRes: Int,
    @RawRes val textRes: Int,
)

private fun android.content.Context.readRaw(@RawRes id: Int): String =
    resources.openRawResource(id).bufferedReader().use { it.readText() }
