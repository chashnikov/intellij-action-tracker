package org.jetbrains.actionTracker

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/**
 * @author nik
 */
class NextTaskAction: DumbAwareAction("Next Task") {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.getActionTrackingService()?.activeTracker?.startNextTask()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val presentation = e.presentation
        presentation.isEnabledAndVisible = project?.getActionTrackingService()?.activeTracker != null
    }
}