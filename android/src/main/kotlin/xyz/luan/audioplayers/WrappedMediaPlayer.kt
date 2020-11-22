package xyz.luan.audioplayers

import android.content.Context
import android.media.*
import android.os.Build
import android.os.PowerManager
import java.io.IOException

class WrappedMediaPlayer internal constructor(
        private val ref: AudioplayersPlugin,
        override val playerId: String,
) : Player(), MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener, AudioManager.OnAudioFocusChangeListener, MediaPlayer.OnSeekCompleteListener, MediaPlayer.OnErrorListener {
    private var context: Context? = null
    private val audioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private var player: MediaPlayer? = null
    private var url: String? = null
    private var dataSource: MediaDataSource? = null
    private var volume = 1.0
    private var rate = 1.0f
    private var respectSilence = false
    private var stayAwake = false
    private var duckAudio = false
    private var releaseMode: ReleaseMode = ReleaseMode.RELEASE
    private var playingRoute: String = "speakers"
    private var released = true
    private var prepared = false
    private var playing = false
    private var shouldSeekTo = -1

    /**
     * Setter methods
     */
    override fun setUrl(url: String?, isLocal: Boolean, context: Context) {
        if (this.url != url) {
            this.url = url
            if (released) {
                player = createPlayer(context)
                released = false
            } else if (prepared) {
                player!!.reset()
                prepared = false
            }
            setSource(url)
            player!!.setVolume(volume.toFloat(), volume.toFloat())
            player!!.isLooping = releaseMode === ReleaseMode.LOOP
            player!!.prepareAsync()
        }

        // Dispose of any old data buffer array, if we are now playing from another source.
        dataSource = null
    }

    override fun setDataSource(mediaDataSource: MediaDataSource?, context: Context) {
        if (!objectEquals(dataSource, mediaDataSource)) {
            dataSource = mediaDataSource
            if (released) {
                player = createPlayer(context)
                released = false
            } else if (prepared) {
                player!!.reset()
                prepared = false
            }
            setMediaSource(mediaDataSource)
            player!!.setVolume(volume.toFloat(), volume.toFloat())
            player!!.isLooping = releaseMode === ReleaseMode.LOOP
            player!!.prepareAsync()
        }
    }

    override fun setVolume(volume: Double) {
        if (this.volume != volume) {
            this.volume = volume
            if (!released) {
                player!!.setVolume(volume.toFloat(), volume.toFloat())
            }
        }
    }

    override fun setPlayingRoute(playingRoute: String, context: Context) {
        if (this.playingRoute != playingRoute) {
            val wasPlaying = playing
            if (wasPlaying) {
                pause()
            }
            this.playingRoute = playingRoute
            val position = player?.currentPosition ?: 0
            released = false
            player = createPlayer(context)
            setSource(url)
            try {
                player!!.prepare()
            } catch (ex: IOException) {
                throw RuntimeException("Unable to access resource", ex)
            }
            seek(position)
            if (wasPlaying) {
                playing = true
                player!!.start()
            }
        }
    }

    override fun setRate(rate: Double): Int {
        val player = this.player ?: return 0

        this.rate = rate.toFloat()
        player.playbackParams = player.playbackParams.setSpeed(this.rate)
        return 1
    }

    override fun configAttributes(respectSilence: Boolean, stayAwake: Boolean, duckAudio: Boolean, context: Context) {
        this.context = context
        if (this.respectSilence != respectSilence) {
            this.respectSilence = respectSilence
            if (!released) {
                player?.let { setAttributes(it, context) }
            }
        }
        if (this.duckAudio != duckAudio) {
            this.duckAudio = duckAudio
            if (!released) {
                player?.let { setAttributes(it, context) }
            }
        }
        if (this.stayAwake != stayAwake) {
            this.stayAwake = stayAwake
            if (!released && this.stayAwake) {
                player?.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            }
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            actuallyPlay(context!!)
        }
    }

    override fun setReleaseMode(releaseMode: ReleaseMode) {
        if (this.releaseMode !== releaseMode) {
            this.releaseMode = releaseMode
            if (!released) {
                player!!.isLooping = releaseMode === ReleaseMode.LOOP
            }
        }
    }

    /**
     * Getter methods
     */
    override val duration: Int
        get() = player!!.duration

    override val currentPosition: Int
        get() = player!!.currentPosition

    override val isActuallyPlaying: Boolean
        get() = playing && prepared

    private val audioManager: AudioManager
        get() = context!!.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Playback handling methods
     */
    override fun play(context: Context) {
        this.context = context
        if (duckAudio) {
            val audioManager = audioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(
                                AudioAttributes.Builder()
                                        .setUsage(if (respectSilence) AudioAttributes.USAGE_NOTIFICATION_RINGTONE else AudioAttributes.USAGE_MEDIA)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                        .build()
                        )
                        .setOnAudioFocusChangeListener { actuallyPlay(context) }.build()
                this.audioFocusRequest = audioFocusRequest
                audioManager.requestAudioFocus(audioFocusRequest)
            } else {
                // Request audio focus for playback
                @Suppress("DEPRECATION")
                val result = audioManager.requestAudioFocus(audioFocusChangeListener,  // Use the music stream.
                        AudioManager.STREAM_MUSIC,  // Request permanent focus.
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    actuallyPlay(context)
                }
            }
        } else {
            actuallyPlay(context)
        }
    }

    private fun actuallyPlay(context: Context) {
        if (!playing) {
            playing = true
            if (released) {
                released = false
                player = createPlayer(context)
                if (dataSource != null) {
                    setMediaSource(dataSource)
                } else {
                    setSource(url)
                }
                player!!.prepareAsync()
            } else if (prepared) {
                player!!.start()
                ref.handleIsPlaying()
            }
        }
    }

    override fun stop() {
        if (duckAudio) {
            val audioManager = audioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusChangeListener)
            }
        }
        if (released) {
            return
        }
        if (releaseMode !== ReleaseMode.RELEASE) {
            if (playing) {
                playing = false
                player!!.pause()
                player!!.seekTo(0)
            }
        } else {
            release()
        }
    }

    override fun release() {
        if (released) {
            return
        }
        if (playing) {
            player!!.stop()
        }
        player!!.reset()
        player!!.release()
        player = null
        prepared = false
        released = true
        playing = false
        context = null
    }

    override fun pause() {
        if (playing) {
            playing = false
            player!!.pause()
        }
    }

    // seek operations cannot be called until after
    // the player is ready.
    override fun seek(position: Int) {
        if (prepared) {
            player!!.seekTo(position)
        } else {
            shouldSeekTo = position
        }
    }

    /**
     * MediaPlayer callbacks
     */
    override fun onPrepared(mediaPlayer: MediaPlayer) {
        prepared = true
        ref.handleDuration(this)
        if (playing) {
            player!!.start()
            ref.handleIsPlaying()
        }
        if (shouldSeekTo >= 0) {
            player!!.seekTo(shouldSeekTo)
            shouldSeekTo = -1
        }
    }

    override fun onCompletion(mediaPlayer: MediaPlayer) {
        if (releaseMode !== ReleaseMode.LOOP) {
            stop()
        }
        ref.handleCompletion(this)
    }

    override fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean {
        var whatMsg: String
        whatMsg = if (what == MediaPlayer.MEDIA_ERROR_SERVER_DIED) {
            "MEDIA_ERROR_SERVER_DIED"
        } else {
            "MEDIA_ERROR_UNKNOWN {what:$what}"
        }
        val extraMsg: String
        when (extra) {
            -2147483648 -> extraMsg = "MEDIA_ERROR_SYSTEM"
            MediaPlayer.MEDIA_ERROR_IO -> extraMsg = "MEDIA_ERROR_IO"
            MediaPlayer.MEDIA_ERROR_MALFORMED -> extraMsg = "MEDIA_ERROR_MALFORMED"
            MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> extraMsg = "MEDIA_ERROR_UNSUPPORTED"
            MediaPlayer.MEDIA_ERROR_TIMED_OUT -> extraMsg = "MEDIA_ERROR_TIMED_OUT"
            else -> {
                whatMsg = "MEDIA_ERROR_UNKNOWN {extra:$extra}"
                extraMsg = whatMsg
            }
        }
        ref.handleError(this, "MediaPlayer error with what:$whatMsg extra:$extraMsg")
        return false
    }

    override fun onSeekComplete(mediaPlayer: MediaPlayer) {
        ref.handleSeekComplete()
    }

    /**
     * Internal logic. Private methods
     */
    private fun createPlayer(context: Context): MediaPlayer {
        val player = MediaPlayer()
        player.setOnPreparedListener(this)
        player.setOnCompletionListener(this)
        player.setOnSeekCompleteListener(this)
        player.setOnErrorListener(this)

        setAttributes(player, context)
        player.setVolume(volume.toFloat(), volume.toFloat())
        player.isLooping = releaseMode === ReleaseMode.LOOP
        return player
    }

    private fun setSource(url: String?) {
        try {
            player!!.setDataSource(url)
        } catch (ex: IOException) {
            throw RuntimeException("Unable to access resource", ex)
        }
    }

    private fun setMediaSource(mediaDataSource: MediaDataSource?) {
        try {
            player!!.setDataSource(mediaDataSource)
        } catch (ex: Exception) {
            throw RuntimeException("Unable to access media resource", ex)
        }
    }

    private fun setAttributes(player: MediaPlayer, context: Context) {
        val usage = when {
            // Works with bluetooth headphones
            // automatically switch to earpiece when disconnect bluetooth headphones
            playingRoute != "speakers" -> AudioAttributes.USAGE_VOICE_COMMUNICATION
            respectSilence -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
            else -> AudioAttributes.USAGE_MEDIA
        }
        player.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )

        if (usage == AudioAttributes.USAGE_VOICE_COMMUNICATION) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.isSpeakerphoneOn = false
        }
    }

}