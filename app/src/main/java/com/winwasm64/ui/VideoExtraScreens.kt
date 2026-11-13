package com.winwasm64.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winwasm64.torrent.VideoFeed
import com.winwasm64.torrent.VideoHistory
import com.winwasm64.torrent.VideoPlay
import com.winwasm64.torrent.VideoPlaylists
import com.winwasm64.ui.browser.CoverCache
import com.winwasm64.ui.theme.Omni
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v4.88.0: открыть ролик из истории/плейлиста тем же встроенным плеером.
 * Логика та же, что в поиске: сначала пробуем прямой поток, иначе плеер площадки.
 */
fun openClipStandalone(
    context: android.content.Context,
    clip: VideoFeed.Clip,
    onNote: (String) -> Unit = {}
) {
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)
    scope.launch {
        onNote("Открываю «${clip.title.take(40)}»…")
        val play = withContext(Dispatchers.IO) { runCatching { VideoPlay.resolve(clip.url) }.getOrNull() }
        if (play == null) {
            onNote("Поток не отдан — открываю страницу ролика на вкладке браузера")
            return@launch
        }
        runCatching {
            context.startActivity(
                Intent(context, NativeVideoPlayerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("url", play.url)
                    .putExtra("title", clip.title)
                    .putExtra("thumbnail", clip.thumb)
                    .putExtra("referer", play.referer)
                    .putExtra("libKey", VideoFeed.keyOf(clip))
            )
        }
        onNote("")
    }
}

/** v4.88.0: история просмотра + история поиска по всем площадкам. */
@Composable
fun VideoHistoryScreen(
    modifier: Modifier = Modifier,
    onOpen: (VideoFeed.Clip) -> Unit,
    onSearchAgain: (String, String) -> Unit,
    onNote: (String) -> Unit = {}
) {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val watch = remember(version) { VideoHistory.watch(ctx) }
    val queries = remember(version) { VideoHistory.queries(ctx) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "История кино · ${watch.size}",
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            if (watch.isNotEmpty()) {
                Text(
                    "очистить",
                    color = Omni.Accent, fontSize = 11.sp,
                    modifier = Modifier.clickable { VideoHistory.clearWatch(ctx); version++ }
                )
            }
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (queries.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "История поиска · ${queries.size}",
                            color = Omni.TextDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "очистить",
                            color = Omni.Accent, fontSize = 10.sp,
                            modifier = Modifier.clickable { VideoHistory.clearQueries(ctx); version++ }
                        )
                    }
                }
                items(queries) { q ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Omni.SurfaceHigh)
                            .clickable { onSearchAgain(q.text, q.source) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🔎", fontSize = 13.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(q.text, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(q.source, color = Omni.TextFaint, fontSize = 10.sp)
                    }
                }
                item { Spacer(Modifier.height(6.dp)) }
            }
            if (watch.isEmpty()) {
                item {
                    Text(
                        "Пока пусто. Как только откроете любой ролик в разделе «Кино», он появится здесь — " +
                            "с площадкой, каналом и временем.",
                        color = Omni.TextDim, fontSize = 12.sp, modifier = Modifier.padding(8.dp)
                    )
                }
            }
            items(watch) { w ->
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Omni.SurfaceHigh)
                        .clickable {
                            onOpen(
                                VideoFeed.Clip(
                                    id = w.key, kind = VideoFeed.CLIP_VIDEO, title = w.title, author = w.author,
                                    lengthText = w.lengthText, viewsText = "", publishedText = "",
                                    thumb = w.thumb, url = w.url, source = w.source
                                )
                            )
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Thumb(w.thumb)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(w.title, color = Color.White, fontSize = 12.sp, maxLines = 2)
                        Text(
                            listOf(w.source, w.author, VideoHistory.whenText(w.at))
                                .filter { it.isNotBlank() }.joinToString(" · "),
                            color = Omni.TextDim, fontSize = 10.sp, maxLines = 1
                        )
                    }
                    Text("✕", color = Omni.TextFaint, fontSize = 13.sp, modifier = Modifier
                        .clickable { VideoHistory.removeWatch(ctx, w.key); version++ }
                        .padding(6.dp))
                }
            }
        }
    }
}

