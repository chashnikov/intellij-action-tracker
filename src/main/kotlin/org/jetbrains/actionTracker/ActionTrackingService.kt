package org.jetbrains.actionTracker

import com.intellij.ide.DataManager
import com.intellij.ide.IdeEventQueue
import com.intellij.ide.IdeEventQueue.EventDispatcher
import com.intellij.ide.actions.ShowFilePathAction
import com.intellij.ide.util.gotoByName.ChooseByNamePopup
import com.intellij.navigation.NavigationItem
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.JBList
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.util.SystemProperties
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*
import javax.swing.event.HyperlinkEvent
import javax.swing.text.JTextComponent

/**
 * @author nik
 */
fun Project.getActionTrackingService() : ActionTrackingService = ServiceManager.getService(this, ActionTrackingService::class.java)

class ActionTrackingService(private val project: Project) {
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
            val productPrefix = ApplicationNamesInfo.getInstance().fullProductName.replace(' ', '_')
            val time = tracker.getStartTrackingTime().replace(Regex("[.,: ]"), "_")
            val file = File(SystemProperties.getUserHome(), "${productPrefix}_action_tracker_$time.txt")
            file.writeText(records)
            val message = "Actions log saved to <a href=\"file\">${file.absolutePath}</a>"
            Notifications.Bus.notify(Notification("Action Tracker", "Action Tracker", message, NotificationType.INFORMATION) { _, e ->
                if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                    ShowFilePathAction.openFile(file)
                }
            })
        }
    }
}

private val actionTextByClass = mapOf("com.intellij.openapi.ui.impl.DialogWrapperPeerImpl\$AnCancelAction" to "Cancel")
private val contextSensitiveEvents = setOf(KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT,
        KeyEvent.VK_HOME, KeyEvent.VK_END, KeyEvent.VK_PAGE_DOWN, KeyEvent.VK_PAGE_UP, KeyEvent.VK_ENTER, KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE, KeyEvent.VK_TAB)
private val textEditingEvents = setOf(KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE)

private fun isContextSensitiveAction(e: KeyEvent) = e.keyCode in contextSensitiveEvents

private inline fun <reified T: Any> Any.getFieldOfType(): T? {
    val type = T::class.java
    val field = javaClass.declaredFields.first { type.isAssignableFrom(it.type) }
    field.isAccessible = true
    return field?.get(this) as? T
}

private fun getSelectedItem(component: Component?, project: Project, textEditing: Boolean): String? {
    val context = DataManager.getInstance().getDataContext(component)
    val text = PlatformDataKeys.PREDEFINED_TEXT.getData(context)
    if (text != null && textEditing) {
        return text
    }

    val navigatable = CommonDataKeys.NAVIGATABLE.getData(context)
    if (navigatable is NavigationItem) {
        val presentableText = navigatable.presentation?.presentableText
        val locationString = navigatable.presentation?.locationString
        return presentableText + if (locationString != null) " $locationString" else ""
    }
    val popup = project.getUserData(ChooseByNamePopup.CHOOSE_BY_NAME_POPUP_IN_PROJECT_KEY)
    if (popup != null) {
        if (textEditing) return popup.enteredText
        val element = popup.chosenElement
        if (element != null) {
            val name = popup.model.getElementName(element)
            if (name != null) {
                return name
            }
        }
    }
    val searchEverywhere = ActionManager.getInstance().getAction("SearchEverywhere")
    if (searchEverywhere != null && searchEverywhere.getFieldOfType<JBPopup>()?.isVisible == true) {
        if (textEditing) {
            val searchTextField = searchEverywhere.getFieldOfType<SearchTextField>()
            if (searchTextField != null) {
                return searchTextField.text
            }
        }
        val list = searchEverywhere.getFieldOfType<JBList<*>>()
        if (list != null) {
            return list.selectedValue?.toString()
        }
    }

    var current = component
    while (true) {
        val cur = current
        when (cur) {
            is JLabel -> return cur.text
            is JTextComponent -> return cur.text
            is SimpleColoredComponent -> return cur.getCharSequence(true).toString()
            is JTree -> return cur.selectionPath?.lastPathComponent.toString()
            is JList<*> -> return cur.selectedValue.toString()
            is TreeTable -> return cur.tree.selectionPath?.lastPathComponent.toString()
            is JTable -> {
                val row = cur.selectedRow
                if (row < 0) return null
                return (0 until cur.columnCount).joinToString(", ") { cur.getValueAt(row, it).toString() }
            }
        }
        val next = cur?.parent ?: return null
        current = next
    }
}

fun getLocalActionText(action: AnAction): String? {
    if (action.javaClass.name.contains("$")) {
        val shortcutSet = action.shortcutSet
        val shortcut = shortcutSet.shortcuts.firstOrNull()
        if (shortcut != null) {
            return shortcut.toString()
        }
    }
    return null
}


