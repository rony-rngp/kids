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

    // FIX: Use a dedicated Job per recording session so stop() + start() can be called multiple
    // times. A fixed Job() on a class-level scope is cancelled after the first stop() and can
    // never launch new coroutines again.
    private var recordingJob: Job? = null

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

        // FIX: Create a fresh Job for each recording session so the scope is always active.
        recordingJob = CoroutineScope(Job() + Dispatchers.IO).launch {
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
        // FIX: Cancel only the current session's job, not a class-level scope.
        recordingJob?.cancel()
        recordingJob = null
    }
}