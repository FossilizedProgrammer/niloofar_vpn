package com.ffvpn.client.ui

import android.content.Context
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import com.ffvpn.client.R

@Composable
fun ProvideAppTextToolbar(content: @Composable () -> Unit) {
    val view = LocalView.current
    val context = LocalContext.current
    val toolbar = remember(view, context) { AppTextToolbar(view, context) }
    DisposableEffect(toolbar) {
        onDispose { toolbar.hide() }
    }
    CompositionLocalProvider(LocalTextToolbar provides toolbar, content = content)
}

private class AppTextToolbar(private val view: View, private val context: Context) : TextToolbar {
    private var actionMode: ActionMode? = null
    private var rect = Rect.Zero
    private var actions = emptyList<Pair<Int, (() -> Unit)?>>()

    override val status: TextToolbarStatus
        get() = if (actionMode == null) TextToolbarStatus.Hidden else TextToolbarStatus.Shown

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        this.rect = rect
        actions = listOf(
            R.string.selection_copy to onCopyRequested,
            R.string.selection_paste to onPasteRequested,
            R.string.selection_cut to onCutRequested,
            R.string.selection_select_all to onSelectAllRequested
        )
        if (actionMode == null) {
            actionMode = view.startActionMode(callback, ActionMode.TYPE_FLOATING)
        } else {
            actionMode?.invalidate()
            actionMode?.invalidateContentRect()
        }
    }

    override fun hide() {
        actionMode?.finish()
        actionMode = null
    }

    private fun populate(menu: Menu) {
        menu.clear()
        actions.forEachIndexed { index, (label, action) ->
            if (action != null) {
                // Pass text, not a resource ID: the menu can have the system locale's context.
                menu.add(Menu.NONE, label, index, context.getString(label))
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            }
        }
    }

    private val callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            populate(menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            populate(menu)
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val action = actions.firstOrNull { it.first == item.itemId }?.second ?: return false
            action()
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
            outRect.set(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        }
    }
}
