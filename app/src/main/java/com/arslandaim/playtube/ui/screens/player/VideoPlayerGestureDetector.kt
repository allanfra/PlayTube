/*
 * PlayTube Project Original (2026)
 * arslandaim-hub (GitHub.com/arslandaim-hub)
 * Licenced Under GPL-3.0+
*/
package com.arslandaim.playtube.ui.screens.player

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun VideoPlayerGestureDetector(
    onDoubleTapLeft: () -> Unit,
    onDoubleTapRight: () -> Unit,
    onSingleTap: () -> Unit,
    onSwipeDown: () -> Unit,
    onSwipeUp: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                coroutineScope {
                    var tapCount = 0
                    var tapJob: kotlinx.coroutines.Job? = null
                    
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val down = event.changes.find { it.changedToDownIgnoreConsumed() }
                            
                            if (down != null) {
                                tapCount++
                                tapJob?.cancel()
                                
                                val isLeftSide = down.position.x < size.width / 2
                                
                                if (tapCount >= 2) {
                                    // Multi-tap detected
                                    down.consume()
                                    if (isLeftSide) onDoubleTapLeft() else onDoubleTapRight()
                                    
                                    tapJob = launch {
                                        delay(300)
                                        tapCount = 0
                                    }
                                } else {
                                    // First tap, wait to see if it's a double tap
                                    tapJob = launch {
                                        delay(300)
                                        if (tapCount == 1) {
                                            onSingleTap()
                                        }
                                        tapCount = 0
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                var totalDrag = 0f
                var dragStartX = 0f
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        totalDrag = 0f
                        dragStartX = offset.x
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        totalDrag += dragAmount
                    },
                    onDragEnd = {
                        val screenWidth = size.width
                        val sideMargin = screenWidth * 0.30f

                        val startedInCenter = dragStartX in sideMargin..(screenWidth - sideMargin)

                        if (startedInCenter) {
                            if (totalDrag > 150) {
                                onSwipeDown()
                            } else if (totalDrag < -150) {
                                onSwipeUp()
                            }
                        }
                    }
                )
            }
    ) {
        content()
    }
}
