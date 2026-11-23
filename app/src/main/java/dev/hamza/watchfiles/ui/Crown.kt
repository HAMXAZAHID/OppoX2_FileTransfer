package dev.hamza.watchfiles.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** How far one crown click scrolls. */
private val CrownStep = 24.dp

/**
 * Scrolls a fixed distance per crown click, whatever size the system says the click is.
 * The OPPO crown reports very large scroll amounts, so the stock handling flew to the end of
 * the list. Clicks add to a target, and one frame-synced loop glides toward it, so fast turns
 * stay smooth instead of restarting an animation on every click.
 */
@Composable
fun Modifier.crownScroll(state: ScrollableState): Modifier {
    val stepPx = with(LocalDensity.current) { CrownStep.toPx() }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val glide = remember { CrownGlide() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun onTick(direction: Float) {
        if (direction == 0f || !glide.accept()) return
        // Turning the other way drops what's left of the previous direction.
        if (glide.remaining != 0f && glide.remaining.sign != direction) glide.remaining = 0f
        glide.remaining += direction * stepPx
        if (glide.job?.isActive == true) return
        glide.job = scope.launch {
            state.scroll {
                while (abs(glide.remaining) > 0.5f) {
                    withFrameNanos { }
                    // Cover ~30% of the way each frame: quick to start, eases out at the end.
                    val move = glide.remaining * 0.3f
                    val wanted = if (abs(move) < 1f) glide.remaining else move
                    val consumed = scrollBy(wanted)
                    glide.remaining -= wanted
                    if (abs(consumed) < abs(wanted) * 0.5f) glide.remaining = 0f // hit the end
                }
                glide.remaining = 0f
            }
        }
    }

    return this
        .pointerInput(stepPx) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Scroll) {
                        val dy = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                        event.changes.forEach { it.consume() }
                        onTick(dy.sign)
                    }
                }
            }
        }
        .onPreRotaryScrollEvent { e ->
            onTick(e.verticalScrollPixels.sign)
            true
        }
        .focusRequester(focus)
        .focusable()
}

private class CrownGlide {
    var remaining = 0f
    var job: Job? = null
    private var last = 0L

    /** The crown can send a burst of events for one small turn; count at most one per 35 ms. */
    fun accept(): Boolean {
        val now = System.nanoTime()
        if (now - last < 35_000_000L) return false
        last = now
        return true
    }
}
