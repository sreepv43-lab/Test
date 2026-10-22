package io.github.sreepv43.streamhub.server

import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import javafx.scene.image.Image
import javafx.scene.web.WebView
import javafx.stage.Stage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The control page in its own "StreamHub Server" window (JavaFX's WebView) instead of a browser
 * tab. JavaFX comes only with the installers: without it (the zip, a NAS) this class can't load
 * and the caller opens the browser instead.
 */
object AppWindow {
    private val started = AtomicBoolean(false)

    /**
     * Opens the window. With [quitOnClose] the program ends when it is closed (it only showed the
     * window of a server that was already running); otherwise the server keeps running.
     * Throws when JavaFX can't start here, so the caller can fall back to the browser.
     */
    fun open(url: String, quitOnClose: Boolean) {
        if (started.compareAndSet(false, true)) {
            val ready = CountDownLatch(1)
            var failure: Throwable? = null
            try {
                Platform.startup { ready.countDown() }
            } catch (e: Throwable) {
                failure = e
                ready.countDown()
            }
            ready.await()
            failure?.let {
                started.set(false)
                throw it
            }
            Platform.setImplicitExit(false)
        }
        Platform.runLater { show(url, quitOnClose) }
    }

    private fun show(url: String, quitOnClose: Boolean) {
        val view = WebView()
        val engine = view.engine
        // The page asks before removing things; WebView shows nothing for confirm() by itself.
        engine.setConfirmHandler { message ->
            Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL).apply {
                title = "StreamHub Server"
                headerText = null
            }.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK
        }
        engine.setOnAlert { event ->
            Alert(Alert.AlertType.INFORMATION, event.data, ButtonType.OK).apply {
                title = "StreamHub Server"
                headerText = null
            }.showAndWait()
        }
        engine.load(url)
        val stage = Stage()
        stage.title = "StreamHub Server"
        AppWindow::class.java.getResourceAsStream("/icon.png")?.use { stage.icons += Image(it) }
        stage.scene = Scene(view, 1100.0, 820.0)
        stage.setOnHidden {
            engine.load(null)
            if (quitOnClose) {
                Platform.exit()
                kotlin.system.exitProcess(0)
            }
        }
        stage.show()
        stage.toFront()
    }
}
