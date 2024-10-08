package xyz.malkki.neostumbler.extensions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration

inline fun <reified A, B> Collection<Flow<A>>.combineAny(crossinline combiner: suspend (Array<A?>) -> B): Flow<B> = channelFlow {
    val values = arrayOfNulls<A?>(size)

    forEachIndexed { index, flow ->
        launch {
            flow.collect {
                values[index] = it

                send(combiner(values.copyOf()))
            }
        }
    }
}

fun <T> Flow<T>.buffer(window: Duration): Flow<List<T>> = channelFlow {
    val items: MutableList<T> = mutableListOf<T>()
    var finished = false

    launch {
        collect {
            items.add(it)
        }

        finished = true
    }

    while (true) {
        delay(window)

        send(items.toList())
        items.clear()

        if (finished) {
            break
        }
    }
}

fun <T, R> Flow<T>.parallelMap(
    context: CoroutineContext = EmptyCoroutineContext,
    transform: suspend (T) -> R
): Flow<R> {
    val scope = CoroutineScope(context + SupervisorJob())

    return map {
            scope.async { transform(it) }
        }
        .buffer()
        .map { it.await() }
        .flowOn(context)
}