package com.santiago43rus.rupoop.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.santiago43rus.rupoop.components.VideoCardItem
import com.santiago43rus.rupoop.components.VideoCardShimmer
import com.santiago43rus.rupoop.data.*
import com.santiago43rus.rupoop.util.extractId
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainFeedScreen(
    videos: List<SearchResult>,
    userRegistry: UserRegistry,
    isRefreshing: Boolean,
    isLoadingMore: Boolean,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onVideoClick: (SearchResult, List<SearchResult>) -> Unit,
    onAuthorClick: (Author) -> Unit,
    onMoreClick: (SearchResult, String) -> Unit,
    availableGenres: List<String>,
    selectedGenre: String?,
    onGenreSelected: (String?) -> Unit,
    listState: LazyGridState = rememberLazyGridState()
) {
    val config = LocalConfiguration.current
    val columns = when {
        config.screenWidthDp >= 900 -> 3
        config.screenWidthDp >= 600 -> 2
        else -> 1
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = rememberPullToRefreshState()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Genre filter chips
            if (availableGenres.isNotEmpty()) {
                Surface(tonalElevation = 1.dp, color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = selectedGenre == null,
                            onClick = { onGenreSelected(null) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.onBackground,
                                selectedLabelColor = MaterialTheme.colorScheme.background
                            ),
                            label = { Text("Все", fontSize = 13.sp) }
                        )
                        availableGenres.forEach { genre ->
                            FilterChip(
                                selected = selectedGenre == genre,
                                onClick = { onGenreSelected(genre) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.onBackground,
                                    selectedLabelColor = MaterialTheme.colorScheme.background
                                ),
                                label = { Text(genre, fontSize = 13.sp) }
                            )
                        }
                    }
                }
            }

            if (videos.isEmpty()) {
                if (isRefreshing) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = if (columns == 1) PaddingValues(bottom = 16.dp) else PaddingValues(12.dp),
                        horizontalArrangement = if (columns == 1) Arrangement.spacedBy(0.dp) else Arrangement.spacedBy(16.dp),
                        verticalArrangement = if (columns == 1) Arrangement.spacedBy(0.dp) else Arrangement.spacedBy(12.dp)
                    ) {
                        val shimmerCount = if (columns > 1) columns * 4 else 6
                        items(shimmerCount) {
                            VideoCardShimmer()
                        }
                    }
                } else {
                    com.santiago43rus.rupoop.components.ErrorPlaceholderView(
                        modifier = Modifier.fillMaxSize(),
                        title = "Не удалось загрузить",
                        message = "Проверьте подключение к интернету и повторите попытку",
                        onRetry = onRefresh
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    state = listState,
                    contentPadding = if (columns == 1) PaddingValues(bottom = 16.dp) else PaddingValues(12.dp),
                    horizontalArrangement = if (columns == 1) Arrangement.spacedBy(0.dp) else Arrangement.spacedBy(16.dp),
                    verticalArrangement = if (columns == 1) Arrangement.spacedBy(0.dp) else Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(videos) { index, video ->
                        val history = userRegistry.watchHistory.find { extractId(video.videoUrl) == it.videoId }
                        VideoCardItem(
                            video = video,
                            history = history,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onVideoClick(video, videos) },
                            onAuthorClick = onAuthorClick,
                            onMoreClick = { action -> onMoreClick(video, action) }
                        )
                        if (index == videos.lastIndex && !isLoadingMore) {
                            LaunchedEffect(video.videoUrl) { onLoadMore() }
                        }
                    }
                    if (isLoadingMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color.Red)
                            }
                        }
                    }
                }
            }
        }
    }
}
