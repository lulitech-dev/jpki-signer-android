package dev.lulitech.jpkisigner.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import dev.lulitech.jpkisigner.R
import dev.lulitech.jpkisigner.data.SignatureRow
import dev.lulitech.jpkisigner.data.SignatureRows
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
 */
@Composable
fun DocumentScreen(
    detail: DocumentDetailUi,
    onSign: () -> Unit,
    onShare: (exportName: String) -> Unit,
    onDeleteCascade: (truncateTo: Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pending by remember { mutableStateOf<SignatureRow?>(null) }
    var namingExport by remember { mutableStateOf(false) }
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

    val commitThresholdPx = with(density) { COMMIT_THRESHOLD.toPx() }
    val rubberBandPx = with(density) { RUBBER_BAND_LIMIT.toPx() }
    val backThresholdPx = with(density) { BACK_THRESHOLD.toPx() }

    fun settle() = scope.launch {
        dragOffset.animateTo(0f, tween(SETTLE_MILLIS))
        backOffset.animateTo(0f, tween(SETTLE_MILLIS))
        anchor = null
        goingBack = false
    }

    /** Routes a horizontal delta to whichever gesture is in progress. */
    fun onHorizontalDrag(delta: Float, rowPosition: Int?, removable: Boolean) {
        if (anchor == null && !goingBack) {
            if (delta > 0f) goingBack = true else anchor = rowPosition ?: return
        }
        scope.launch {
            if (goingBack) {
                backOffset.snapTo((backOffset.value + delta).coerceAtLeast(0f))
            } else {
                val limit = if (removable) Float.MAX_VALUE else rubberBandPx
                dragOffset.snapTo((dragOffset.value + delta).coerceIn(-limit, 0f))
            }
        }
    }

    /** @return true when the gesture was consumed as a back navigation. */
    fun finishBackGesture(): Boolean {
        if (!goingBack) return false
        if (backOffset.value >= backThresholdPx) {
            scope.launch {
                backOffset.animateTo(SWEEP_OFF_PX, tween(SETTLE_MILLIS))
                onBack()
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
                Text(
                    stringResource(R.string.document_no_signatures),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            val far = abs(dragOffset.value) >= commitThresholdPx
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
        if (detail.rows.isNotEmpty() && detail.rows.last().info.coversWholeDocument.not()) {
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
                        stringResource(
                            R.string.signature_delete_body_cascade,
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
                            // Carry the whole suffix off screen, then commit.
                            if (anchor == null) anchor = row.position
                            dragOffset.animateTo(-SWEEP_OFF_PX, tween(SETTLE_MILLIS))
                            onDeleteCascade(target)
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
 * that a drag on any row can move this one too.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CascadeRow(
    row: SignatureRow,
    cascadeCount: Int,
    offsetPx: Float,
    onDrag: (Float) -> Unit,
    onDragStopped: () -> Unit,
    onMenuDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    val accessibilityLabel = if (cascadeCount > 1) {
        stringResource(
            R.string.a11y_delete_signature_cascade,
            row.position + 1,
            cascadeCount - 1,
        )
    } else {
        stringResource(R.string.a11y_delete_signature, row.position + 1)
    }

    Box {
        // Revealed as the row slides away.
        Box(
            Modifier.matchParentSize().padding(horizontal = 20.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                if (!row.isRemovable) {
                    stringResource(R.string.signature_not_removable)
                } else {
                    stringResource(R.string.signature_delete_confirm)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (!row.isRemovable) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }

        Box(
            Modifier
                .offset { IntOffset(offsetPx.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta -> onDrag(delta) },
                    onDragStopped = { onDragStopped() },
                )
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        if (!row.isRemovable) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = true
                        }
                    },
                )
                // Deletion must not depend on performing a drag.
                .semantics {
                    if (!row.isRemovable) {
                        customActions = listOf(
                            CustomAccessibilityAction(accessibilityLabel) {
                                onMenuDelete()
                                true
                            },
                        )
                    }
                },
        ) {
            SignatureCard(row)

            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.signature_delete_confirm)) },
                    onClick = {
                        menuOpen = false
                        onMenuDelete()
                    },
                )
            }
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
            if (!row.info.integrityOk) {
                Text(
                    stringResource(R.string.signature_integrity_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
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

private const val SETTLE_MILLIS = 200
private const val SWEEP_OFF_PX = 2000f
