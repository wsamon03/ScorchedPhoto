package com.scorchedphoto.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Shared enable/disable/add/remove logic for one [PhraseCategory]'s list, backing both
 * [DeathPhrasesViewModel] and [AttackPhrasesViewModel] - each concrete subclass just fixes
 * which category it manages, since a Hilt `@HiltViewModel` can't take a runtime-chosen
 * category itself without assisted injection. */
abstract class PhraseListViewModel(
    private val category: PhraseCategory,
    private val repository: PhraseRepository,
) : ViewModel() {

    val phrases: StateFlow<List<Phrase>> = repository.observePhrases(category)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addPhrase(text: String) {
        viewModelScope.launch { repository.addPhrase(category, text) }
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(id, enabled) }
    }

    fun deletePhrase(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }
}

@HiltViewModel
class DeathPhrasesViewModel @Inject constructor(repository: PhraseRepository) :
    PhraseListViewModel(PhraseCategory.DEATH, repository)

@HiltViewModel
class AttackPhrasesViewModel @Inject constructor(repository: PhraseRepository) :
    PhraseListViewModel(PhraseCategory.ATTACK, repository)
