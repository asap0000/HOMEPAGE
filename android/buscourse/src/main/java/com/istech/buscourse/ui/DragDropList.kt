package com.istech.buscourse.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput

/**
 * LazyColumn 用のドラッグ&ドロップ並べ替え状態（コース編成UI・設計書§3.8「停留所の並べ替え
 * （ドラッグ&ドロップ等でUI操作）」）。外部DnDライブラリは導入せず自前実装とする
 * （オフライン方針の監査対象を増やさない。§8と同趣旨の判断）。
 *
 * 使い方: 各アイテムの見た目に `graphicsLayer { translationY = state.offsetYFor(index) }` を適用し、
 * そのアイテムのドラッグハンドルに [dragHandleModifier] を付ける（`index` はLazyColumn内の生index）。
 * 長押しでドラッグ開始し、アイテム中心が別アイテムに重なるたびに [onMove] で並びを入れ替える。
 *
 * 【2026-07-09実機確認】検出Modifierは必ず各アイテムの子（ハンドル等）に付けること。
 * LazyColumn自体（親）に付けると、LazyColumn組み込みのスクロールジェスチャーが
 * Composeのポインタイベント伝播（子が先にMainパスを処理する）により先に移動量を消費してしまい、
 * onDragStartは発火してもonDragが一切呼ばれず、並べ替えが機能しない（実機/エミュレータで確認済みの不具合）。
 */
class DragDropListState(
    val lazyListState: LazyListState,
    /** LazyColumn内の生アイテムindexがドラッグ対象か（ヘッダ行等を除外するための述語）。 */
    private val canDrag: (index: Int) -> Boolean,
    private val onMove: (fromIndex: Int, toIndex: Int) -> Unit,
) {
    /** ドラッグ中のアイテムindex（非ドラッグ時は null）。 */
    var draggingItemIndex by mutableStateOf<Int?>(null)
        private set

    /** ドラッグ中アイテムの表示上のYオフセット（レイアウト位置からの差分）。 */
    var draggingItemOffsetY by mutableFloatStateOf(0f)
        private set

    /** [index] のアイテムに適用すべき translationY。 */
    fun offsetYFor(index: Int): Float = if (index == draggingItemIndex) draggingItemOffsetY else 0f

    internal fun onDragStart(index: Int) {
        if (!canDrag(index)) return
        draggingItemIndex = index
        draggingItemOffsetY = 0f
    }

    internal fun onDrag(dragAmount: Offset) {
        draggingItemOffsetY += dragAmount.y
        val fromIndex = draggingItemIndex ?: return
        val visible = lazyListState.layoutInfo.visibleItemsInfo
        val current = visible.firstOrNull { it.index == fromIndex } ?: return

        val middleY = current.offset + draggingItemOffsetY + current.size / 2f
        val target = visible.firstOrNull { candidate ->
            candidate.index != fromIndex && canDrag(candidate.index) &&
                middleY.toInt() in candidate.offset..(candidate.offset + candidate.size)
        } ?: return

        onMove(fromIndex, target.index)
        // 入れ替え後もドラッグ中アイテムの見た目の位置を維持する（新しいレイアウト位置との差分に換算）
        draggingItemOffsetY += current.offset - target.offset
        draggingItemIndex = target.index
    }

    internal fun onDragInterrupted() {
        draggingItemIndex = null
        draggingItemOffsetY = 0f
    }
}

/** [DragDropListState] を生成・保持する。[onMove] は並び順リストの入れ替えを行うこと。 */
@Composable
fun rememberDragDropListState(
    lazyListState: LazyListState = rememberLazyListState(),
    canDrag: (index: Int) -> Boolean = { true },
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
): DragDropListState {
    val currentOnMove by rememberUpdatedState(onMove)
    val currentCanDrag by rememberUpdatedState(canDrag)
    return remember(lazyListState) {
        DragDropListState(
            lazyListState = lazyListState,
            canDrag = { index -> currentCanDrag(index) },
            onMove = { from, to -> currentOnMove(from, to) },
        )
    }
}

/** 各アイテムのドラッグハンドルに適用する長押しドラッグ検出 Modifier。[index] はLazyColumn内の生index。 */
fun Modifier.dragHandleModifier(state: DragDropListState, index: Int): Modifier =
    pointerInput(state, index) {
        detectDragGesturesAfterLongPress(
            onDragStart = { state.onDragStart(index) },
            onDrag = { change, dragAmount ->
                change.consume()
                state.onDrag(dragAmount)
            },
            onDragEnd = { state.onDragInterrupted() },
            onDragCancel = { state.onDragInterrupted() },
        )
    }