class ActionTracker(private val project: Project): Disposable {
    private val actionInputEvents = HashSet<InputEvent>()
    private val actionContexts = HashMap<InputEvent, String>()
    private val actionRecords = ArrayList<ActionRecord>()
    private val startTime = System.currentTimeMillis()

    override fun dispose() {
    }

    fun startNextTask() {
        addRecord(NextTask())
    }

    private fun addRecord(actionData: ActionData) {
        actionRecords.add(ActionRecord(System.currentTimeMillis(), actionData))
//        println(actionData.toPresentableText())
    }

    fun start() {
        ActionManager.getInstance().addAnActionListener(object : AnActionListener.Adapter() {
            override fun beforeActionPerformed(action: AnAction, dataContext: DataContext, event: AnActionEvent) {
                val input = event.inputEvent
                if (actionInputEvents.size > 100) actionInputEvents.clear()
                actionInputEvents.add(input)
                val source = when (input) {
                    is MouseEvent -> MouseClicked(input)
                    is KeyEvent -> KeyStrokePressed(KeyStroke.getKeyStrokeForEvent(input))
                    else -> null
                }
                val actionId = ActionManager.getInstance().getId(action)
                if (actionId?.startsWith("action.tracker.") == true) return

                val actionClassName = action.javaClass.name
                val text = StringUtil.nullize(event.presentation.text, true)
                        ?: actionId ?: getLocalActionText(action) ?: actionTextByClass[actionClassName] ?: actionClassName
                addRecord(ActionInvoked(text, source), input)
            }
        }, this)
        IdeEventQueue.getInstance().addDispatcher(EventDispatcher { e ->
            val selection = if (e is MouseEvent && e.getID() == MouseEvent.MOUSE_CLICKED) {
                getSelectedItem(e.component, project, false)
            } else if (e is KeyEvent && e.getID() == KeyEvent.KEY_PRESSED && isContextSensitiveAction(e)) {
                getSelectedItem(e.component, project, e.keyCode in textEditingEvents)
            } else null

            if (selection != null && e is InputEvent) {
                if (actionContexts.size > 100) actionContexts.clear()
                actionContexts[e] = selection
            }
            false
        }, this)
        IdeEventQueue.getInstance().addPostprocessor(EventDispatcher { e ->
            if (e is MouseEvent && e.getID() == MouseEvent.MOUSE_CLICKED) {
                processMouseClickedEvent(e)
            } else if (e is KeyEvent && e.getID() == KeyEvent.KEY_PRESSED) {
                processKeyPressedEvent(e)
            }
            false
        }, this)

    }

    private fun addRecord(actionData: ActionData, input: InputEvent?) {
        val selection = actionContexts[input]
        if (selection != null) {
            actionContexts.remove(input)
            addRecord(ContextSensitiveActionInvoked(selection, actionData))
        } else {
            addRecord(actionData)
        }
    }

    private fun processMouseClickedEvent(e: MouseEvent) {
        if (actionInputEvents.contains(e)) {
            actionInputEvents.remove(e)
            return
        }
        addRecord(MouseClicked(e), e)
    }

    private fun processKeyPressedEvent(e: KeyEvent) {
        if (actionInputEvents.contains(e)) {
            actionInputEvents.remove(e)
            return
        }
        if (e.keyCode in setOf(KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_META, KeyEvent.VK_SHIFT)) {
            return
        }
        if (!IdeEventQueue.getInstance().keyEventDispatcher.isReady) {
            return
        }

        val isChar = e.keyChar != KeyEvent.CHAR_UNDEFINED && UIUtil.isReallyTypedEvent(e)
        val hasActionModifiers = e.isAltDown || e.isControlDown || e.isMetaDown
        val plainType = isChar && !hasActionModifiers
        val isEnter = e.keyCode == KeyEvent.VK_ENTER

        if (plainType && !isEnter) {
            addRecord(CharTyped(e.keyChar))
        }
        else {
            addRecord(KeyStrokePressed(KeyStroke.getKeyStrokeForEvent(e)), e)
        }
    }

    fun exportRecords(): String {
        val lines = arrayListOf("Tracking started: ${getStartTrackingTime()}.")
        val formatter = SimpleDateFormat("HH:mm:ss.SSS")
        actionRecords.mapTo(lines) {
            val time = formatter.format(Date(it.timestamp))
            "$time: ${it.action.toPresentableText()}"
        }
        return lines.joinToString("\n")
    }

    fun getStartTrackingTime(): String {
        return SimpleDateFormat("dd.MM.yyyy, HH:mm").format(Date(startTime))
    }
}
