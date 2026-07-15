package com.scorchedphoto.engine.ai

enum class Difficulty(val angleNoiseDegrees: Float, val powerNoisePercent: Float) {
    EASY(12f, 0.15f),
    MEDIUM(6f, 0.08f),
    HARD(2f, 0.02f),
}
