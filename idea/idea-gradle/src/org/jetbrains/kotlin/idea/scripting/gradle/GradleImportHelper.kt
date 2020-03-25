/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("UnstableApiUsage")

package org.jetbrains.kotlin.idea.scripting.gradle

import com.intellij.notification.*
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import org.jetbrains.kotlin.idea.KotlinIcons
import org.jetbrains.kotlin.idea.KotlinIdeaGradleBundle
import org.jetbrains.kotlin.psi.UserDataProperty
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.util.GradleConstants

fun runPartialGradleImport(project: Project) {
    val gradleSettings = ExternalSystemApiUtil.getSettings(project, GradleConstants.SYSTEM_ID)
    val projectSettings = gradleSettings.getLinkedProjectsSettings()
        .filterIsInstance<GradleProjectSettings>()
        .firstOrNull() ?: return

    ExternalSystemUtil.refreshProject(
        projectSettings.externalProjectPath,
        ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
            .build()
    )
}

private val kotlinDslNotificationGroupId = "Gradle Kotlin DSL Scripts"
private var Project.notificationPanel: ScriptConfigurationChangedNotification?
        by UserDataProperty<Project, ScriptConfigurationChangedNotification>(Key.create("load.script.configuration.panel"))


fun showNotificationForProjectImport(project: Project, callback: () -> Unit) {
    val existingPanel = project.notificationPanel
    if (existingPanel != null) {
        existingPanel.update(callback)
        return
    }

    val notificationGroup = NotificationGroup.findRegisteredGroup(kotlinDslNotificationGroupId)
    if (notificationGroup == null) {
        NotificationsConfiguration.getNotificationsConfiguration().register(
            kotlinDslNotificationGroupId, NotificationDisplayType.STICKY_BALLOON, false
        )
    }

    val notification = ScriptConfigurationChangedNotification(project, callback)
    project.notificationPanel = notification
    notification.notify(project)
}

fun hideNotificationForProjectImport(project: Project): Boolean {
    if (project.notificationPanel == null) return false
    project.notificationPanel?.expire()
    return true
}

private class ScriptConfigurationChangedNotification(val project: Project, onClick: () -> Unit) :
    Notification(
        kotlinDslNotificationGroupId,
        KotlinIcons.LOAD_SCRIPT_CONFIGURATION,
        KotlinIdeaGradleBundle.message("notification.title.script.configuration.has.been.changed"),
        null,
        KotlinIdeaGradleBundle.message("notification.text.script.configuration.has.been.changed"),
        NotificationType.INFORMATION,
        null
    ) {

    init {
        //NotificationGroup.findRegisteredGroup(s) ?: NotificationGroup.balloonGroup(s)

        addAction(LoadConfigurationAction(onClick))
        addAction(NotificationAction.createSimple(KotlinIdeaGradleBundle.message("action.label.enable.auto.import")) {
            val gradleSettings = ExternalSystemApiUtil.getSettings(project, GradleConstants.SYSTEM_ID)
            val projectSettings = gradleSettings.getLinkedProjectsSettings()
                .filterIsInstance<GradleProjectSettings>()
                .firstOrNull()
            if (projectSettings != null) {
                projectSettings.isUseAutoImport = true
            }
        })
    }

    fun update(onClick: () -> Unit) {
        actions.filterIsInstance<LoadConfigurationAction>().forEach { it.onClick = onClick }
    }

    override fun expire() {
        super.expire()

        project.notificationPanel = null
    }

    private class LoadConfigurationAction(var onClick: () -> Unit) : AnAction(KotlinIdeaGradleBundle.message("action.label.import.project")) {
        override fun actionPerformed(e: AnActionEvent) {
            onClick()
        }
    }
}