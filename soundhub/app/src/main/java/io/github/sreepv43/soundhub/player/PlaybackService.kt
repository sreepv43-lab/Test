package io.github.sreepv43.soundhub.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.sreepv43.soundhub.MainActivity
import io.github.sreepv43.soundhub.container
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Keeps music playing when the app is in the background, and lets the TV's system controls and
 * remote media keys control it.
 */
class PlaybackService : MediaSessionService() {
    private val scope = MainScope()
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val playback = container.playback
        session = buildSession(playback.player)
        scope.launch {
            playback.playerFlow.drop(1).collect { player ->
                session?.let {
                    removeSession(it)
                    it.release()
                }
                session = buildSession(player)
            }
        }
    }

    private fun buildSession(player: androidx.media3.common.Player): MediaSession {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return MediaSession.Builder(this, player).setSessionActivity(openApp).build().also(::addSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            context.startService(Intent(context, PlaybackService::class.java))
        }
    }
}
