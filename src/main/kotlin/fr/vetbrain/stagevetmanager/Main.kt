package fr.vetbrain.stagevetmanager

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import fr.vetbrain.stagevetmanager.ui.App

fun main() = application {
    val state = rememberWindowState(width = 1400.dp, height = 800.dp)
    Window(
        onCloseRequest = ::exitApplication,
        title = "StageVet Manager",
        state = state,
    ) {
        App()
    }
}
