package org.jetbrains.actionTracker

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/**
 * @author nik
 */
class StartStopTrackingAction : DumbAwareAction("Start/Stop Tracking") {
    override fun actionPerformed(e: AnActionEvent) {
        val service = e.project!!.getActionTrackingService()
        if (service.activeTracker != null) {
            service.stopTracking()
        }
        else {
            service.startTracking()
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val presentation = e.presentation
        if (project == null) {
            presentation.isEnabledAndVisible = false
            return
        }
        val tracker = project.getActionTrackingService().activeTracker
        presentation.isEnabledAndVisible = true
        presentation.text = if (tracker == null) "Start Tracking" else "Stop Tracking..."
    }
}