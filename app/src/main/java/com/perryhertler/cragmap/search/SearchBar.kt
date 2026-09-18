package com.perryhertler.cragmap.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * Nest" by name, only the routes on them.
 *
 * Recent queries persist lightly in SharedPreferences. A query that looks
 * like a grade (5.8 / 5.10a / V3) prefers climb grade matches.
 */
@Composable
fun SearchBar(
    modifier: Modifier = Modifier,
    db: AppDatabase,
    onResultSelected: (SearchResultItem) -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResultItem>>(emptyList()) }
    var recent by remember { mutableStateOf(loadRecentSearches(context)) }
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        results = if (query.isBlank()) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                val climbs = db.searchClimbs(query).map { SearchResultItem.Climb(it) }
                val areas = db.areaDao().searchByName(query).map { SearchResultItem.Area(it) }
                if (looksLikeGradeQuery(query)) {
                    // Grade-shaped query: climbs first, areas unlikely to help.
                    climbs + areas
                } else {
                    // Areas first — searching "East Rampart" should land on the wall.
                    areas + climbs
                }
            }
        }
    }

    fun commitSelection(item: SearchResultItem) {
        if (query.isNotBlank()) {
            rememberSearchQuery(context, query)
            recent = loadRecentSearches(context)
        }
        onResultSelected(item)
        query = ""
        results = emptyList()
        focused = false
    }

    Column(modifier = modifier.padding(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                focused = true
            },
            placeholder = { Text("Search routes, walls, or grades…") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .testTag("search-field"),
        )
        if (focused && query.isBlank() && recent.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(top = 4.dp),
            ) {
                item {
                    Text(
                        "Recent",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                items(recent) { past ->
                    Text(
                        text = past,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                query = past
                                focused = true
                            }
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                    )
                }
            }
        }
        if (results.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(top = 4.dp),
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
                            .fillMaxWidth()
                            .clickable { commitSelection(item) }
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                    ) {
                        Text(primary)
                        if (secondary.isNotBlank()) {
                            Text(secondary, color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
