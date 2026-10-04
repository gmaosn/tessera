package tessera.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Computes Real-ESRGAN results ahead, for one open comic, whether the reader is open or not:
 * the frames after the one read ([ahead]), or the whole book ([book]). High-definition pages
 * go frame by frame, others as whole pages. One item at a time, always behind what is shown.
 * Its state is Compose state, for the indicator in the top bar.
 */
class Preparer(
    private val images: ImageCache,
    private val session: Session,
    /** The UI thread: [ImageCache] is used from there only (its heavy work runs elsewhere). */
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    var active by mutableStateOf(false)
        private set

    /** True while preparing the whole book (ahead requests then leave it alone). */
    var wholeBook by mutableStateOf(false)
        private set

    /** Frames ready and planned; a page without frames counts as one. */
    var done by mutableIntStateOf(0)
        private set
    var total by mutableIntStateOf(0)
        private set

    /** The item being computed, for its progress in [ImageCache.superResProgress]. */
    var current by mutableStateOf<String?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null

    /** Prepares the [count] frames after page [page], frame [frame], in reading order. */
    fun ahead(page: Int, frame: Int, count: Int = AHEAD) {
        if (wholeBook && active) return
        start(plan(page, frame + 1, count), whole = false)
    }

    /** Prepares every frame of the book, from the start. */
    fun book() = start(plan(0, 0, Int.MAX_VALUE), whole = true)

    fun stop() {
        job?.cancel()
        finish()
    }

    /** Stops for good (the comic is closed). */
    fun close() {
        stop()
        scope.cancel()
    }

    /** One page's frames to prepare: [frames] is null for the whole page. */
    private class Step(val page: Int, val frames: IntRange?, val units: Int)

    private fun plan(fromPage: Int, fromFrame: Int, limit: Int): List<Step> {
        val steps = mutableListOf<Step>()
        var left = limit
        var p = fromPage
        var f = fromFrame
        while (left > 0 && p < session.pages.size) {
            val count = maxOf(1, session.pages[p].frames.count { it.polygon != null })
            if (f < count) {
                val n = minOf(count - f, left)
                steps += Step(p, f until f + n, n)
                left -= n
            }
            p++; f = 0
        }
        return steps
    }

    private fun start(steps: List<Step>, whole: Boolean) {
        job?.cancel()
        images.preparingPriority = if (whole) 2 else 1
        total = steps.sumOf { it.units }
        done = 0
        wholeBook = whole
        active = total > 0
        if (!active) return
        job = scope.launch {
            try {
                for (step in steps) run(step)
            } finally {
                finish()
            }
        }
    }

    private suspend fun run(step: Step) {
        val page = session.pages.getOrNull(step.page) ?: return
        val href = page.imageHref ?: return
        val image = images.page(href)
        if (image == null) {
            done += step.units; return
        }
        if (!images.isHighDefinition(href)) {
            // The whole page at once covers all its frames.
            prepare(href, null)
            done += step.units
            return
        }
        val polys = page.frames.mapNotNull { it.polygon }
        for (k in step.frames ?: 0 until 1) {
            val poly = polys.getOrNull(k)
            if (poly != null) prepare(href, Region.around(poly, image.width, image.height))
            done += 1
        }
    }

    private suspend fun prepare(href: String, region: Region?) {
        val id = region?.id(href) ?: href
        current = id
        images.preparing = listOf(id)
        images.ensureSuperRes(href, region)
    }

    private fun finish() {
        active = false
        wholeBook = false
        current = null
        images.preparing = emptyList()
    }

    companion object {
        const val AHEAD = 15
    }
}

/** A comic whose whole-book preparation goes on while another one is open. */
class BackgroundBook(val name: String, val preparer: Preparer)
