package org.jetbrains.actionTracker

import com.intellij.openapi.keymap.KeymapUtil
import java.awt.Component
import java.awt.Window
import java.awt.event.MouseEvent
import javax.swing.JDialog
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * @author nik
 */
class ActionRecord(val timestamp: Long, val action: ActionData)

interface ActionData

class CharTyped(val char: Char): ActionData

class ActionInvoked(val actionText: String, val source: ActionData?): ActionData

class ContextSensitiveActionInvoked(val selection: String, val source: ActionData): ActionData

private fun getWindow(c: Component) = if (c is Window) c else SwingUtilities.getWindowAncestor(c)

class MouseClicked(e: MouseEvent): ActionData {
    val dialogTitle = (getWindow(e.getComponent()) as? JDialog)?.getTitle()
}

class KeyStrokePressed(private val keyStroke: KeyStroke): ActionData {
    fun getKeystrokeText(): String = KeymapUtil.getKeystrokeText(keyStroke)
}

class NextTask: ActionData

fun ActionData.toPresentableText(): String = when (this) {
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
    is ContextSensitiveActionInvoked -> "${source.toPresentableText()} on '$selection'"
    is NextTask -> ">>> Next Task <<<"
    else -> "unknown"
}