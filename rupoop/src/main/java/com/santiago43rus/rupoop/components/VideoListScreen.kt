package com.santiago43rus.rupoop.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.santiago43rus.rupoop.data.Author
import com.santiago43rus.rupoop.data.SearchResult
import com.santiago43rus.rupoop.data.SearchSource
import com.santiago43rus.rupoop.network.PlatformSearchEngineResolver
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll

/**
 * Playlist-style video list (Liked / Watch Later / Playlists).
 *
 * Fix for "ищешь в плейлисте одного вк, а поиск в рутуб":
 * - локальный поиск по названию видео в списке;
 * - chips источника: Все / Rutube / ВКонтакте / Одноклассники / Lordfilm
 *   фильтруют список по platform видео (author.platform или URL);
 * - если все видео плейлиста из одной платформы — источник подставляется автоматически.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoListScreen(
    videos: List<SearchResult>,
    onVideoClick: (SearchResult) -> Unit,
    onAuthorClick: (Author) -> Unit,
    onShare: (SearchResult) -> Unit,
    onRemove: (SearchResult) -> Unit,
    onDownload: (SearchResult) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf(SearchSource.ALL) }

    // Если все видео плейлиста из одной платформы — подставляем её как источник по умолчанию
    LaunchedEffect(videos) {
        if (videos.isEmpty()) return@LaunchedEffect
        val platforms = videos.mapNotNull { it.author?.platform?.uppercase() }
            .ifEmpty { videos.map { PlatformSearchEngineResolver.getPlatformDisplayName(it.videoUrl).uppercase() } }
        if (platforms.distinct().size == 1) {
            selectedSource = SearchSource.entries.firstOrNull { it.name == platforms.first() } ?: SearchSource.ALL
        } else {
            selectedSource = SearchSource.ALL
        }
    }

    val filtered = remember(videos, query, selectedSource) {
        val q = query.trim().lowercase()
        videos.filter { video ->
            if (selectedSource != SearchSource.ALL) {
                val platform = video.author?.platform?.uppercase()
                    ?: PlatformSearchEngineResolver.getPlatformDisplayName(video.videoUrl).uppercase()
                if (platform != selectedSource.name) return@filter false
            }
            if (q.isBlank()) return@filter true
            video.title.lowercase().contains(q) ||
                video.author?.name?.lowercase()?.contains(q) == true
        }
    }

    if (videos.isEmpty()) {
        EmptyPlaceholderView(
            modifier = Modifier.fillMaxSize(),
            title = "Список пуст",
            message = "В этом разделе пока нет добавленных видео"
        )
    } else {
        val config = LocalConfiguration.current
        val columns = when {
            config.screenWidthDp >= 900 -> 3
            config.screenWidthDp >= 600 -> 2
            else -> 1
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Поиск + chips источника
            Surface(
                tonalElevation = 1.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Поиск в списке…") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors()
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SearchSource.entries.forEach { source ->
                            val isSelected = selectedSource == source
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedSource = source },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.onBackground,
                                    selectedLabelColor = MaterialTheme.colorScheme.background
                                ),
                                label = { Text(source.displayName, fontSize = 13.sp) }
                            )
                        }
                    }
                }
            }

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isNotBlank()) "Ничего не найдено по «$query»" else "Нет видео на этой платформе",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = if (columns == 1) PaddingValues(bottom = 16.dp) else PaddingValues(12.dp),
                    horizontalArrangement = if (columns == 1) Arrangement.spacedBy(0.dp) else Arrangement.spacedBy(16.dp),
                    verticalArrangement = if (columns == 1) Arrangement.spacedBy(0.dp) else Arrangement.spacedBy(12.dp)
                ) {
                    items(filtered) { video ->
                        VideoCardItem(
                            video = video, history = null,
                            onClick = { onVideoClick(video) },
                            onAuthorClick = onAuthorClick,
                            onMoreClick = { action ->
                                when (action) {
                                    "remove" -> onRemove(video)
                                }
                            },
                            isEditMode = true,
                            isEditOnlyMode = true
                        )
                    }
                }
            }
        }
    }
}
