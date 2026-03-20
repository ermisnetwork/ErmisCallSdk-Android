package network.ermis.call.core.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class CountUpTimer(private val intervalInMs: Long = 1_000) {

    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private val elapsedTime: AtomicLong = AtomicLong()
    private val resumed: AtomicBoolean = AtomicBoolean(false)

    init {
        startCounter()
    }

    private fun startCounter() {
        tickerFlow(coroutineScope, intervalInMs / 10)
                .filter { resumed.get() }
                .map { elapsedTime.addAndGet(intervalInMs / 10) }
                .filter { it % intervalInMs == 0L }
                .onEach {
                    tickListener?.onTick(it)
                }.launchIn(coroutineScope)
    }

    var tickListener: TickListener? = null

    fun elapsedTime(): Long {
        return elapsedTime.get()
    }

    fun pause() {
        resumed.set(false)
    }

    fun resume() {
        resumed.set(true)
    }

    fun stop() {
        coroutineScope.cancel()
    }

    interface TickListener {
        fun onTick(milliseconds: Long)
    }

    private fun tickerFlow(scope: CoroutineScope, delayMillis: Long, initialDelayMillis: Long = delayMillis): Flow<Unit> {
        return scope.fixedPeriodTicker(delayMillis, initialDelayMillis).consumeAsFlow()
    }

    private fun CoroutineScope.fixedPeriodTicker(delayMillis: Long, initialDelayMillis: Long = delayMillis): ReceiveChannel<Unit> {
        require(delayMillis >= 0) { "Expected non-negative delay, but has $delayMillis ms" }
        require(initialDelayMillis >= 0) { "Expected non-negative initial delay, but has $initialDelayMillis ms" }
        return produce(capacity = 0) {
            delay(initialDelayMillis)
            while (true) {
                channel.send(Unit)
                delay(delayMillis)
            }
        }
    }
}
