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

import java.time.Instant

/**
 * Represents a single rate limit state. Immutable.
 */
public data class Rate(
    /**
     * The number of remaining requests + 1. So, if this is 1, then this is the last allowed request and the next
     * request will trigger a rate limit.
     */
    val remainingRequests: Long,
    /**
     * The instant at which the rate limit is invalid and reset.
     */
    val resetAt: Instant
)

/**
 * Returns true if the rate has expired (we have passed its reset instant)
 */
public fun Rate.hasExpired(): Boolean = resetAt < Instant.now()

/**
 * Consumes a single request and returns the modified rate.
 */
public fun Rate.consume(): Rate = copy(remainingRequests = (remainingRequests - 1).coerceAtLeast(0))

/**
 * Returns true if the 429 HTTP error should be returned, as in, this rate has not expired and there are no remaining
 * requests
 */
public fun Rate.shouldLimit(): Boolean = !hasExpired() && remainingRequests == 0L
