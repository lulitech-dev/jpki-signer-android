package dev.lulitech.jpkisigner.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import dev.lulitech.jpkisigner.R
import dev.lulitech.jpkisigner.data.SignatureRow
import dev.lulitech.jpkisigner.data.SignatureRows
import dev.lulitech.jpkisigner.pdf.SignatureIntegrity
import kotlinx.coroutines.launch
import java.text.DateFormat
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Screen 2: one document's signature history.
 *
 * Ordered oldest first, so the list reads forward in time like a message thread,
 * which makes "everything after this" map onto "below" and match the cascade
 * motion.
 *
 * Every signature is removable, whoever made it: revision boundaries come from
 * the PDF, not from local records. The rare exception is a signature whose
 * ByteRange does not reach its revision end, where truncating would corrupt the
 * file.
 *
 * @param onDeleteCascade suspends until the truncation has landed and the
 *   reloaded detail has been published. The swept rows are held off screen until
 *   it returns: resetting the offset while [detail] still held the old rows
 *   snapped them back into view for as long as the re-read took, so a cascade
 *   delete looked like it had been reverted and then applied again.
 */
@Composable
fun DocumentScreen(
    detail: DocumentDetailUi,
    onSign: () -> Unit,
    onShare: (exportName: String) -> Unit,
    onDeleteCascade: suspend (truncateTo: Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pending by remember { mutableStateOf<SignatureRow?>(null) }
    var namingExport by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Both gestures live on the horizontal axis and are told apart by the
    // direction of the first movement, decided once per gesture so a wobble
    // mid-drag cannot flip the meaning underneath the user.
    //
    // Left  -> remove this signature and every later one. Rows from the anchor
    //          down move as one, because those signatures really are one
    //          indivisible unit: signature n+1 signed the bytes containing
    //          signature n. The motion is the explanation.
    // Right -> go back to the document list. The whole screen follows the
    //          finger, which is what makes it read as navigation rather than
    //          as an action on a row.
    var anchor by remember { mutableStateOf<Int?>(null) }
    var goingBack by remember { mutableStateOf(false) }
    val dragOffset = remember { Animatable(0f) }
    val backOffset = remember { Animatable(0f) }

    // Where the finger has put each offset, accumulated synchronously here on the
    // main thread. `Animatable.snapTo` suspends, so accumulating inside the
    // coroutine made every delta a read-modify-write racing the next one, and
    // deltas went missing mid-drag. The coroutine now only pushes an already
    // decided value.
    //
    // These, not the Animatables, are what the thresholds below are measured
    // against. A `snapTo` launched from a drag event runs on the next frame, so
    // when the gesture ends the animated value can still be a frame behind the
    // finger -- and a fast flick was judged against an offset it had already gone
    // past, springing back instead of offering the confirmation.
    var dragTarget by remember { mutableFloatStateOf(0f) }
    var backTarget by remember { mutableFloatStateOf(0f) }

    val commitThresholdPx = with(density) { COMMIT_THRESHOLD.toPx() }
    val rubberBandPx = with(density) { RUBBER_BAND_LIMIT.toPx() }
    val backThresholdPx = with(density) { BACK_THRESHOLD.toPx() }
    // Past the edge of the window, whatever the window is. This was a fixed 2000px,
    // which is generous on a phone and short of the edge on a tablet in landscape,
    // leaving the departing row parked in view.
    val sweepOffPx = LocalWindowInfo.current.containerSize.width +
        with(density) { SWEEP_MARGIN.toPx() }

    fun settle() {
        dragTarget = 0f
        backTarget = 0f
        scope.launch {
            dragOffset.animateTo(0f, tween(SETTLE_MILLIS))
            backOffset.animateTo(0f, tween(SETTLE_MILLIS))
            anchor = null
            goingBack = false
        }
    }

    /** Routes a horizontal delta to whichever gesture is in progress. */
    fun onHorizontalDrag(delta: Float, rowPosition: Int?, removable: Boolean) {
        if (anchor == null && !goingBack) {
            if (delta > 0f) goingBack = true else anchor = rowPosition ?: return
        }
        if (goingBack) {
            backTarget = (backTarget + delta).coerceAtLeast(0f)
            scope.launch { backOffset.snapTo(backTarget) }
        } else {
            val limit = if (removable) Float.MAX_VALUE else rubberBandPx
            dragTarget = (dragTarget + delta).coerceIn(-limit, 0f)
            scope.launch { dragOffset.snapTo(dragTarget) }
        }
    }

    /** @return true when the gesture was consumed as a back navigation. */
    fun finishBackGesture(): Boolean {
        if (!goingBack) return false
        if (backTarget >= backThresholdPx) {
            scope.launch {
                backOffset.animateTo(sweepOffPx, tween(SETTLE_MILLIS))
                onBack()
                backTarget = 0f
                backOffset.snapTo(0f)
                goingBack = false
            }
        } else {
            settle()
        }
        return true
    }

    // The newest signature and the actions are both at the bottom.
    LaunchedEffect(detail.rows.size) {
        if (detail.rows.isNotEmpty()) listState.scrollToItem(detail.rows.lastIndex)
    }

    Column(
        modifier
            .fillMaxSize()
            .offset { IntOffset(backOffset.value.roundToInt(), 0) }
            // Drags that start off a row -- the header, the empty state, the
            // button strip -- still navigate back.
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    onHorizontalDrag(delta, rowPosition = null, removable = false)
                },
                onDragStopped = { if (!finishBackGesture()) settle() },
            ),
    ) {
        Text(
            detail.displayName,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp),
        )

        if (detail.rows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                // "No signatures yet" is a claim about the document, and it can
                // only be made about one we actually read. A file we failed to
                // parse gets the same empty list and must not borrow the same
                // sentence.
                Text(
                    stringResource(
                        if (detail.unreadable) {
                            R.string.document_unreadable
                        } else {
                            R.string.document_no_signatures
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (detail.unreadable) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(detail.rows, key = { _, row -> row.position }) { _, row ->
                    val cascade = SignatureRows.cascadeCount(detail.rows, row)
                    val follows = anchor?.let { row.position >= it } == true

                    CascadeRow(
                        row = row,
                        cascadeCount = cascade,
                        // Rows at or below the dragged one move as one.
                        offsetPx = if (follows) dragOffset.value else 0f,
                        onDrag = { delta ->
                            onHorizontalDrag(delta, row.position, row.isRemovable)
                        },
                        onDragStopped = {
                            if (finishBackGesture()) return@CascadeRow
                            val far = abs(dragTarget) >= commitThresholdPx
                            if (far && row.isRemovable) {
                                // Hold the rows at their dragged offset while the
                                // dialog decides. Animating them away first would
                                // make a cancel look like the delete happened and
                                // then reverted.
                                pending = row
                            } else {
                                settle()
                            }
                        },
                        onMenuDelete = { pending = row },
                    )
                }
            }
        }

        // Bytes exist that no signature covers. Unlike the per-row line this
        // removed, this is genuinely exceptional: a clean append-only chain
        // always ends with its newest signature reaching end-of-file.
        if (detail.rows.isNotEmpty() && !detail.rows.last().info.coversWholeDocument) {
            Text(
                stringResource(R.string.document_trailing_changes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = { namingExport = true }, modifier = Modifier.weight(1f)) {
                Icon(
                    Icons.Filled.Share,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.document_share))
            }
            Button(onClick = onSign, modifier = Modifier.weight(1f)) {
                Icon(
                    painterResource(R.drawable.ic_sign),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.document_sign))
            }
        }
    }

    if (namingExport) {
        ExportNameDialog(
            storedName = detail.displayName,
            onConfirm = { chosen ->
                namingExport = false
                onShare(chosen)
            },
            onDismiss = { namingExport = false },
        )
    }

    pending?.let { row ->
        val cascade = SignatureRows.cascadeCount(detail.rows, row)
        val displayNumber = row.position + 1
        AlertDialog(
            onDismissRequest = {
                pending = null
                settle()
            },
            title = { Text(stringResource(R.string.signature_delete_title)) },
            text = {
                Text(
                    // The motion already showed this; the dialog states it in words.
                    if (cascade > 1) {
                        pluralStringResource(
                            R.plurals.signature_delete_body_cascade,
                            cascade - 1,
                            displayNumber,
                            cascade - 1,
                        )
                    } else {
                        stringResource(R.string.signature_delete_body_single, displayNumber)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = row.truncateTo
                    pending = null
                    if (target != null) {
                        scope.launch {
                            // Carry the whole suffix off screen, then commit, and
                            // only reset once the new row list is in hand -- see
                            // onDeleteCascade.
                            if (anchor == null) anchor = row.position
                            dragOffset.animateTo(-sweepOffPx, tween(SETTLE_MILLIS))
                            onDeleteCascade(target)
                            dragTarget = 0f
                            dragOffset.snapTo(0f)
                            anchor = null
                        }
                    } else {
                        settle()
                    }
                }) { Text(stringResource(R.string.signature_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pending = null
                    settle()
                }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/**
 * One signature row.
 *
 * Its horizontal position is dictated by the caller, not by its own gesture, so
 * that a drag on any row can move this one too. The long-press menu and the
 * accessibility action are [DeletableSurface]'s, shared with the document list --
 * they are the same affordances, and keeping two copies of them is how one of
 * them once ended up with its guard inverted.
 */
@Composable
private fun CascadeRow(
    row: SignatureRow,
    cascadeCount: Int,
    offsetPx: Float,
    onDrag: (Float) -> Unit,
    onDragStopped: () -> Unit,
    onMenuDelete: () -> Unit,
) {
    val accessibilityLabel = if (cascadeCount > 1) {
        pluralStringResource(
            R.plurals.a11y_delete_signature_cascade,
            cascadeCount - 1,
            row.position + 1,
            cascadeCount - 1,
        )
    } else {
        stringResource(R.string.a11y_delete_signature, row.position + 1)
    }

    Box {
        // Revealed as the row slides away.
        RevealLabel(
            deletable = row.isRemovable,
            deleteLabel = stringResource(R.string.signature_delete_confirm),
            blockedLabel = stringResource(R.string.signature_not_removable),
            modifier = Modifier.matchParentSize(),
        )

        DeletableSurface(
            deletable = row.isRemovable,
            deleteLabel = stringResource(R.string.signature_delete_confirm),
            accessibilityLabel = accessibilityLabel,
            onDeleteRequested = onMenuDelete,
            modifier = Modifier
                .offset { IntOffset(offsetPx.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta -> onDrag(delta) },
                    onDragStopped = { onDragStopped() },
                ),
        ) {
            SignatureCard(row)
        }
    }
}

@Composable
private fun SignatureCard(row: SignatureRow) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.signature_position, row.position + 1),
                style = MaterialTheme.typography.titleSmall,
            )
            row.info.signedAt?.let {
                Text(
                    DateFormat.getDateTimeInstance().format(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            row.info.signerCommonName?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            // Only speak up when something is wrong.
            //
            // A success message here would be worth little and cost something: a
            // user cannot act on "this checked out", and any positive statement
            // invites being read as *our* confirmation of the signature. What we
            // can establish offline is narrow -- the CMS matches the bytes it
            // covers -- and says nothing about the certificate being valid or
            // unrevoked, which needs network. Confirming a signature is the
            // 法務省 plugin's job, so the app does not imply it.
            //
            // UNCHECKED is not a finding about the document and is not coloured
            // as one. A signature this build cannot verify -- the verifier is
            // RSA-only -- used to borrow the mismatch wording and tell the owner
            // of an intact PDF that their file was damaged.
            when (row.info.integrity) {
                SignatureIntegrity.OK -> Unit
                SignatureIntegrity.MISMATCH -> Text(
                    stringResource(R.string.signature_integrity_mismatch),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                SignatureIntegrity.UNCHECKED -> Text(
                    stringResource(R.string.signature_integrity_unchecked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!row.isRemovable) {
                Text(
                    stringResource(R.string.signature_not_removable),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** How far the suffix must travel before the confirmation is offered. */
private val COMMIT_THRESHOLD = 120.dp

/** Rows that cannot be removed give this far and no further, then spring back. */
private val RUBBER_BAND_LIMIT = 48.dp

/** How far right the screen must travel to count as going back. */
private val BACK_THRESHOLD = 96.dp

/** Cleared beyond the window edge, so nothing is left clinging to it. */
private val SWEEP_MARGIN = 48.dp

private const val SETTLE_MILLIS = 200
