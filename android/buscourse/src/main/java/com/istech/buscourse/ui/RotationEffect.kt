package com.istech.buscourse.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * この Composable が画面に居る間だけ端末の回転を許可し、離れたら元（縦固定）へ戻す。
 *
 * 背景（istech `docs/2026-07-25_設計ドラフト_映像ナビ画面と簡易版ナビ用マップ.md` §2-2・§7-3）:
 * 映像ナビ本画面・ナビ設定画面は**横向き対応する**（オーナー確定 2026-07-25）。一方 [MainActivity] は
 * 撮影・録音中の一時ファイルが Activity 再生成で無警告に失われる不具合の対策として
 * `android:screenOrientation="portrait"` で縦固定されている（2026-07-11 レビュー指摘）。
 *
 * そこで **Activity 全体の縦固定はマニフェストに残したまま、ナビ2画面の滞在中だけ実行時に回転を解放**する。
 * 実行時の `requestedOrientation` はマニフェスト指定より優先されるため、これで他画面（録画等）の
 * 縦固定は一切変えずに済む。あわせてマニフェストに `configChanges="orientation|screenSize|keyboardHidden"`
 * を付け、回転が起きても **Activity を再生成させない**（MapView の SurfaceView 再確保・スライダー状態の
 * 消失を防ぐ。設計 §2-2「MapView はサイズ変更に弱い」）。
 */
@Composable
fun AllowRotationWhileVisible() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        onDispose {
            // 元の指定へ戻す（取得できなければマニフェスト既定と同じ縦固定へ）。
            activity?.requestedOrientation = previous ?: ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
