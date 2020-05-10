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

import java.time.Duration
import java.time.Instant

/**
 * This context provides information useful for [RateLimiter]s that wish to create new [Rate] objects.
 */
data class RateLimitingContext(
    /**
     * The limit, which is the maximum number amount of requests that can be made in the span of [resetTime]
     * milliseconds
     */
    val limit: Long,
    /**
     * The amount of milliseconds the rate limit lasts
     */
    val resetTime: Long
)

/**
 * Creates a new [Rate] object based on the current rate limiting context. Useful for creating brand new rates.
 */
fun RateLimitingContext.newRate(): Rate =
    Rate(limit, Instant.now() + Duration.ofMillis(resetTime))