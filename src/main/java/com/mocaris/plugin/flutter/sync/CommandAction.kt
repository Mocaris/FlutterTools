package com.mocaris.plugin.flutter.sync

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class CommandAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val basePath = e.project!!.basePath
        /*   if(!FileUtil.exists("${e.project!!.basePath}${File.separator}pubspec.yaml")){

           }*/
        val commandLine = GeneralCommandLine("flutter packages pub run build_runner build")
        commandLine.setWorkDirectory(basePath)
        val osProcessHandler = OSProcessHandler(commandLine)
        osProcessHandler.startNotify()
    }
}