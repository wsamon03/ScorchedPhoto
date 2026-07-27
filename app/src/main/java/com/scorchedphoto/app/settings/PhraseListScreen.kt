package com.scorchedphoto.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DeathPhrasesScreen(onBack: () -> Unit, viewModel: DeathPhrasesViewModel = hiltViewModel()) {
    PhraseListContent(title = "Death Phrases", viewModel = viewModel, onBack = onBack)
}

@Composable
fun AttackPhrasesScreen(onBack: () -> Unit, viewModel: AttackPhrasesViewModel = hiltViewModel()) {
    PhraseListContent(title = "Attack Phrases", viewModel = viewModel, onBack = onBack)
}

@Composable
private fun PhraseListContent(title: String, viewModel: PhraseListViewModel, onBack: () -> Unit) {
    val phrases by viewModel.phrases.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium)

            if (phrases.isEmpty()) {
                Text(
                    "No phrases yet - tanks will stay silent. Add one below.",
                    modifier = Modifier.padding(top = 16.dp),
                )
            } else if (phrases.none { it.enabled }) {
                Text(
                    "All phrases are disabled - tanks will stay silent.",
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 8.dp),
            ) {
                items(phrases, key = { it.id }) { phrase ->
                    PhraseRow(
                        phrase = phrase,
                        onToggle = { enabled -> viewModel.setEnabled(phrase.id, enabled) },
                        onDelete = { viewModel.deletePhrase(phrase.id) },
                    )
                }
            }

            Button(
                onClick = { showAddDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text("Add Phrase")
            }
            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text("Back")
            }
        }
    }

    if (showAddDialog) {
        AddPhraseDialog(
            onConfirm = { text ->
                viewModel.addPhrase(text)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
private fun PhraseRow(phrase: Phrase, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            phrase.text,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        )
        Switch(checked = phrase.enabled, onCheckedChange = onToggle)
        TextButton(onClick = onDelete) {
            Text("Delete")
        }
    }
}

@Composable
private fun AddPhraseDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Phrase") },
        text = {
            TextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Phrase") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
