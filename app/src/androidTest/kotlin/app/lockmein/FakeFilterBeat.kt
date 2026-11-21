package app.lockmein

/**
 * Under instrumentation Android holds the accessibility services back, so the real filter sends no heartbeat and FilterWatcher
 * rightly closes the watched apps. Tests that are not about the watcher send the heartbeat themselves, as a live filter would.
 */
object FakeFilterBeat {
    @Volatile private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        thread = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    FilterWatcher.beat()
                    Thread.sleep(1_000)
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        thread?.interrupt()
        thread = null
    }

    /** Waits until a running watcher counts the filter as working again (two heartbeats after any earlier 'off'). */
    fun awaitWorking(timeoutMs: Long = 8_000) {
        val start = System.currentTimeMillis()
        while (FilterWatcher.filterDownNow == true && System.currentTimeMillis() - start < timeoutMs) Thread.sleep(100)
    }
}
