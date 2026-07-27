package com.scorchedphoto.app.settings

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhraseRepository @Inject constructor(
    private val dao: PhraseDao,
) {
    fun observePhrases(category: PhraseCategory): Flow<List<Phrase>> = dao.observeByCategory(category.name)

    suspend fun addPhrase(category: PhraseCategory, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        dao.insert(Phrase(category = category.name, text = trimmed))
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun delete(id: Long) = dao.deleteById(id)

    /** The lines a tank in [category] can actually say right now - empty if every phrase in
     * that category is disabled or none exist, in which case the tank stays silent (see
     * [com.scorchedphoto.app.game.GameRenderer]/[com.scorchedphoto.app.game.GameLoopThread]). */
    suspend fun getEnabledTexts(category: PhraseCategory): List<String> = dao.getEnabledTexts(category.name)
}
