package tessera.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The pages down the left side, each with its frame count; a dashed zero marks pages to do. */
@Composable
fun PageStrip(session: Session, images: ImageCache, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPalette.current
    @Suppress("UNUSED_VARIABLE") val revision = session.revision
    val state = rememberLazyListState()
    LaunchedEffect(session.pageIndex) {
        val visible = state.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == session.pageIndex && it.offset >= 0 && it.offset + it.size <= state.layoutInfo.viewportEndOffset }) {
            state.animateScrollToItem((session.pageIndex - 1).coerceAtLeast(0))
        }
    }
    val pages = session.pages
    LazyColumn(
        modifier.background(c.paper), state = state,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(pages, key = { i, _ -> i }) { i, page ->
            val current = i == session.pageIndex
            val thumb by rememberThumbnail(images, page.imageHref)
            Column(
                Modifier.clickable { onSelect(i) }.pointerHoverIcon(PointerIcon.Hand),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val shape = RoundedCornerShape(4.dp)
                val ratio = thumb?.let { it.width.toFloat() / it.height } ?: 0.65f
                Box(
                    Modifier.width(84.dp).aspectRatio(ratio).shadow(2.dp, shape).clip(shape).background(c.panel)
                        .border(if (current) 2.dp else 1.dp, if (current) c.accent else c.line, shape),
                ) {
                    thumb?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.Medium) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label(if (page.isCover) Strings.cover else "${i + 1}", color = if (current) c.ink else c.muted, size = 11.sp)
                    CountBadge(page.frames.size)
                }
            }
        }
    }
}
