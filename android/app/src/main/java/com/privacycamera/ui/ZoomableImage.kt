package com.privacycamera.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale

private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * An image that supports pinch-to-zoom and, while zoomed, one-finger pan all the
 * way to the corners (translation is clamped to the scaled bounds so you can reach
 * every edge but not drag the image off-screen). Double-tap toggles zoom.
 *
 * Zoom/pan state resets whenever a different [bitmap] is shown.
 */
@Composable
fun ZoomableImage(
    bitmap: ImageBitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()

        fun clampOffset(candidate: Offset, atScale: Float): Offset {
            val maxX = (atScale - 1f) * maxW / 2f
            val maxY = (atScale - 1f) * maxH / 2f
            return Offset(
                candidate.x.coerceIn(-maxX, maxX),
                candidate.y.coerceIn(-maxY, maxY)
            )
        }

        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                        scale = newScale
                        offset = clampOffset(offset + pan, newScale)
                    }
                }
                .pointerInput(bitmap) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = DOUBLE_TAP_ZOOM
                            }
                        }
                    )
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
    }
}
