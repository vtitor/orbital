package com.github.cosmosdbclient.service

import com.azure.cosmos.CosmosException
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.Project

/**
 * Central place that turns any [Throwable] from the Azure SDK into a friendly, actionable
 * message. Errors are surfaced as balloon notifications with a "Show details" action;
 * successes can be reported the same way.
 */
object CosmosErrors {

    const val GROUP_ID = "Orbital"

    fun notifyError(project: Project?, context: String, error: Throwable) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
        if (group == null) {
            Messages.showErrorDialog(project, details(context, error), "$context — Orbital")
            return
        }
        group.createNotification("$context failed", shortMessage(error), NotificationType.ERROR)
            .addAction(object : NotificationAction("Show details") {
                override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                    Messages.showMessageDialog(
                        project,
                        details(context, error),
                        "Orbital — Error Details",
                        Messages.getErrorIcon(),
                    )
                }
            })
            .notify(project)
    }

    fun notifyInfo(project: Project?, content: String) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID) ?: return
        group.createNotification(content, NotificationType.INFORMATION).notify(project)
    }

    /** One-line summary suitable for a balloon or status bar. */
    fun shortMessage(error: Throwable): String = when (error) {
        is CosmosException -> "HTTP ${error.statusCode}" +
            (if (error.subStatusCode != 0) "/${error.subStatusCode}" else "") +
            ": " + firstLine(error.message)
        else -> error.message?.let { firstLine(it) } ?: error.javaClass.simpleName
    }

    /** Full, multi-line detail for the details dialog. */
    fun details(context: String, error: Throwable): String = buildString {
        appendLine("Operation: $context")
        appendLine("Type: ${error.javaClass.name}")
        if (error is CosmosException) {
            appendLine("HTTP status: ${error.statusCode}")
            appendLine("Sub-status: ${error.subStatusCode}")
            appendLine("Activity id: ${error.activityId}")
            appendLine("Request charge: ${"%.2f".format(error.requestCharge)} RU")
            error.retryAfterDuration?.let { appendLine("Retry after: ${it.toMillis()} ms") }
        }
        appendLine()
        appendLine(error.message ?: "(no message)")
    }.trim()

    private fun firstLine(message: String?): String {
        if (message.isNullOrBlank()) return "(no message)"
        val cut = message.indexOfFirst { it == '\n' || it == '\r' }
        val line = if (cut >= 0) message.substring(0, cut) else message
        // CosmosException messages embed a large JSON diagnostics blob; trim it.
        return line.substringBefore(", {\"").take(240).trim()
    }
}
