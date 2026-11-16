package com.mocaris.plugin.flutter.tools.core

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object FlutterToolsNotifications {
    fun notify(project: Project, message: String, type: NotificationType) {
        if (!project.isDisposed) NotificationGroupManager.getInstance().getNotificationGroup("FlutterTools")
            .createNotification("FlutterTools", message, type).notify(project)
    }
}
