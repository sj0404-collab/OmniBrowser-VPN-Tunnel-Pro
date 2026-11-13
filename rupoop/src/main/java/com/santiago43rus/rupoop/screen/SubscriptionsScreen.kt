package com.santiago43rus.rupoop.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.santiago43rus.rupoop.data.Author
import com.santiago43rus.rupoop.data.SearchResult
import com.santiago43rus.rupoop.data.UserRegistry

/**
 * v4.92.0: «Подписки» — это подписки, а не вторая лента видео.
 *
 * Раньше здесь сверху висели аватарки каналов, а ниже та же лента видео — отсюда
 * «два раза один канал» и «видео самого канала там, где должны быть подписки».
 * Теперь:
 *  • вкладки по площадкам: Все · YouTube · ВКонтакте · RuTube · Одноклассники · Lordfilm;
 *  • в каждой вкладке — только каналы этой площадки (один канал = одна карточка);
 *  • во вкладке YouTube есть кнопка «Взять мои подписки с аккаунта»: вход проверяется
 *    по браузеру приложения (тот же аккаунт, что вы уже открыли), VK/RuTube входа не требуют;
 *  • нажатие на канал открывает его видео.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    userRegistry: UserRegistry,
    subscriptionVideos: List<SearchResult>,
    isRefreshing: Boolean,
    isLoadingMore: Boolean,
    hasMoreVideos: Boolean,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onVideoClick: (SearchResult, List<SearchResult>) -> Unit,
    onAuthorClick: (Author) -> Unit,
    onMoreClick: (SearchResult, String) -> Unit,
    importBusy: Boolean = false,
    importNote: String = "",
    onImportYouTube: () -> Unit = {},
    onUnsubscribe: (Author) -> Unit = {},
    listState: LazyGridState = rememberLazyGridState()
) {
    val config = LocalConfiguration.current
    val columns = when {
        config.screenWidthDp >= 900 -> 4
        config.screenWidthDp >= 600 -> 3
        else -> 2
    }

    val subs = userRegistry.subscriptions.distinctBy { keyOf(it) }
    val platforms = remember(subs) {
        subs.map { platformKey(it) }.distinct()
    }
    var selected by remember { mutableStateOf("ВСЕ") }
    LaunchedEffect(platforms) {
        if (selected != "ВСЕ" && platforms.none { it == selected }) selected = "ВСЕ"
    }

    val visible = if (selected == "ВСЕ") subs else subs.filter { platformKey(it) == selected }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = rememberPullToRefreshState()
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── вкладки площадок ──
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val tabs = mutableListOf("ВСЕ" to subs.size)
                    platforms.forEach { p ->
                        tabs.add(p to subs.count { platformKey(it) == p })
                    }
                    items(tabs) { (p, count) ->
                        val active = selected == p
                        Surface(
                            color = if (active) MaterialTheme.colorScheme.onBackground
                            else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (active) MaterialTheme.colorScheme.background
                            else MaterialTheme.colorScheme.onSurface,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.clickable { selected = p }
                        ) {
                            Text(
                                "${platformTitle(p)}" + if (count > 0) " · $count" else "",
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            // ── блок YouTube-аккаунта ──
            if (selected == "ВСЕ" || selected == "YOUTUBE") {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "Подписки YouTube из аккаунта",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                "Вход берётся из браузера приложения — отдельно входить не нужно. " +
                                    "ВКонтакте, RuTube и ОК работают без входа.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            if (importNote.isNotBlank()) {
                                Text(
                                    importNote,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                            Button(
                                onClick = onImportYouTube,
                                enabled = !importBusy,
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                modifier = Modifier.padding(top = 8.dp).height(34.dp)
                            ) {
                                Text(
                                    if (importBusy) "Загружаю…" else "Взять мои подписки",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            if (visible.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    com.santiago43rus.rupoop.components.EmptyPlaceholderView(
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        title = if (selected == "ВСЕ") "Пока нет подписок" else "Нет подписок ${platformTitle(selected)}",
                        message = "Подпишитесь на канал в поиске «Кино» или возьмите свои подписки YouTube из аккаунта"
                    )
                }
            }

            items(visible) { author ->
                ChannelCard(
                    author = author,
                    onClick = { onAuthorClick(author) },
                    onLongClick = { onUnsubscribe(author) }
                )
            }
        }
    }
}

@Composable
private fun ChannelCard(author: Author, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AsyncImage(
            model = author.avatarUrl ?: "",
            contentDescription = null,
            modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.Gray)
        )
        Text(
            author.name,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            platformTitle(platformKey(author)),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

// ── площадки ──

internal fun platformKey(author: Author): String {
    val p = author.platform.orEmpty().uppercase()
    val url = author.url.orEmpty().lowercase()
    val avatar = author.avatarUrl.orEmpty().lowercase()
    return when {
        p.startsWith("YOUTUBE") || url.contains("youtube.com") || url.contains("youtu.be") -> "YOUTUBE"
        p.contains("LORDFILM") || url.contains("lordfilm") || avatar.contains("lordfilm") -> "LORDFILM"
        p.contains("VK") || url.contains("vk.com") || url.contains("vkvideo") -> "VK"
        p.contains("OK") || url.contains("ok.ru") || avatar.contains("ok.ru") -> "OK"
        p.contains("RUTUBE") || url.contains("rutube") -> "RUTUBE"
        else -> "ДРУГОЕ"
    }
}

internal fun platformTitle(key: String): String = when (key) {
    "YOUTUBE" -> "YouTube"
    "VK" -> "ВКонтакте"
    "RUTUBE" -> "RuTube"
    "OK" -> "Одноклассники"
    "LORDFILM" -> "Lordfilm"
    "ВСЕ" -> "Все"
    else -> "Другое"
}

internal fun keyOf(author: Author): String =
    platformKey(author) + "|" + author.name.lowercase().trim()
