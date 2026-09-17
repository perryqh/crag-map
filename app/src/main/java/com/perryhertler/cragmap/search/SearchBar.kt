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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.perryhertler.cragmap.data.AppDatabase
import com.perryhertler.cragmap.data.AreaSearchResult
import com.perryhertler.cragmap.data.ClimbSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Either a climb (name/grade FTS) or an area/formation (plain name match). */
sealed class SearchResultItem {
    data class Climb(val result: ClimbSearchResult) : SearchResultItem()
    data class Area(val result: AreaSearchResult) : SearchResultItem()
}

/**
 * Offline search over both the climb_fts table and area names. Climb-name
 * search alone wasn't enough — you couldn't search "East Rampart" or "Hawk's
 * Nest" by name, only the routes on them, which is exactly the "search that
 * matches how climbers think" gap from the plan this came out of.
 */
@Composable
fun SearchBar(
    modifier: Modifier = Modifier,
    db: AppDatabase,
    onResultSelected: (SearchResultItem) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResultItem>>(emptyList()) }

    LaunchedEffect(query) {
        results = if (query.isBlank()) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                val climbs = db.searchClimbs(query).map { SearchResultItem.Climb(it) }
                val areas = db.areaDao().searchByName(query).map { SearchResultItem.Area(it) }
                // Areas first — searching "East Rampart" should land you on the
                // area itself, not buried under every climb whose name happens
                // to contain those letters.
                areas + climbs
            }
        }
    }

    Column(modifier = modifier.padding(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search routes, walls, or grades…") },
            singleLine = true,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .testTag("search-field")
        )
        if (results.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(top = 4.dp)
            ) {
                items(results) { item ->
                    val (primary, secondary) = when (item) {
                        is SearchResultItem.Climb ->
                            item.result.name to (item.result.ydsGrade ?: "?")
                        is SearchResultItem.Area ->
                            item.result.name to (item.result.parentName ?: "")
                    }
                    Column(
                        modifier = Modifier
                            .clickable {
                                onResultSelected(item)
                                query = ""
                                results = emptyList()
                            }
                            .padding(12.dp)
                    ) {
                        Text(primary)
                        if (secondary.isNotBlank()) {
                            Text(secondary, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}
