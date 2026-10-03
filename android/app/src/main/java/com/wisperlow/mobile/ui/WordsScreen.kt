package com.wisperlow.mobile.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.wisperlow.mobile.R

/** Dialog target: null spoken = adding a new word. */
private data class WordEdit(val spoken: String?, val initialSpoken: String, val initialWritten: String)

@Composable
fun WordsScreen(
    words: Map<String, String>,
    contentPadding: PaddingValues,
    onSave: (originalSpoken: String?, spoken: String, written: String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var editing by remember { mutableStateOf<WordEdit?>(null) }
    val sorted = remember(words) { words.entries.sortedBy { it.key } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = screenPadding(contentPadding).let {
                PaddingValues(
                    start = Space.Ml,
                    end = Space.Ml,
                    top = it.calculateTopPadding(),
                    bottom = it.calculateBottomPadding() + 72.dp,
                )
            },
            verticalArrangement = Arrangement.spacedBy(Space.Sm),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Space.Xs), modifier = Modifier.padding(bottom = Space.Xs)) {
                    ScreenTitle(stringResource(R.string.words_title))
                    Text(
                        stringResource(R.string.words_body),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (sorted.isEmpty()) {
                item { EmptyState(Icons.Rounded.Spellcheck, R.string.words_empty_title, R.string.words_empty_body) }
            }
            items(sorted, key = { it.key }) { (spoken, written) ->
                val source = remember { MutableInteractionSource() }
                Surface(
                    onClick = { editing = WordEdit(spoken, spoken, written) },
                    interactionSource = source,
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth().animateItem().pressScale(source),
                ) {
                    Row(Modifier.padding(start = Space.M, top = Space.Xs, bottom = Space.Xs), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(spoken, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(written, style = MaterialTheme.typography.titleMedium)
                        }
                        IconButton(onClick = { onDelete(spoken) }) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { editing = WordEdit(null, "", "") },
            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.words_add)) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = Space.Ml, bottom = contentPadding.calculateBottomPadding() + Space.M),
        )
    }

    editing?.let { edit ->
        var spoken by remember(edit) { mutableStateOf(edit.initialSpoken) }
        var written by remember(edit) { mutableStateOf(edit.initialWritten) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(if (edit.spoken == null) R.string.words_add else R.string.words_edit)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.Sm)) {
                    OutlinedTextField(
                        value = spoken,
                        onValueChange = { spoken = it.replace("\n", " ") },
                        label = { Text(stringResource(R.string.words_spoken)) },
                        placeholder = { Text(stringResource(R.string.words_spoken_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = written,
                        onValueChange = { written = it.replace("\n", " ") },
                        label = { Text(stringResource(R.string.words_written)) },
                        placeholder = { Text(stringResource(R.string.words_written_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = spoken.isNotBlank() && written.isNotBlank(),
                    onClick = {
                        onSave(edit.spoken, spoken, written)
                        editing = null
                    },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
