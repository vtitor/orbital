package com.github.cosmosdbclient.util

import com.github.cosmosdbclient.service.CosmosErrors
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

/**
 * Runs a blocking piece of work on a background thread (with a progress indicator) and
 * delivers the result back on the EDT. Failures are reported through [CosmosErrors] unless
 * a custom [onError] is supplied.
 */
object Bg {

    fun <T> run(
        project: Project?,
        title: String,
        work: () -> T,
        onSuccess: (T) -> Unit = {},
        onError: (Throwable) -> Unit = { CosmosErrors.notifyError(project, title.trimEnd('…', '.'), it) },
        cancellable: Boolean = true,
        onComplete: () -> Unit = {},
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, cancellable) {
            private var outcome: Result<T>? = null

            override fun run(indicator: ProgressIndicator) {
                outcome = runCatching { work() }
            }

            override fun onSuccess() {
                outcome?.fold(onSuccess, onError)
            }

            // Always runs on the EDT — including when the task is cancelled (when onSuccess is
            // NOT called), so callers can reliably reset UI state (buttons, loading flags).
            override fun onFinished() {
                onComplete()
            }
        })
    }
}
