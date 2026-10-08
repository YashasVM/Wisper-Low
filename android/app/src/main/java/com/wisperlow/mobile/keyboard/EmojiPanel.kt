package com.wisperlow.mobile.keyboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wisperlow.mobile.R
import com.wisperlow.mobile.ui.MonoFamily
import kotlinx.coroutines.launch

private class EmojiSection(val label: Int, val icon: String?, val emoji: List<String>)

private sealed interface EmojiItem {
    class Header(val section: Int) : EmojiItem
    class Glyph(val emoji: String, val section: Int) : EmojiItem
}

/**
 * Every category in one continuous list with section headers. The tabs follow your
 * scroll, and tapping a tab glides to its section.
 */
@Composable
internal fun EmojiPanel(recent: List<String>, actions: KeyboardActions) {
    val colors = LocalKeyboardColors.current
    val sections = remember(recent) {
        buildList {
            if (recent.isNotEmpty()) add(EmojiSection(R.string.keyboard_emoji_recent, null, recent))
            Emoji.categories.forEach { add(EmojiSection(it.label, it.icon, it.emoji)) }
        }
    }
    val items = remember(sections) {
        buildList<EmojiItem> {
            sections.forEachIndexed { i, s ->
                add(EmojiItem.Header(i))
                s.emoji.forEach { add(EmojiItem.Glyph(it, i)) }
            }
        }
    }
    val headerAt = remember(items) { items.indices.filter { items[it] is EmojiItem.Header } }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val current by remember(items) {
        derivedStateOf {
            when (val item = items.getOrNull(grid.firstVisibleItemIndex)) {
                is EmojiItem.Header -> item.section
                is EmojiItem.Glyph -> item.section
                null -> 0
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            sections.forEachIndexed { i, section ->
                val selected = i == current
                val bg by animateColorAsState(if (selected) colors.accentContainer else Color.Transparent, tween(200), label = "tab")
                val name = stringResource(section.label)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(2.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(bg)
                        .clickable(role = Role.Tab) { scope.launch { grid.animateScrollToItem(headerAt[i]) } }
                        .semantics { contentDescription = name },
                    contentAlignment = Alignment.Center,
                ) {
                    if (section.icon == null) {
                        Icon(
                            Icons.Outlined.History,
                            null,
                            tint = if (selected) colors.onAccentContainer else colors.stripMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Text(section.icon, fontSize = 17.sp, modifier = Modifier.graphicsLayer { alpha = if (selected) 1f else 0.5f })
                    }
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(46.dp),
            state = grid,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp),
        ) {
            items.forEachIndexed { index, item ->
                when (item) {
                    is EmojiItem.Header -> item(key = "h$index", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResource(sections[item.section].label).uppercase(),
                            color = colors.stripMuted,
                            style = TextStyle(fontFamily = MonoFamily, fontSize = 10.sp, letterSpacing = 1.2.sp),
                            modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp),
                        )
                    }
                    is EmojiItem.Glyph -> item(key = "e$index") {
                        Box(
                            Modifier.height(46.dp).clip(RoundedCornerShape(12.dp)).clickable { actions.onEmoji(item.emoji) },
                            contentAlignment = Alignment.Center,
                        ) { Text(item.emoji, fontSize = 27.sp) }
                    }
                }
            }
        }
        PanelBottomRow(actions)
    }
}
