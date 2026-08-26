package dev.lulitech.jpkisigner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.lulitech.jpkisigner.R
import java.time.Year

/**
 * Version, authorship and the notices we are obliged to carry.
 *
 * The reference implementation is under an MIT-equivalent licence, which requires
 * its copyright notice to accompany substantial portions of the work. We ported
 * its PDF and CMS construction, so that notice belongs here — this screen exists
 * as much to discharge that obligation as to show a version number.
 */
@Composable
fun AboutDialog(
    versionName: String,
    onShowNotices: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about_title)) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Section(
                    heading = stringResource(R.string.app_name),
                    body = stringResource(R.string.about_version, versionName),
                    // The source, under the name of the thing it is the source
                    // of. An offline app that asks for a PIN has to be checkable
                    // by whoever is being asked, and a link they can follow is
                    // the only way this screen offers to do that.
                    link = stringResource(R.string.about_repository),
                )
                Section(
                    heading = stringResource(R.string.about_author_heading),
                    body = stringResource(R.string.about_copyright, copyrightYears()),
                    link = stringResource(R.string.about_homepage),
                )
                Section(
                    heading = stringResource(R.string.about_license_heading),
                    body = stringResource(R.string.about_license),
                )
                Section(
                    heading = stringResource(R.string.about_based_on_heading),
                    body = stringResource(R.string.about_based_on),
                )
                Section(
                    heading = stringResource(R.string.about_libraries_heading),
                    body = stringResource(R.string.about_libraries),
                )
                Section(
                    heading = stringResource(R.string.about_privacy_heading),
                    body = stringResource(R.string.about_privacy),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
        dismissButton = {
            TextButton(onClick = onShowNotices) {
                Text(stringResource(R.string.about_notices))
            }
        },
    )
}

/**
 * The copyright year, as a single year until the calendar moves on and a range
 * afterwards: "2026" during 2026, "2026-2028" during 2028.
 *
 * [currentYear] comes from the device clock, so a wrong clock shows a wrong
 * range -- harmless, and preferable to a year frozen at build time that silently
 * goes stale.
 */
internal fun copyrightYears(
    firstYear: Int = FIRST_PUBLICATION_YEAR,
    currentYear: Int = Year.now().value,
): String = if (currentYear <= firstYear) "$firstYear" else "$firstYear-$currentYear"

private const val FIRST_PUBLICATION_YEAR = 2026

@Composable
private fun Section(heading: String, body: String, link: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(heading, style = MaterialTheme.typography.titleSmall)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (link != null) {
            val uriHandler = LocalUriHandler.current
            Text(
                text = link,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                // Opening a URL hands off to the browser through ACTION_VIEW, so
                // it needs no INTERNET permission and this app still never
                // connects to anything itself.
                //
                // runCatching because openUri throws when nothing can handle the
                // intent, and an About dialog must not be able to crash the app.
                modifier = Modifier.clickable { runCatching { uriHandler.openUri(link) } },
            )
        }
    }
}
