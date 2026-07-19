package com.istech.buscourse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 園区分（色選択）機能の色パレット（設計書§9 フェーズ2、2026-07-11オーナー指定「依頼１続き」）。
 *
 * 停留所カードに園区分の属性を追加する。入力は任意項目で、UIの汎用性を高めるため
 * 「色を選択するだけ」にとどめる（色と園の対応はアプリ側では固定せず運用で決める。
 * 将来のPC側プランナー＝バスコースプランナーEXがこの色で園を識別する）。
 * そのため色の呼称も「赤」「青」等の色名のみとし、園名を連想させる名称は付けない。
 */

/**
 * 選択可能な色（"#RRGGBB"形式）。2026-07-14にオーナー指定でマゼンタ(#EC407A)・
 * ターコイズ(#00ACC1)・緑(#43A047)を外し、白・黒を追加した。
 */
val GARDEN_COLOR_PALETTE: List<String> = listOf(
    "#E53935", // 赤
    "#1E88E5", // 青
    "#FDD835", // 黄
    "#8E24AA", // 紫
    "#FB8C00", // 橙
    "#FFFFFF", // 白
    "#000000", // 黒
)

/** "#RRGGBB" 文字列 → Compose Color。パース失敗時はnull（不正値で描画が落ちないようにする）。 */
private fun parseColorHexOrNull(colorHex: String): Color? =
    runCatching { Color(android.graphics.Color.parseColor(colorHex)) }.getOrNull()

/**
 * 区分（色）選択部品。7色を横一列（画面幅が狭い場合は折り返し）に並べ、タップで選択する。
 * 選択中の色を再タップすると選択解除（null）になる。
 *
 * @param selected 現在選択中の色（"#RRGGBB"）。未選択はnull。
 * @param onSelect 選択変更時のコールバック。解除時はnullを渡す。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GardenColorSelector(
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text("区分（任意）", style = MaterialTheme.typography.bodyMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            GARDEN_COLOR_PALETTE.forEach { colorHex ->
                val isSelected = selected.equals(colorHex, ignoreCase = true)
                val color = parseColorHexOrNull(colorHex) ?: return@forEach
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = CircleShape,
                        )
                        .clickable {
                            // 選択中の色を再タップすると解除（null）にする
                            onSelect(if (isSelected) null else colorHex)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "選択中",
                            tint = pickCheckMarkTint(color),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 背景色の明度に応じてチェックマークを白/黒に振り分ける（黄色等の明色でも視認できるようにする）。 */
private fun pickCheckMarkTint(background: Color): Color {
    val luminance = 0.299f * background.red + 0.587f * background.green + 0.114f * background.blue
    return if (luminance > 0.6f) Color.Black else Color.White
}

/**
 * 一覧表示用の小さな園区分ドット（12dp固定）。nullなら何も描画しない
 * （StopCardListScreenの過去の教訓: バッジ類を横に並べると大フォント設定で
 * 名前列が押し出されるため、ここは常に固定の小サイズに留める）。
 */
@Composable
fun GardenColorDot(colorHex: String?, modifier: Modifier = Modifier) {
    if (colorHex == null) return
    val color = parseColorHexOrNull(colorHex) ?: return
    Box(
        modifier = modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color)
    )
}
