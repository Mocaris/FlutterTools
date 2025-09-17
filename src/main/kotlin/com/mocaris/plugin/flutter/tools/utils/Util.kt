package com.mocaris.plugin.flutter.tools.utils

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.ui.Messages

object Util {

    fun notificationSticky(title: String, message: String, type: NotificationType) {
        /*val groupManager = NotificationGroupManager.getInstance()
        val notificationGroup = groupManager.getNotificationGroup("notifi_sticky_balloon")
        val notification = notificationGroup.createNotification(title, message, type)*/
        val notification = Notification("notifi_sticky_balloon", title, message, type)
        Notifications.Bus.notify(notification)
    }

    fun notificationBalloon(title: String, message: String, type: NotificationType) {
        /* val groupManager = NotificationGroupManager.getInstance()
         val notificationGroup = groupManager.getNotificationGroup("notifi_balloon")
         val notification = notificationGroup.createNotification(title, message, type)*/
        val notification = Notification("notifi_balloon", title, message, type)
        Notifications.Bus.notify(notification)
    }

    fun showOkDialog(title: String, message: String) {
        Messages.showOkCancelDialog(message, title, "OK", "Cancel", null)
    }

    fun showErrorDialog(title: String, message: String) {
        Messages.showErrorDialog(message, title)
    }

}