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

/**
 * The (non-standard) headers used by the rate limiting plugins. The names
 * are identical to the ones used by Discord.
 */
public object RateLimitHeaders {
    /**
     * The number of requests that can be made
     */
    public const val Limit: String = "X-RateLimit-Limit"

    /**
     * The number of remaining requests that can be made before the reset time.
     */
    public const val Remaining: String = "X-RateLimit-Remaining"

    /**
     * The epoch time at which the rate limit resets
     */
    public const val Reset: String = "X-RateLimit-Reset"

    /**
     * The total time in seconds (either integer or floating) of when the
     * current rate limit will reset
     */
    public const val ResetAfter: String = "X-RateLimit-Reset-After"

    /**
     * Header for precision (second or millisecond)
     */
    public const val Precision: String = "X-RateLimit-Precision"

    /**
     * "Bucket" header
     */
    public const val Bucket: String = "X-RateLimit-Bucket"
}
