package com.wisperlow.mobile.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wisperlow.mobile.R
import com.wisperlow.mobile.ui.MonoFamily
import com.wisperlow.mobile.ui.SansFamily
import com.wisperlow.mobile.ui.SerifFamily

/** Recently copied text as cards: tap to paste, pin to keep, cross to forget. */
@Composable
internal fun ClipboardPanel(clips: List<ClipEntry>, actions: KeyboardActions) {
    val colors = LocalKeyboardColors.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.keyboard_clipboard_title).uppercase(),
                color = colors.stripMuted,
                style = TextStyle(fontFamily = MonoFamily, fontSize = 10.sp, letterSpacing = 1.2.sp),
                modifier = Modifier.weight(1f),
            )
            if (clips.any { !it.pinned }) Chip(stringResource(R.string.keyboard_clipboard_clear), null, false, actions.onClearClips)
        }
        if (clips.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.keyboard_clipboard_empty),
                    color = colors.stripText,
                    style = TextStyle(fontFamily = SerifFamily, fontSize = 24.sp),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.keyboard_clipboard_empty_hint).uppercase(),
                    color = colors.stripMuted,
                    style = TextStyle(fontFamily = MonoFamily, fontSize = 9.sp, letterSpacing = 1.2.sp),
                )
            }
        } else {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                verticalItemSpacing = 6.dp,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(clips, key = { it.text }) { clip -> ClipCard(clip, actions, Modifier.animateItem()) }
            }
        }
        Spacer(Modifier.height(4.dp))
        PanelBottomRow(actions)
    }
}

@Composable
private fun ClipCard(clip: ClipEntry, actions: KeyboardActions, modifier: Modifier) {
    val colors = LocalKeyboardColors.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.key)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.keyboard_paste)) { actions.onPasteText(clip.text) }
            .padding(start = 10.dp, top = 8.dp, end = 2.dp, bottom = 2.dp),
    ) {
        Text(
            clip.text,
            color = colors.keyText,
            style = TextStyle(fontFamily = SansFamily, fontSize = 13.sp, lineHeight = 18.sp),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 8.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(50)).clickable(role = Role.Button) { actions.onTogglePin(clip.text) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (clip.pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                    stringResource(if (clip.pinned) R.string.keyboard_clip_unpin else R.string.keyboard_clip_pin),
                    tint = if (clip.pinned) colors.accent else colors.stripMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
            IconTap(Icons.Rounded.Close, stringResource(R.string.keyboard_clip_delete)) { actions.onDeleteClip(clip.text) }
        }
    }
}
