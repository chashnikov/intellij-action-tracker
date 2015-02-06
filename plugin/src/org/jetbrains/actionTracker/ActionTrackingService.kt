package org.jetbrains.actionTracker

import com.intellij.openapi.project.Project
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.AnActionEvent
import java.util.HashSet
import java.awt.event.InputEvent
import java.util.ArrayList
import java.awt.event.KeyEvent
import com.intellij.ide.IdeEventQueue
import com.intellij.util.ui.UIUtil
import javax.swing.KeyStroke
import com.intellij.ide.IdeEventQueue.EventDispatcher
import java.awt.AWTEvent
import java.awt.event.MouseEvent
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.ui.Messages
import java.text.SimpleDateFormat
import java.util.Date

/**
 * @author nik
 */
fun Project.getActionTrackingService() : ActionTrackingService = ServiceManager.getService(this, javaClass())

public class ActionTrackingService(private val project: Project) {
    var activeTracker: ActionTracker? = null
      private set

    fun startTracking() {
        val tracker = ActionTracker(project)
        tracker.start()
        Disposer.register(project, tracker)
        activeTracker = tracker
    }

    fun stopTracking() {
        val tracker = activeTracker
        if (tracker != null) {
            val records = tracker.exportRecords()
            Disposer.dispose(tracker)
            activeTracker = null
            Messages.showInfoMessage(project, records, "Actions Log")
        }
    }
}

private val actionTextByClass = mapOf("com.intellij.openapi.ui.impl.DialogWrapperPeerImpl\$AnCancelAction" to "Cancel")

class ActionTracker(private val project: Project): Disposable {
    private val actionInputEvents = HashSet<InputEvent>()
    private val actionRecords = ArrayList<ActionRecord>()
    private val startTime = System.currentTimeMillis()

    override fun dispose() {
    }

    private fun addRecord(actionData: ActionData) {
        actionRecords.add(ActionRecord(System.currentTimeMillis(), actionData))
//        println(actionData.toPresentableText())
    }

    fun start() {
        ActionManager.getInstance().addAnActionListener(object : AnActionListener.Adapter() {
            public override fun beforeActionPerformed(action: AnAction, dataContext: DataContext, event: AnActionEvent) {
                val input = event.getInputEvent()
                actionInputEvents.add(input)
                val source = when (input) {
                    is MouseEvent -> MouseClicked(input)
                    is KeyEvent -> KeyStrokePressed(KeyStroke.getKeyStrokeForEvent(input))
                    else -> null
                }
                val actionId = ActionManager.getInstance().getId(action)
                val actionClassName = action.javaClass.getName()
                val text = StringUtil.nullize(event.getPresentation().getText(), true)
                        ?: actionId ?: actionTextByClass[actionClassName] ?: actionClassName
                addRecord(ActionInvoked(text, source))
            }
        }, this)
        IdeEventQueue.getInstance().addPostprocessor(object : EventDispatcher {
            public override fun dispatch(e: AWTEvent?): Boolean {
                if (e is MouseEvent && e.getID() == MouseEvent.MOUSE_CLICKED) {
                    processMouseClickedEvent(e)
                }
                else if (e is KeyEvent && e.getID() == KeyEvent.KEY_PRESSED) {
                    processKeyPressedEvent(e)
                }
                return false
            }
        }, this)

    }

    private fun processMouseClickedEvent(e: MouseEvent) {
        if (actionInputEvents.contains(e)) {
            actionInputEvents.remove(e)
            return
        }
        addRecord(MouseClicked(e))
    }

    private fun processKeyPressedEvent(e: KeyEvent) {
        if (actionInputEvents.contains(e)) {
            actionInputEvents.remove(e)
            return
        }
        if (e.getKeyCode() in setOf(KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_META, KeyEvent.VK_SHIFT)) {
            return;
        }
        if (!IdeEventQueue.getInstance().getKeyEventDispatcher().isReady()) {
            return
        }

        val isChar = e.getKeyChar() != KeyEvent.CHAR_UNDEFINED && UIUtil.isReallyTypedEvent(e);
        val hasActionModifiers = e.isAltDown() || e.isControlDown() || e.isMetaDown();
        val plainType = isChar && !hasActionModifiers;
        val isEnter = e.getKeyCode() == KeyEvent.VK_ENTER;

        if (plainType && !isEnter) {
            addRecord(CharTyped(e.getKeyChar()))
        }
        else {
            addRecord(KeyStrokePressed(KeyStroke.getKeyStrokeForEvent(e)))
        }
    }

    public fun exportRecords(): String {
        val lines = arrayListOf("Tracking started: ${SimpleDateFormat("dd.MM.yyyy, HH:mm").format(Date(startTime))}.")
        val formatter = SimpleDateFormat("HH:mm:ss.SSS")
        actionRecords.mapTo(lines) {
            val time = formatter.format(Date(it.timestamp))
            "$time: ${it.action.toPresentableText()}"
        }
        return lines.joinToString("\n")
    }
}
