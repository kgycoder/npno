package com.sync.app.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.RoundedRectangle
import kotlin.math.roundToInt

/*
 * 플레이리스트 곡 "edit 상태" 의 Liquid Glass.
 *
 *  · EditTile : 길게 눌러 edit 에 들어간 곡 UI.  기존 글래스(미니 플레이어/내비)와 같은 Kyant 효과
 *               (vibrancy + blur 2dp + lens) 로 그린다. 위치는 DOM 행(.pl-track.editing)을 따라가며(드래그 포함)
 *               터치는 가로채지 않는다 → 길게 누른 손가락의 드래그/스크롤은 그대로 WebView(app.js)가 처리한다.
 *  · EditMenu : 곡 UI 옆(바로 아래/위)에 나타나는 컨텍스트 메뉴 — 기존 "플레이리스트에 추가" 메뉴와 같은
 *               dialogGlass 효과(colorControls + blur + lens(depthEffect) + Highlight.Plain). 항목: 곡 삭제 / 곡 즐겨찾기.
 *
 * 두 표면 모두 WebView 문서 스크롤을 검색 캡슐과 같은 방식(레이아웃 + 드로우 단계 보정)으로 1:1 추적한다.
 */

/** 값이 사라져도 마지막 값을 유지한 채 alpha 를 0 으로 내려, 페이드 아웃 동안 계속 그릴 수 있게 한다. */
@Composable
private fun <T : Any> rememberFaded(value: T?): Pair<T?, Float> {
    val hold = remember { arrayOfNulls<Any>(1) }
    if (value != null) hold[0] = value
    val a by animateFloatAsState(
        targetValue = if (value != null) 1f else 0f,
        animationSpec = tween(180),
        label = "editFade"
    )
    @Suppress("UNCHECKED_CAST")
    val shown = (value ?: hold[0]) as T?
    return (if (value != null || a > 0.01f) shown else null) to a
}

@Composable
internal fun EditGlass(e: EditState?, backdrop: Backdrop, c: GlassController) {
    val (tile, tileFade) = rememberFaded(e)
    val (menu, menuFade) = rememberFaded(e?.menu)
    if (tile != null) EditTile(tile, tileFade, backdrop, c)
    if (menu != null) EditMenu(menu, menuFade, backdrop, c)
}

/* ─────────────── 문서 스크롤 추적 (SearchGlass 와 동일한 기법) ─────────────── */

private class EditPlacement { @JvmField var topPx: Int = 0 }

private fun Modifier.editRect(c: GlassController, r: CssRect, placed: EditPlacement): Modifier = this
    .offset {
        val top = ((r.y - c.cssScroll(density)) * density).roundToInt()
        placed.topPx = top
        IntOffset((r.x * density).roundToInt(), top)
    }
    .size(r.w.dp, r.h.dp)

private fun DrawScope.editDrawDelta(c: GlassController, r: CssRect, placed: EditPlacement): Float =
    (r.y - c.cssScroll(density)) * density - placed.topPx

/** 레이아웃이 아는 건 직전 프레임의 스크롤뿐이므로, 그린 위치와 샘플링 위치의 차이를 드로우 단계에서 보정한다. */
private class EditScrollBackdrop(
    private val inner: Backdrop,
    private val delta: DrawScope.() -> Float
) : Backdrop {

    override val isCoordinatesDependent: Boolean get() = inner.isCoordinatesDependent

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val dy = delta(this)
        withTransform({ translate(0f, -dy) }) {
            with(inner) { drawBackdrop(density, coordinates, layerBlock) }
        }
    }
}

/* ─────────────── edit 타일 ─────────────── */

