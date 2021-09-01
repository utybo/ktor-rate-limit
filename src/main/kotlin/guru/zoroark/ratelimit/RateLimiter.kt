/**
 * Copyright 2020 the original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package guru.zoroark.ratelimit

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The RateLimiter is responsible for storing information about current rate limits
 *
 * @param K The type of the key. The [RateLimit] feature uses String-based rate limiters
 */
public interface RateLimiter<K> {
    /**
     * Handle a single call with the given key. Should return the resulting rate object.
     *
     * This function must also handle all rate-resetting activities.
     *
     * @param ctx The context to use to get more information about rates
     * @return A [Rate] object representing the rate handled and whether the limit should happen or not
     */
    public suspend fun handle(ctx: RateLimitingContext, key: K): Rate
}

/**
 * An implementation of rate limiters that stores rate limits using a
 * `ConcurrentHashMap`.
 *
 * @param mapPurgeSize The size the map needs to reach in order for it to be
 * purged on the next call
 * @param mapPurgeWaitDuration The amount of time to wait before the next
 * purging.
 */
public class InMemoryRateLimiter(
    private val mapPurgeSize: Int,
    private val mapPurgeWaitDuration: Duration
) : RateLimiter<String> {
    private val scope =
        CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineExceptionHandler { _, ex ->
            logger.error(
                "Uncaught exception in in-memory rate limiter storage",
                ex
            )
        })
    private val mutex = Mutex()
    private val isPurgeRunning = AtomicBoolean(false)
    private var lastPurgeTime = Instant.now()
    private val map = ConcurrentHashMap<String, Rate>()
    private val logger =
        LoggerFactory.getLogger("guru.zoroark.ratelimit.inmemory")

    override suspend fun handle(ctx: RateLimitingContext, key: String): Rate =
        withContext(Dispatchers.Default) {
            map.compute(key) { _, v ->
                when {
                    v == null -> ctx.newRate()
                    v.hasExpired() -> ctx.newRate()
                        .also { logger.debug { "Bucket $key has expired, reset" } }
                    else -> v.consume()
                }
            }!!.also { launchPurgeIfNeeded() }
        }

    private fun launchPurgeIfNeeded() {
        logger.debug { "Should purge: ${shouldPurge()}" }
        if (shouldPurge() && mutex.tryLock()) {
            logger.debug { "Launching purge in coroutine scope" }
            scope.launch {
                try {
                    logger.info("Purging rate limit information, current size = ${map.size}")
                    val shouldRemove = map.filterValues { it.hasExpired() }.keys
                    shouldRemove.forEach { k ->
                        logger.debug { "Removing stale bucket $k" }
                        map.remove(k)
                    }
                    lastPurgeTime = Instant.now()
                    isPurgeRunning.set(false)
                    logger.info("Purged rate limit information, new size = ${map.size}")
                } finally {
                    mutex.unlock()
                }
            }
        }
    }

    private fun shouldPurge() =
        map.size > mapPurgeSize && Duration.between(
            lastPurgeTime,
            Instant.now()
        ) > mapPurgeWaitDuration
}
