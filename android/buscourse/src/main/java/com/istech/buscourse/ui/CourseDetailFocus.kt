package com.istech.buscourse.ui

/** 停留所を外した後、同じ位置の次点（末尾なら前点）へ視線を移す。 */
internal fun nextSelectionAfterRemoval(removedIndex: Int, newSize: Int): Int? = when {
    newSize <= 0 -> null
    removedIndex < newSize -> removedIndex.coerceAtLeast(0)
    else -> newSize - 1
}