@Composable
private fun EditTile(t: EditState, fade: Float, backdrop: Backdrop, c: GlassController) {
    val art = rememberRemoteImage(t.art)
    // 드래그 중에는 살짝 떠오르는(확대) 느낌
    val lift by animateFloatAsState(
        targetValue = if (t.dragging) 1f else 0f,
        animationSpec = spring(0.6f, 420f),
        label = "editLift"
    )
    val placed = remember { EditPlacement() }
    val live = remember(backdrop, c, t.rect, placed) {
        EditScrollBackdrop(backdrop) { editDrawDelta(c, t.rect, placed) }
    }
    Row(
        Modifier
            .editRect(c, t.rect, placed)
            .drawWithContent {
                val dy = editDrawDelta(c, t.rect, placed)
                translate(top = dy) { this@drawWithContent.drawContent() }
            }
            .drawBackdrop(
                backdrop = live,
                shape = { RoundedRectangle(18.dp) },
                effects = {
                    vibrancy()
                    blur(2f.dp.toPx())
                    lens(12f.dp.toPx(), 24f.dp.toPx())
                },
                layerBlock = {
                    val f = fade.coerceIn(0f, 1f)
                    val s = lerp(0.94f, 1f, f) * (1f + 0.03f * lift)
                    scaleX = s; scaleY = s; alpha = f
                },
                onDrawSurface = { drawRect(GlassTheme.Container) }
            )
            .padding(start = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Color.White.copy(0.08f))
        ) {
            if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(
            Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically)
        ) {
            BasicText(
                t.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(Color.White.copy(0.96f), 13.5f.sp, FontWeight.SemiBold)
            )
            BasicText(
                t.channel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(Color.White.copy(0.55f), 11.sp)
            )
        }
        BasicText(
            t.dur, Modifier.padding(end = 12.dp),
            style = TextStyle(Color.White.copy(0.55f), 11.sp)
        )
        // 드래그 핸들 (위/아래로 끌어 순서 변경)
        Canvas(Modifier.size(width = 16.dp, height = 12.dp)) {
            val sw = 1.8f.dp.toPx()
            for (i in 0..2) {
                val y = sw / 2f + (size.height - sw) * (i / 2f)
                drawLine(
                    Color.White.copy(0.6f), Offset(sw / 2f, y), Offset(size.width - sw / 2f, y),
                    strokeWidth = sw, cap = StrokeCap.Round
                )
            }
        }
    }
}

/* ─────────────── 컨텍스트 메뉴 ─────────────── */

private val EditNoTouchThrough: Modifier = Modifier.pointerInput(Unit) {
    // 메뉴 본체의 탭이 뒤쪽 DOM(곡 목록)으로 새지 않게 소비
    detectTapGestures { }
}

private val TrashIcon by lazy {
    fun p(d: String) = PathParser().parsePathString(d).toPath()
    listOf(
        p("M2.5 4h11"), p("M6 4V2.5h4V4"),
        p("M4 4l.7 9.2a1 1 0 0 0 1 .8h4.6a1 1 0 0 0 1-.8L12 4"),
        p("M6.5 7v4"), p("M9.5 7v4")
    )
}

@Composable
private fun EditMenu(m: EditMenuState, fade: Float, backdrop: Backdrop, c: GlassController) {
    val placed = remember { EditPlacement() }
    val live = remember(backdrop, c, m.rect, placed) {
        EditScrollBackdrop(backdrop) { editDrawDelta(c, m.rect, placed) }
    }
    Column(
        Modifier
            .editRect(c, m.rect, placed)
            .drawWithContent {
                val dy = editDrawDelta(c, m.rect, placed)
                translate(top = dy) { this@drawWithContent.drawContent() }
            }
            .drawBackdrop(
                backdrop = live,
                shape = { RoundedRectangle(28.dp) },
                effects = {
                    colorControls(brightness = 0f, saturation = 1.5f)
                    blur(3f.dp.toPx())
                    lens(16f.dp.toPx(), 32f.dp.toPx(), depthEffect = true)
                },
                highlight = { Highlight.Plain },
                layerBlock = {
                    val f = fade.coerceIn(0f, 1f)
                    val s = lerp(0.90f, 1f, f)
                    scaleX = s; scaleY = s; alpha = f
                },
                onDrawSurface = { drawRect(GlassTheme.Container) }
            )
            .then(EditNoTouchThrough)
            .padding(vertical = 8.dp)
    ) {
        m.items.forEach { EditMenuRow(it, c) }
    }
}

@Composable
private fun EditMenuRow(item: EditItem, c: GlassController) {
    val accent = LocalGlassAccent.current
    val isDelete = item.kind == "del"
    val color = when {
        isDelete -> GlassTheme.Danger
        item.on -> accent
        else -> GlassTheme.Content
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .tap { c.cmd { put("t", "pledit"); put("a", item.kind) } }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Canvas(Modifier.size(18.dp)) {
            when {
                isDelete -> strokeIcon(TrashIcon, 16f, color, 1.5f)
                item.on -> fillIcon(GlassIcons.Heart, 15f, color)
                else -> strokeIcon(listOf(GlassIcons.Heart), 15f, color, 1.4f)
            }
        }
        BasicText(
            item.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color, 15.sp, FontWeight.Medium)
        )
    }
}
