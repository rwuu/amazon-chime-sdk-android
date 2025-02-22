/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.amazonaws.services.chime.sdk.meetings.internal.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import com.amazonaws.services.chime.sdk.meetings.analytics.EventAnalyticsController
import com.amazonaws.services.chime.sdk.meetings.analytics.EventAttributeName
import com.amazonaws.services.chime.sdk.meetings.analytics.EventName
import com.amazonaws.services.chime.sdk.meetings.analytics.MeetingStatsCollector
import com.amazonaws.services.chime.sdk.meetings.audiovideo.audio.AudioMode
import com.amazonaws.services.chime.sdk.meetings.internal.utils.AppInfoUtil
import com.amazonaws.services.chime.sdk.meetings.session.MeetingSessionStatus
import com.amazonaws.services.chime.sdk.meetings.session.MeetingSessionStatusCode
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger
import com.xodee.client.audio.audioclient.AudioClient
import com.xodee.client.audio.audioclient.AudioClient.AudioModeInternal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class DefaultAudioClientController(
    private val context: Context,
    private val logger: Logger,
    private val audioClientObserver: AudioClientObserver,
    private val audioClient: AudioClient,
    private val meetingStatsCollector: MeetingStatsCollector,
    private val eventAnalyticsController: EventAnalyticsController
) : AudioClientController {
    private val TAG = "DefaultAudioClientController"
    private val DEFAULT_PORT = 0 // In case the URL does not have port
    private val AUDIO_PORT_OFFSET = 200 // Offset by 200 so that subtraction results in 0
    private val DEFAULT_PRESENTER = true
    private val AUDIO_CLIENT_RESULT_SUCCESS = AudioClient.AUDIO_CLIENT_OK

    private var muteMicAndSpeaker = false
    private val uiScope = CoroutineScope(Dispatchers.Main)
    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioModePreCall: Int = audioManager.mode
    private var speakerphoneStatePreCall: Boolean = audioManager.isSpeakerphoneOn

    companion object {
        var audioClientState = AudioClientState.INITIALIZED
    }

    private fun setUpAudioConfiguration(audioMode: AudioMode) {
        // There seems to be no call that gives us the native input sample rate, so we just use the output rate
        val nativeSR = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_SYSTEM)

        // The HARDWARE_SAMPLERATE is currently used to construct the proper buffer sizes
        audioClient.sendMessage(AudioClient.MESS_SET_HARDWARE_SAMPLE_RATE, nativeSR)

        // This IO_SAMPLE_RATE is used to create OpenSLES:
        val samplingRateConfig = when (audioMode) {
            AudioMode.Mono16K -> 16000
            AudioMode.Mono48K -> 48000
            AudioMode.Stereo48K -> 48000
        }
        audioClient.sendMessage(
            AudioClient.MESS_SET_IO_SAMPLE_RATE,
            samplingRateConfig
        )

        // Result is in bytes, so we divide by 2 (16-bit samples)
        val outputChannelConfig = when (audioMode) {
            AudioMode.Mono16K -> AudioFormat.CHANNEL_OUT_MONO
            AudioMode.Mono48K -> AudioFormat.CHANNEL_OUT_MONO
            AudioMode.Stereo48K -> AudioFormat.CHANNEL_OUT_STEREO
        }
        val spkMinBufSizeInSamples = AudioTrack.getMinBufferSize(
            nativeSR,
            outputChannelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        ) / 2

        val micMinBufSizeInSamples = AudioRecord.getMinBufferSize(
            nativeSR,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) / 2

        logger.info(
            TAG,
            "spkMinBufSizeInSamples $spkMinBufSizeInSamples micMinBufSizeInSamples $micMinBufSizeInSamples"
        )
        audioClient.sendMessage(AudioClient.MESS_SET_MIC_FRAMES_PER_BUFFER, micMinBufSizeInSamples)
        audioClient.sendMessage(AudioClient.MESS_SET_SPK_FRAMES_PER_BUFFER, spkMinBufSizeInSamples)
        audioClient.sendMessage(
            AudioClient.MESS_SET_SPEAKERPHONE_MIC,
            AudioClient.OPENSL_MIC_DEFAULT
        )
        audioClient.sendMessage(AudioClient.MESS_SET_CVP_MODULE_FLAG, AudioClient.CVP_MODULE_NONE)
        audioClient.sendMessage(AudioClient.MESS_SET_CVP_PREF_FLAG, AudioClient.CVP_PREF_NONE)
    }

    override fun getRoute(): Int {
        return audioClient.route
    }

    override fun setRoute(route: Int): Boolean {
        if (getRoute() == route) return true
        logger.info(TAG, "Setting route to $route")

        return audioClient.setRoute(route) == AUDIO_CLIENT_RESULT_SUCCESS
    }

    override fun start(
        audioFallbackUrl: String,
        audioHostUrl: String,
        meetingId: String,
        attendeeId: String,
        joinToken: String,
        audioMode: AudioMode
    ) {
        // Validate audio client state
        if (audioClientState != AudioClientState.INITIALIZED &&
            audioClientState != AudioClientState.STOPPED
        ) {
            logger.warn(
                TAG,
                "Current audio client state $audioClientState is invalid to start audio, ignoring"
            )
            return
        }

        val audioUrlParts: List<String> =
            audioHostUrl.split(":".toRegex()).dropLastWhile { it.isEmpty() }

        val (host: String, portStr: String) = if (audioUrlParts.size == 2) audioUrlParts else listOf(
            audioUrlParts[0],
            "$AUDIO_PORT_OFFSET"
        )

        // We subtract 200 here since audio client will add an offset of 200 for the DTLS port
        val port = try {
            Integer.parseInt(portStr) - AUDIO_PORT_OFFSET
        } catch (exception: Exception) {
            logger.warn(
                TAG,
                "Error parsing int. Using default value. Exception: ${exception.message}"
            )
            DEFAULT_PORT
        }
        setUpAudioConfiguration(audioMode)
        eventAnalyticsController.publishEvent(EventName.meetingStartRequested)
        audioClientObserver.notifyAudioClientObserver { observer ->
            observer.onAudioSessionStartedConnecting(
                false
            )
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        val appInfo = AppInfoUtil.initializeAudioClientAppInfo(context)

        uiScope.launch {
            val audioModeInternal = when (audioMode) {
                AudioMode.Mono16K -> AudioModeInternal.MONO_16K
                AudioMode.Mono48K -> AudioModeInternal.MONO_48K
                AudioMode.Stereo48K -> AudioModeInternal.STEREO_48K
            }
            val res = audioClient.startSessionV2(
                AudioClient.XTL_DEFAULT_TRANSPORT,
                host,
                port,
                joinToken,
                meetingId,
                attendeeId,
                muteMicAndSpeaker,
                muteMicAndSpeaker,
                DEFAULT_PRESENTER,
                audioFallbackUrl,
                null,
                appInfo,
                audioModeInternal
            )

            if (res != AUDIO_CLIENT_RESULT_SUCCESS) {
                logger.error(TAG, "Failed to start audio session. Response code: $res")

                eventAnalyticsController.publishEvent(
                    EventName.meetingStartFailed
                )
            } else {
                logger.info(TAG, "Started audio session.")
                audioClientState = AudioClientState.STARTED
            }
        }
    }

    override fun stop() {
        if (audioClientState != AudioClientState.STARTED) {
            logger.error(
                TAG,
                "Current audio client state $audioClientState is invalid to stop audio, ignoring"
            )
            return
        }

        GlobalScope.launch {
            val res = audioClient.stopSession()

            if (res != AUDIO_CLIENT_RESULT_SUCCESS) {
                logger.error(TAG, "Failed to stop audio session. Response code: $res")
            } else {
                logger.info(TAG, "Stopped audio session.")
                audioClientState = AudioClientState.STOPPED
                resetAudioManager()
                notifyStop()
                audioClientObserver.notifyAudioClientObserver { observer ->
                    observer.onAudioSessionStopped(
                        MeetingSessionStatus(MeetingSessionStatusCode.OK)
                    )
                }
            }
        }
    }

    private fun notifyStop() {
        eventAnalyticsController.publishEvent(
            EventName.meetingEnded,
            mutableMapOf(EventAttributeName.meetingStatus to MeetingSessionStatusCode.OK)
        )
        meetingStatsCollector.resetMeetingStats()
    }

    private fun resetAudioManager() {
        audioManager.apply {
            isBluetoothScoOn = false
            stopBluetoothSco()
        }
        audioManager.mode = audioModePreCall
        audioManager.isSpeakerphoneOn = speakerphoneStatePreCall
    }

    override fun setMute(isMuted: Boolean): Boolean {
        return audioClientState == AudioClientState.STARTED && AudioClient.AUDIO_CLIENT_OK == audioClient.setMicMute(
            isMuted
        )
    }

    override fun setVoiceFocusEnabled(enabled: Boolean): Boolean {
        if (audioClientState == AudioClientState.STARTED) {
            return AudioClient.AUDIO_CLIENT_OK == audioClient.setVoiceFocusNoiseSuppression(enabled)
        } else {
            logger.error(
                TAG,
                "Failed to set VoiceFocus to $enabled; audio client state is $audioClientState"
            )
            return false
        }
    }

    override fun isVoiceFocusEnabled(): Boolean {
        if (audioClientState == AudioClientState.STARTED) {
            return audioClient.getVoiceFocusNoiseSuppression()
        } else {
            logger.error(
                TAG,
                "Failed to get VoiceFocus enabled state; audio client state is $audioClientState"
            )
            return false
        }
    }
}
