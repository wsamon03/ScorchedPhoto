package com.scorchedphoto.app.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.scorchedphoto.app.settings.AudioSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Speaks a tank's death/pre-fire taunt line aloud (see [com.scorchedphoto.app.settings.Phrase]),
 * in whichever real on-device voice - and pitch/rate on top of it - its player picked during
 * setup. Owns a single shared [TextToSpeech] engine for the whole app process ([Singleton],
 * same lifetime rationale as [com.scorchedphoto.app.ml.TfliteSkySegmentationModel]), since
 * there's never a need for more than one at a time.
 */
@Singleton
class DeathLineSpeaker @Inject constructor(
    @ApplicationContext context: Context,
    audioSettingsRepository: AudioSettingsRepository,
) {

    private data class SpeakRequest(val text: String, val voiceId: String?, val pitch: Float, val speechRate: Float)

    private val lock = Any()
    private var ready = false
    private var counter = 0
    private val pending = ArrayDeque<SpeakRequest>() // guarded by lock

    // enqueue() makes several synchronous TextToSpeech calls, each a Binder round-trip to the
    // separate TTS engine process (setVoice in particular is known to be expensive on real
    // devices) - speak() is called from GameLoopThread's render thread (once per shot, once
    // per death, the latter while the Surface canvas is locked), so that work must never run
    // on the caller's thread. A single-thread executor serializes every enqueue() call (from
    // speak() and from the init-flush below) in submission order, which is what preserves the
    // "voice+pitch+rate+speak as one atomic unit" guarantee this class has always promised -
    // just moved off the caller's thread instead of behind a lock held on it.
    private val speechExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DeathLineSpeaker-TTS").apply { isDaemon = true }
    }

    // Read on speechExecutor (see enqueue), written from the settings collector below -
    // plain @Volatile so enqueue() never needs to suspend.
    @Volatile private var voiceVolume = 1f

    private val _availableVoices = MutableStateFlow(listOf(VoiceOption.SYSTEM_DEFAULT))

    /** Voices this device's TTS engine actually has, ready to use - starts as just
     * [VoiceOption.SYSTEM_DEFAULT] and updates once the async engine init completes. */
    val availableVoices: StateFlow<List<VoiceOption>> = _availableVoices.asStateFlow()

    // Counts utterances currently between onStart and onDone/onError, so isSpeaking stays
    // true across an in-game moment where two tanks' lines briefly overlap in the queue -
    // only the last one finishing should flip it back to false. Updated from whichever
    // thread the TTS engine calls the listener back on (its own internal thread, not
    // speechExecutor), so a plain Int would race; StateFlow.value writes are their own
    // synchronization point, but the increment/decrement itself still needs to be atomic.
    private val activeUtteranceCount = AtomicInteger(0)
    private val _isSpeaking = MutableStateFlow(false)

    /** True while any queued line (a death/pre-fire taunt, or a setup-screen voice test) is
     * actively being spoken - see [GameSetupViewModel.isTestingVoice], which uses this to
     * grey out every "Test" button while one is in progress. */
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        synchronized(lock) {
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                _availableVoices.value = buildVoiceOptions()
                val toFlush = pending.toList()
                pending.clear()
                speechExecutor.execute { toFlush.forEach { enqueue(it.text, it.voiceId, it.pitch, it.speechRate) } }
            } else {
                pending.clear()
            }
        }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                activeUtteranceCount.incrementAndGet()
                _isSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                if (activeUtteranceCount.decrementAndGet() <= 0) _isSpeaking.value = false
            }

            // The single-arg overload is deprecated in favor of onError(String, int), but it's
            // still abstract on UtteranceProgressListener itself, so it must be implemented.
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) {
                if (activeUtteranceCount.decrementAndGet() <= 0) _isSpeaking.value = false
            }
        })
    }

    init {
        // Lives for the app process, same as this singleton itself - never cancelled, same
        // rationale as speechExecutor above.
        CoroutineScope(Dispatchers.Default).launch {
            audioSettingsRepository.settings.collect { voiceVolume = it.voiceVolume }
        }
    }

    /** Safe to call from any thread (see class doc) and before initialization finishes -
     * queues until the engine reports ready. */
    fun speak(text: String, voiceId: String?, pitch: Float, speechRate: Float) {
        synchronized(lock) {
            if (ready) {
                speechExecutor.execute { enqueue(text, voiceId, pitch, speechRate) }
            } else {
                pending.addLast(SpeakRequest(text, voiceId, pitch, speechRate))
            }
        }
    }

    // Only ever invoked on speechExecutor, which is single-threaded - every call (from speak()
    // or the init-flush above) is therefore automatically serialized in submission order, so
    // voice+pitch+rate+speak land on the shared `tts` engine as one atomic unit even when two
    // tanks die in the same tick (one blast can ignite multiple).
    private fun enqueue(text: String, voiceId: String?, pitch: Float, speechRate: Float) {
        val resolved = voiceId?.let { id -> tts.voices?.find { it.name == id } } ?: tts.defaultVoice
        resolved?.let { tts.voice = it }
        tts.setPitch(pitch)
        tts.setSpeechRate(speechRate)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, voiceVolume) }
        tts.speak(text, TextToSpeech.QUEUE_ADD, params, "death-${counter++}")
    }

    /**
     * Only voices matching the device's current language are offered - this app's taunts
     * are English text, and a voice for an unrelated language would mangle pronunciation
     * rather than just "sound different." Voices flagged as needing a network data
     * download the user hasn't done yet ([TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED])
     * are excluded too, since speaking with one would just fail until that download
     * happens. Always includes [VoiceOption.SYSTEM_DEFAULT], so this is never empty.
     */
    private fun buildVoiceOptions(): List<VoiceOption> {
        val systemLanguage = Locale.getDefault().language
        val usable = (tts.voices ?: emptySet()).filter { voice ->
            voice.locale.language == systemLanguage &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features
        }
        val ranked = usable.sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })
        return listOf(VoiceOption.SYSTEM_DEFAULT) +
            ranked.mapIndexed { index, voice -> VoiceOption(voice.name, friendlyName(voice, index)) }
    }

    private fun friendlyName(voice: Voice, index: Int): String =
        "Voice ${index + 1} (${voice.locale.displayName}, ${qualityLabel(voice.quality)})"

    private fun qualityLabel(quality: Int): String = when {
        quality >= Voice.QUALITY_VERY_HIGH -> "very high quality"
        quality >= Voice.QUALITY_HIGH -> "high quality"
        quality >= Voice.QUALITY_NORMAL -> "normal quality"
        quality >= Voice.QUALITY_LOW -> "low quality"
        else -> "very low quality"
    }
}
