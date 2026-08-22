package dev.lulitech.jpkisigner.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A row that can be deleted by swiping, long-pressing, or an accessibility action.
 *
 * Swipe alone is not enough. It is undiscoverable without an affordance, and a
 * destructive action reachable only by a drag gesture excludes TalkBack users and
 * anyone with limited motor control. All three paths lead to the same
 * confirmation, which the caller owns.
 *
 * @param deletable false for rows that cannot be removed. The swipe then
 *   rubber-bands back and the background explains why, rather than the gesture
 *   being silently ignored -- the gesture is how the constraint is learned.
 * @param accessibilityLabel spoken description of the delete action. Should state
 *   the consequence, including how many other rows go with it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeletableRow(
    deletable: Boolean,
    deleteLabel: String,
    blockedLabel: String,
    accessibilityLabel: String,
    onDeleteRequested: () -> Unit,
    content: @Composable () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    // confirmValueChange always returns false: the row never dismisses itself.
    // It is held at the swiped offset while the caller's dialog decides, and
    // springs back on cancel. Animating it away first would make a cancel look
    // like the delete happened and then reverted.
    val swipeState = rememberSwipeToDismissBoxStateCompat(
        onSwiped = {
            if (deletable) {
                onDeleteRequested()
            } else {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        },
    )

    SwipeToDismissBox(
        state = swipeState,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = if (deletable) deleteLabel else blockedLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (deletable) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
    ) {
        Box(
            Modifier
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        if (deletable) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = true
                        }
                    },
                )
                // Exposed to TalkBack and other services as a named action, so
                // deletion never depends on performing a drag.
                .semantics {
                    if (deletable) {
                        customActions = listOf(
                            CustomAccessibilityAction(accessibilityLabel) {
                                onDeleteRequested()
                                true
                            },
                        )
                    }
                },
        ) {
            content()

            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(deleteLabel) },
                    onClick = {
                        menuOpen = false
                        onDeleteRequested()
                    },
                )
            }
        }
    }
}

@Composable
private fun rememberSwipeToDismissBoxStateCompat(onSwiped: () -> Unit) =
    androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) onSwiped()
            false
        },
    )
