package com.scorchedphoto.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Speaks a tank's death-taunt line aloud (see [com.scorchedphoto.app.game.GameRenderer]'s
 * `BurnTaunt`s), in whichever real on-device voice - and pitch/rate on top of it - its
 * player picked during setup. Owns a single shared [TextToSpeech] engine for the whole app
 * process ([Singleton], same lifetime rationale as [com.scorchedphoto.app.ml.TfliteSkySegmentationModel]),
 * since there's never a need for more than one at a time.
 */
@Singleton
class DeathLineSpeaker @Inject constructor(@ApplicationContext context: Context) {

    private data class SpeakRequest(val text: String, val voiceId: String?, val pitch: Float, val speechRate: Float)

    private val lock = Any()
    private var ready = false
    private var counter = 0
    private val pending = ArrayDeque<SpeakRequest>() // guarded by lock

    private val _availableVoices = MutableStateFlow(listOf(VoiceOption.SYSTEM_DEFAULT))

    /** Voices this device's TTS engine actually has, ready to use - starts as just
     * [VoiceOption.SYSTEM_DEFAULT] and updates once the async engine init completes. */
    val availableVoices: StateFlow<List<VoiceOption>> = _availableVoices.asStateFlow()

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        synchronized(lock) {
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                _availableVoices.value = buildVoiceOptions()
                pending.forEach { enqueue(it.text, it.voiceId, it.pitch, it.speechRate) }
            }
            pending.clear()
        }
    }

    /** Safe to call from any thread (see class doc) and before initialization finishes -
     * queues until the engine reports ready. */
    fun speak(text: String, voiceId: String?, pitch: Float, speechRate: Float) {
        synchronized(lock) {
            if (ready) enqueue(text, voiceId, pitch, speechRate) else pending.addLast(SpeakRequest(text, voiceId, pitch, speechRate))
        }
    }

    // Caller holds `lock`: voice+pitch+rate+speak are set as one atomic unit on the single
    // shared engine, since two tanks can die in the same tick (one blast can ignite
    // multiple) and these are shared, per-call state on `tts` - without the lock a second
    // call's voice/pitch could land between the first call's and its own speak().
    private fun enqueue(text: String, voiceId: String?, pitch: Float, speechRate: Float) {
        val resolved = voiceId?.let { id -> tts.voices?.find { it.name == id } } ?: tts.defaultVoice
        resolved?.let { tts.voice = it }
        tts.setPitch(pitch)
        tts.setSpeechRate(speechRate)
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "death-${counter++}")
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
