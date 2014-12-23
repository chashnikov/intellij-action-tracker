package org.jetbrains.actionTracker

import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * @author nik
 */
public class StartStopTrackingAction : DumbAwareAction("Start/Stop Tracking") {
    override fun actionPerformed(e: AnActionEvent) {
        val service = e.getProject().getActionTrackingService()
        if (service.activeTracker != null) {
            service.stopTracking()
        }
        else {
            service.startTracking()
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.getProject()
        val presentation = e.getPresentation()
        if (project == null) {
            presentation.setEnabledAndVisible(false)
            return
        }
        val tracker = project.getActionTrackingService().activeTracker
        presentation.setEnabledAndVisible(true)
        presentation.setText(if (tracker == null) "Start Tracking" else "Stop Tracking...")
    }
}