package com.scorchedphoto.app.tts

/**
 * One selectable entry in a voice picker: either a real device [android.speech.tts.Voice]
 * ([id] is that voice's [android.speech.tts.Voice.name], an opaque-but-stable per-device
 * engine identifier) or [SYSTEM_DEFAULT] ([id] null), meaning "whatever the engine already
 * defaults to." Kept as a plain id/displayName pair rather than wrapping the SDK `Voice`
 * type directly, so callers that just need to persist/compare a choice (e.g. [com.scorchedphoto.app.setup.TankConfig])
 * don't need to depend on `android.speech.tts`.
 */
data class VoiceOption(val id: String?, val displayName: String) {
    companion object {
        val SYSTEM_DEFAULT = VoiceOption(id = null, displayName = "Default")

        /** Selectable "no voice" choice: a tank set to this never has its death line
         * spoken. Uses a sentinel id (rather than null, which already means
         * [SYSTEM_DEFAULT]) so the two stay distinguishable through [TankConfig]. */
        val NONE = VoiceOption(id = "__none__", displayName = "None")
    }
}
