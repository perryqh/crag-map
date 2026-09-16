package com.perryhertler.cragmap.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.perryhertler.cragmap.data.AppDatabase
import com.perryhertler.cragmap.data.ClimbSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offline FTS search over the bundled climb_fts table (see AppDatabase.searchClimbs).
 * Selecting a result is the direct fix for "how do I get from one area to the
 * next" — it jumps the camera straight to that route's formation instead of
 * requiring breadcrumb navigation through the area hierarchy.
 */
@Composable
fun SearchBar(
    modifier: Modifier = Modifier,
    db: AppDatabase,
    onResultSelected: (ClimbSearchResult) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ClimbSearchResult>>(emptyList()) }

    LaunchedEffect(query) {
        results = if (query.isBlank()) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { db.searchClimbs(query) }
        }
    }

    Column(modifier = modifier.padding(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search routes or grades…") },
            singleLine = true,
            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
        )
        if (results.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(top = 4.dp)
            ) {
                items(results) { result ->
                    Text(
                        text = "${result.name}  ·  ${result.ydsGrade ?: "?"}",
                        modifier = Modifier
                            .clickable {
                                onResultSelected(result)
                                query = ""
                                results = emptyList()
                            }
                            .padding(12.dp)
                    )
                }
            }
        }
    }
}
