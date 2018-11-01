package org.jetbrains.actionTracker

import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * @author nik
 */
public class NextTaskAction: DumbAwareAction("Next Task") {
    override fun actionPerformed(e: AnActionEvent) {
        e.getProject()?.getActionTrackingService()?.activeTracker?.startNextTask()
    }

    override fun update(e: AnActionEvent) {
        val project = e.getProject()
        val presentation = e.getPresentation()
        presentation.setEnabledAndVisible(project?.getActionTrackingService()?.activeTracker != null)
    }
}