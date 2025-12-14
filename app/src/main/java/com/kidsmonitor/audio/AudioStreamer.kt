package com.kidsmonitor.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
class AudioStreamer(private val onAudioChunk: (ByteArray) -> Unit) {

    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private var noiseSuppressor: NoiseSuppressor? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var automaticGainControl: AutomaticGainControl? = null

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    private val scope = CoroutineScope(Job() + Dispatchers.IO)

    fun start() {
        if (isRecording) return

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(audioRecord!!.audioSessionId)
            noiseSuppressor?.enabled = true
        }
        
        if (AcousticEchoCanceler.isAvailable()) {
            acousticEchoCanceler = AcousticEchoCanceler.create(audioRecord!!.audioSessionId)
            acousticEchoCanceler?.enabled = true
        }

        if (AutomaticGainControl.isAvailable()) {
            automaticGainControl = AutomaticGainControl.create(audioRecord!!.audioSessionId)
            automaticGainControl?.enabled = true
        }

        isRecording = true
        audioRecord?.startRecording()

        scope.launch {
            val buffer = ByteArray(bufferSize)
            while (this.isActive && isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    onAudioChunk(buffer.copyOf(read))
                }
            }
        }
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false
        noiseSuppressor?.release()
        noiseSuppressor = null
        acousticEchoCanceler?.release()
        acousticEchoCanceler = null
        automaticGainControl?.release()
        automaticGainControl = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        (scope.coroutineContext[Job] as Job).cancel()
    }
}