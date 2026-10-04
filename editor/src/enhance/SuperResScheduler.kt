package tessera.editor.enhance

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

/**
 * One Real-ESRGAN computation at a time in the whole application (it uses every core), most
 * urgent first across all open and background comics. Priorities: 0 what is shown, 1 the frames
 * prepared ahead of it, 2 a whole book prepared in the background; the fraction orders items
 * within a level (the frame shown before the next one). A running computation gives
 * way between tiles when something more urgent waits ([moreUrgentWaiting]).
 */
object SuperResScheduler {
    private val lock = Mutex()

    /** Who waits for a turn, with a way to read their current priority. */
    private val waiting = MutableStateFlow<Map<Any, () -> Double>>(emptyMap())

    /** Waits for the turn of [who] (any unique object), then holds the lock: call [release] after. */
    suspend fun takeTurn(who: Any, priority: () -> Double) {
        waiting.update { it + (who to priority) }
        try {
            while (true) {
                lock.lock()
                val mine = priority()
                val best = waiting.value.filterKeys { it != who }.values.minOfOrNull { it() }
                if (best == null || best >= mine) return
                lock.unlock()
                delay(150)
            }
        } finally {
            waiting.update { it - who }
        }
    }

    fun release() = lock.unlock()

    /** True when someone waits with a more urgent priority than [mine]. */
    fun moreUrgentWaiting(mine: Double): Boolean = waiting.value.values.any { it() < mine }
}
