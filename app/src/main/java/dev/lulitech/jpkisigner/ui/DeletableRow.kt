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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The label revealed behind a row as it slides away.
 *
 * @param deletable false for rows that cannot be removed, which show
 *   [blockedLabel] instead. The gesture is how that constraint is learned, so it
 *   has to answer rather than be silently ignored.
 */
@Composable
fun RevealLabel(
    deletable: Boolean,
    deleteLabel: String,
    blockedLabel: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .padding(horizontal = 20.dp)
            // Out of the semantics tree. This label answers a drag, and a screen
            // reader cannot drag: it sits behind every row permanently, so
            // exposing it made TalkBack read "delete" or "cannot be removed" on
            // every row in the list. What it says is available to a screen reader
            // anyway -- as the named delete action when the row is removable, and
            // as a line on the card when it is not.
            .clearAndSetSemantics {},
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
}

/**
 * Everything about "this row can be deleted" that is not a gesture: the
 * long-press menu and the named accessibility action.
 *
 * Deletion must not depend on performing a drag -- a swipe is undiscoverable
 * without an affordance, and a destructive action reachable only by dragging
 * excludes TalkBack users and anyone with limited motor control. All three paths
 * lead to the same confirmation, which the caller owns.
 *
 * Separate from the gestures because the two lists drag differently: a document
 * row swipes itself away, while a signature row is moved by whichever row of its
 * suffix the finger is on. Everything else here was written twice, and the guard
 * on one copy had already been inverted once, disabling deletion entirely.
 *
 * @param onClick what tapping the row does, or null when a tap does nothing.
 *   Taken here rather than left to the content, so a row that is clickable is one
 *   clickable node to a screen reader instead of two nested ones -- and a row that
 *   is not is not announced as one at all.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeletableSurface(
    deletable: Boolean,
    deleteLabel: String,
    accessibilityLabel: String,
    onDeleteRequested: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    // `deletable`, not its negation. Offering the menu on a row that cannot be
    // removed leads to a confirmation whose confirm button does nothing, and
    // withholds it from every row where it would work.
    fun openMenu() {
        if (deletable) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            menuOpen = true
        }
    }

    val gestures = if (onClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = ::openMenu)
    } else {
        // No tap action to offer, so no clickable node. `combinedClickable` needs
        // an onClick and advertises one to accessibility services, which announced
        // signature rows as activatable with nothing behind the tap. The long
        // press still opens the menu, and the named action below is the accessible
        // route to the same thing.
        Modifier.pointerInput(deletable) {
            detectTapGestures(onLongPress = { openMenu() })
        }
    }

    Box(
        modifier
            .then(gestures)
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

/**
 * A row that can be deleted by swiping, long-pressing, or an accessibility
 * action.
 *
 * Every row in the document library can be removed, so unlike a signature row
 * there is no blocked state to explain here.
 */
@Composable
fun DeletableRow(
    deleteLabel: String,
    accessibilityLabel: String,
    onDeleteRequested: () -> Unit,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    // confirmValueChange always returns false: the row never dismisses itself, so
    // it springs back as the caller's dialog opens over it. Letting it animate
    // away and then reappear on cancel would show a delete happening and being
    // reverted, which is worse than a row that simply never left.
    //
    // Note this is not what a signature row does. There the whole suffix below
    // the finger is held at its dragged offset until the delete lands, because
    // those rows are moved by DocumentScreen rather than by a gesture of their
    // own -- see CascadeRow.
    val swipeState = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) onDeleteRequested()
            false
        },
    )

    SwipeToDismissBox(
        state = swipeState,
        backgroundContent = {
            RevealLabel(
                deletable = true,
                deleteLabel = deleteLabel,
                blockedLabel = "",
                modifier = Modifier.fillMaxSize(),
            )
        },
    ) {
        DeletableSurface(
            deletable = true,
            deleteLabel = deleteLabel,
            accessibilityLabel = accessibilityLabel,
            onDeleteRequested = onDeleteRequested,
            onClick = onClick,
            content = content,
        )
    }
}