/** v4.88.0: плейлисты по типам видео — со своим названием у каждого. */
@Composable
fun VideoPlaylistsScreen(
    modifier: Modifier = Modifier,
    onOpen: (VideoFeed.Clip) -> Unit
) {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("видео") }
    val lists = remember(version) { VideoPlaylists.list(ctx) }
    var opened by remember { mutableStateOf<String?>(null) }
    val openedItems = remember(version, opened) { opened?.let { VideoPlaylists.items(ctx, it) } ?: emptyList() }

    Column(modifier.fillMaxSize()) {
        if (opened == null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("Название плейлиста", color = Omni.TextFaint, fontSize = 12.sp) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Omni.AccentContainer).clickable {
                        if (VideoPlaylists.create(ctx, name, kind)) { name = ""; version++ }
                    }.padding(horizontal = 12.dp, vertical = 10.dp)
                ) { Text("＋", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("видео", "короткие", "фильмы и сериалы", "трансляции").forEach { k ->
                    Box(
                        Modifier.clip(RoundedCornerShape(9.dp))
                            .background(if (kind == k) Omni.AccentContainer else Omni.SurfaceHighest)
                            .clickable { kind = k }
                            .padding(horizontal = 9.dp, vertical = 6.dp)
                    ) { Text(k, color = Color.White, fontSize = 10.sp) }
                }
            }
            Text(
                "Плейлисты хранятся на устройстве. Добавить ролик: карточка → «🎞 В плейлист».",
                color = Omni.TextFaint, fontSize = 10.sp, modifier = Modifier.padding(10.dp)
            )
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (lists.isEmpty()) {
                    item { Text("Плейлистов пока нет — создайте первый выше.", color = Omni.TextDim, fontSize = 12.sp) }
                }
                items(lists) { pl ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Omni.SurfaceHigh)
                            .clickable { opened = pl.name }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🎞", fontSize = 16.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(pl.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("${pl.kind} · ${pl.count} видео", color = Omni.TextDim, fontSize = 10.sp)
                        }
                        Text("✕", color = Omni.TextFaint, fontSize = 13.sp,
                            modifier = Modifier.clickable { VideoPlaylists.remove(ctx, pl.name); version++ }.padding(6.dp))
                    }
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("←", color = Color.White, fontSize = 18.sp,
                    modifier = Modifier.clickable { opened = null }.padding(6.dp))
                Text(opened.orEmpty(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.weight(1f))
                Text("${openedItems.size}", color = Omni.TextFaint, fontSize = 11.sp)
            }
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (openedItems.isEmpty()) {
                    item { Text("Пусто — добавьте ролики из поиска.", color = Omni.TextDim, fontSize = 12.sp) }
                }
                items(openedItems) { c ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Omni.SurfaceHigh)
                            .clickable { onOpen(c) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Thumb(c.thumb)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.title, color = Color.White, fontSize = 12.sp, maxLines = 2)
                            Text(listOf(c.source, c.author, c.lengthText).filter { it.isNotBlank() }.joinToString(" · "),
                                color = Omni.TextDim, fontSize = 10.sp, maxLines = 1)
                        }
                        Text("✕", color = Omni.TextFaint, fontSize = 13.sp,
                            modifier = Modifier
                                .clickable { VideoPlaylists.removeItem(ctx, opened.orEmpty(), c.id); version++ }
                                .padding(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumb(url: String) {
    val bmp by produceState<android.graphics.Bitmap?>(null, url) {
        value = withContext(Dispatchers.IO) {
            runCatching { CoverCache.load(url, maxSide = 240) }.getOrNull()
        }
    }
    Box(
        Modifier.size(width = 56.dp, height = 38.dp).clip(RoundedCornerShape(8.dp)).background(Omni.SurfaceHighest),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            androidx.compose.foundation.Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else Text("▶", color = Omni.TextFaint, fontSize = 12.sp)
    }
}
