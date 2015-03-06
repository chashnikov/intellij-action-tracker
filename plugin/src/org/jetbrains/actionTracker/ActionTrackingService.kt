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
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JLabel
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.navigation.NavigationItem
import javax.swing.text.JTextComponent
import com.intellij.ide.util.gotoByName.ChooseByNamePopup
import javax.swing.JTree
import javax.swing.JList
import javax.swing.JTable
import com.intellij.ui.treeStructure.treetable.TreeTable
import java.io.File
import com.intellij.util.SystemProperties
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.notification.Notifications
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.ide.actions.ShowFilePathAction
import javax.swing.event.HyperlinkEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import java.awt.Component
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.ui.components.JBList
import java.util.HashMap
import com.intellij.ui.SearchTextField

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
            val productPrefix = ApplicationNamesInfo.getInstance().getFullProductName().replace(' ', '_')
            val time = tracker.getStartTrackingTime().replaceAll("[\\.,: ]", "_")
            val file = File(SystemProperties.getUserHome(), "${productPrefix}_action_tracker_$time.txt")
            file.writeText(records)
            val message = "Actions log saved to <a href=\"file\">${file.getAbsolutePath()}</a>"
            Notifications.Bus.notify(Notification("Action Tracker", "Action Tracker", message, NotificationType.INFORMATION) { n, e ->
                if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
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

private fun isContextSensitiveAction(e: KeyEvent) = e.getKeyCode() in contextSensitiveEvents

private inline fun <reified T: Any> Any.getFieldOfType(): T? {
    val type = javaClass<T>()
    val field = javaClass.getDeclaredFields().first { type.isAssignableFrom(it.getType()) }
    field.setAccessible(true)
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
        val presentableText = navigatable.getPresentation()?.getPresentableText()
        val locationString = navigatable.getPresentation()?.getLocationString()
        return presentableText + if (locationString != null) " $locationString" else ""
    }
    val popup = project.getUserData(ChooseByNamePopup.CHOOSE_BY_NAME_POPUP_IN_PROJECT_KEY)
    if (popup != null) {
        if (textEditing) return popup.getEnteredText()
        val element = popup.getChosenElement()
        if (element != null) {
            val name = popup.getModel().getElementName(element)
            if (name != null) {
                return name
            }
        }
    }
    val searchEverywhere = ActionManager.getInstance().getAction("SearchEverywhere")
    if (searchEverywhere != null && searchEverywhere.getFieldOfType<JBPopup>()?.isVisible() ?: false) {
        if (textEditing) {
            val searchTextField = searchEverywhere.getFieldOfType<SearchTextField>()
            if (searchTextField != null) {
                return searchTextField.getText()
            }
        }
        val list = searchEverywhere.getFieldOfType<JBList>()
        if (list != null) {
            return list.getSelectedValue()?.toString()
        }
    }

    var current = component
    while (true) {
        val cur = current
        when (cur) {
            is JLabel -> return cur.getText()
            is JTextComponent -> return cur.getText()
            is SimpleColoredComponent -> return cur.getCharSequence(true).toString()
            is JTree -> return cur.getSelectionPath()?.getLastPathComponent().toString()
            is JList -> return cur.getSelectedValue().toString()
            is TreeTable -> return cur.getTree().getSelectionPath()?.getLastPathComponent().toString()
            is JTable -> {
                val row = cur.getSelectedRow()
                if (row < 0) return null
                //todo report Kotlin bug
                return (0..cur.getColumnCount()-1).map { (cur.getValueAt(row, it) as Any?).toString() }.joinToString(", ")
            }
        }
        val next = cur.getParent()
        if (next == null) {
            return null
        }
        current = next
    }
}

fun getLocalActionText(action: AnAction): String? {
    if (action.javaClass.getName().contains("$")) {
        val shortcutSet = action.getShortcutSet()
        if (shortcutSet != null) {
            val shortcut = shortcutSet.getShortcuts().firstOrNull()
            if (shortcut != null) {
                return shortcut.toString()
            }
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

    public fun startNextTask() {
        addRecord(NextTask())
    }

    private fun addRecord(actionData: ActionData) {
        actionRecords.add(ActionRecord(System.currentTimeMillis(), actionData))
//        println(actionData.toPresentableText())
    }

    fun start() {
        ActionManager.getInstance().addAnActionListener(object : AnActionListener.Adapter() {
            public override fun beforeActionPerformed(action: AnAction, dataContext: DataContext, event: AnActionEvent) {
                val input = event.getInputEvent()
                if (actionInputEvents.size() > 100) actionInputEvents.clear()
                actionInputEvents.add(input)
                val source = when (input) {
                    is MouseEvent -> MouseClicked(input)
                    is KeyEvent -> KeyStrokePressed(KeyStroke.getKeyStrokeForEvent(input))
                    else -> null
                }
                val actionId = ActionManager.getInstance().getId(action)
                if (actionId?.startsWith("action.tracker.") ?: false) return

                val actionClassName = action.javaClass.getName()
                val text = StringUtil.nullize(event.getPresentation().getText(), true)
                        ?: actionId ?: getLocalActionText(action) ?: actionTextByClass[actionClassName] ?: actionClassName
                addRecord(ActionInvoked(text, source), input)
            }
        }, this)
        IdeEventQueue.getInstance().addDispatcher(object : EventDispatcher {
            public override fun dispatch(e: AWTEvent?): Boolean {
                val selection = if (e is MouseEvent && e.getID() == MouseEvent.MOUSE_CLICKED) {
                    getSelectedItem(e.getComponent(), project, false)
                }
                else if (e is KeyEvent && e.getID() == KeyEvent.KEY_PRESSED && isContextSensitiveAction(e)) {
                    getSelectedItem(e.getComponent(), project, e.getKeyCode() in textEditingEvents)
                }
                else null

                if (selection != null && e is InputEvent) {
                    if (actionContexts.size() > 100) actionContexts.clear()
                    actionContexts.put(e, selection)
                }
                return false
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
            addRecord(KeyStrokePressed(KeyStroke.getKeyStrokeForEvent(e)), e)
        }
    }

    public fun exportRecords(): String {
        val lines = arrayListOf("Tracking started: ${getStartTrackingTime()}.")
        val formatter = SimpleDateFormat("HH:mm:ss.SSS")
        actionRecords.mapTo(lines) {
            val time = formatter.format(Date(it.timestamp))
            "$time: ${it.action.toPresentableText()}"
        }
        return lines.joinToString("\n")
    }

    public fun getStartTrackingTime(): String {
        return SimpleDateFormat("dd.MM.yyyy, HH:mm").format(Date(startTime))
    }
}
