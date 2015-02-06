package org.jetbrains.actionTracker

import javax.swing.KeyStroke
import com.intellij.openapi.keymap.KeymapUtil
import java.awt.event.MouseEvent
import java.awt.Component
import java.awt.Window
import javax.swing.SwingUtilities
import javax.swing.JDialog

/**
 * @author nik
 */
class ActionRecord(val timestamp: Long, val action: ActionData)

trait ActionData {
}

class CharTyped(val char: Char): ActionData {}

class ActionInvoked(val actionText: String, val source: ActionData?): ActionData

class NavigationActionInvoked(val selection: String, val source: ActionData): ActionData

private fun getWindow(c: Component) = if (c is Window) c else SwingUtilities.getWindowAncestor(c)

class MouseClicked(e: MouseEvent): ActionData {
    val dialogTitle = (getWindow(e.getComponent()) as? JDialog)?.getTitle() ?: null
}

class KeyStrokePressed(val keyStroke: KeyStroke): ActionData {
    fun getKeystrokeText(): String = KeymapUtil.getKeystrokeText(keyStroke)
}

class NextTask: ActionData {}

public fun ActionData.toPresentableText(): String = when (this) {
    is CharTyped -> "typed '$char'"
    is MouseClicked -> "mouse clicked${if (dialogTitle != null) " (in '$dialogTitle' dialog)" else ""}"
    is KeyStrokePressed -> getKeystrokeText()
    is ActionInvoked -> {
        val via = when (source) {
            is MouseClicked -> " via mouse click"
            is KeyStrokePressed -> " via ${source.toPresentableText()}"
            else -> ""
        }
        "action '$actionText'$via"
    }
    is NavigationActionInvoked -> "${source.toPresentableText()} on '$selection'"
    is NextTask -> ">>> Next Task <<<"
    else -> "unknown"
}