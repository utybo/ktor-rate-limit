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

import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RateLimitTest {
    @Test
    fun `Test rate limiting headers`(): Unit = testApplication {
        val resetDur = Duration.ofMinutes(1)
        install(RateLimit) {
            limit = 5
            timeBeforeReset = resetDur
        }
        routing {
            rateLimited {
                get("/") {
                    call.respond(HttpStatusCode.OK)
                }
            }
        }
        // Add a second to account for possible lag or delays
        val max = Instant.now() + resetDur + Duration.ofSeconds(1)
        repeat(5) { iteration ->
            client.get("/").apply {
                assertRateLimitedHeaders(5, 4L - iteration, max)
                assertStatus(HttpStatusCode.OK)
            }
        }

        client.get("/").apply {
            assertStatus(HttpStatusCode.TooManyRequests)
            assertRateLimitedHeaders(5, 0, max)
            assertEquals(headers["X-RateLimit-Reset-After"]!!, headers["Retry-After"])
        }
    }

    @Test
    fun `Different buckets have different counters`(): Unit = testApplication {
        val resetDur = Duration.ofMinutes(1)
        var useMe = "One"
        install(RateLimit) {
            limit = 5
            timeBeforeReset = resetDur
            callerKeyProducer = {
                useMe.toByteArray()
            }
        }
        routing {
            rateLimited {
                get("/") {
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        // Add a second to account for possible lag or delays
        val max = Instant.now() + resetDur + Duration.ofSeconds(1)

        // Simulate first client
        repeat(4) { iteration ->
            client.get("/").apply {
                assertRateLimitedHeaders(5, 4L - iteration, max)
                assertStatus(HttpStatusCode.OK)
            }
        }
        // Simulate second client
        useMe = "Two"
        repeat(3) { iteration ->
            client.get("/").apply {
                assertRateLimitedHeaders(5, 4L - iteration, max)
            }
        }
        // Simulate first client, whose bucket should not have expired
        useMe = "One"
        client.get("/").apply {
            assertRateLimitedHeaders(5, 0L, max)
            assertStatus(HttpStatusCode.OK)
        }
        client.get("/").apply {
            assertStatus(HttpStatusCode.TooManyRequests)
            assertRateLimitedHeaders(5, 0, max)
            assertEquals(headers["X-RateLimit-Reset-After"]!!, headers["Retry-After"])
        }
    }

    @Test
    fun `Different routes have different counters`(): Unit = testApplication {
        val resetDur = Duration.ofMinutes(1)

        install(RateLimit) {
            limit = 5
            timeBeforeReset = resetDur
        }
        routing {
            rateLimited {
                get("/one") {
                    call.respond(HttpStatusCode.OK)
                }
            }
            rateLimited {
                get("/two") {
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        // Add a second to account for possible lag or delays
        val max = Instant.now() + resetDur + Duration.ofSeconds(1)

        // Simulate first route
        repeat(4) { iteration ->
            client.get("/one").apply {
                assertRateLimitedHeaders(5, 4L - iteration, max)
                assertStatus(HttpStatusCode.OK)
            }
        }
        // Simulate second route
        repeat(3) { iteration ->
            client.get("/two").apply {
                assertRateLimitedHeaders(5, 4L - iteration, max)
            }
        }
        // Simulate second route, whose bucket should not have expired
        client.get("/one").apply {
            assertRateLimitedHeaders(5, 0L, max)
            assertStatus(HttpStatusCode.OK)
        }
        client.get("/one").apply {
            assertStatus(HttpStatusCode.TooManyRequests)
            assertRateLimitedHeaders(5, 0, max)
            assertEquals(headers["X-RateLimit-Reset-After"]!!, headers["Retry-After"])
        }
    }

    @Test
    fun `Different additional key have different counters`(): Unit = testApplication {
        val resetDur = Duration.ofMinutes(1)
        install(RateLimit) {
            limit = 5
            timeBeforeReset = resetDur
        }
        routing {
            rateLimited(additionalKeyExtractor = {
                parameters["id"]!!
            }) {
                get("/{id}") {
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        // Add a second to account for possible lag or delays
        val max = Instant.now() + resetDur + Duration.ofSeconds(1)

        // Simulate first key
        repeat(4) { iteration ->
            client.get("/one").apply {
                assertRateLimitedHeaders(5, 4L - iteration, max)
                assertStatus(HttpStatusCode.OK)
            }
        }
        // Simulate second key
        repeat(3) { iteration ->
            client.get("/two").apply {
                assertRateLimitedHeaders(5, 4L - iteration, max)
            }
        }
        // Simulate second key, whose bucket should not have expired
        client.get("/one").apply {
            assertRateLimitedHeaders(5, 0L, max)
            assertStatus(HttpStatusCode.OK)
        }
        client.get("/one").apply {
            assertStatus(HttpStatusCode.TooManyRequests)
            assertRateLimitedHeaders(5, 0, max)
            assertEquals(headers["X-RateLimit-Reset-After"]!!, headers["Retry-After"])
        }
    }

    @Test
    fun `Rate limit expires`(): Unit = testApplication {
        val resetDur = Duration.ofMillis(300)
        install(RateLimit) {
            limit = 3
            timeBeforeReset = resetDur
        }
        routing {
            rateLimited(additionalKeyExtractor = {
                parameters["id"]!!
            }) {
                get("/{id}") {
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        repeat(2) {
            client.get("/one").apply {
                assertStatus(HttpStatusCode.OK)
            }
        }
        repeat(3) {
            client.get("/two").apply {
                assertStatus(HttpStatusCode.OK)
            }
        }
        client.get("/two").apply {
            assertStatus(HttpStatusCode.TooManyRequests)
        }
        delay(300)
        client.get("/one").apply {
            assertStatus(HttpStatusCode.OK)
            assertEquals("2", headers["X-RateLimit-Remaining"])
        }
        client.get("/two").apply {
            assertStatus(HttpStatusCode.OK)
            assertEquals("2", headers["X-RateLimit-Remaining"])
        }
    }

    @Test
    fun `Check purge has run`(): Unit = testApplication {
        val resetDur = Duration.ofMillis(300)
        val limiter = InMemoryRateLimiter(5, Duration.ofMillis(400))
        install(RateLimit) {
            limit = 3
            timeBeforeReset = resetDur
            this.limiter = limiter
        }
        routing {
            rateLimited(additionalKeyExtractor = {
                parameters["id"]!!
            }) {
                get("/{id}") {
                    call.respond(HttpStatusCode.OK)
                }
            }
        }
        repeat(100) {
            client.get("/$it").apply {
                assertStatus(HttpStatusCode.OK)
            }
        }
        assertTrue(limiter.internalMapSize > 40, "Internal map size is ${limiter.internalMapSize}")
        delay(400)
        client.get("/1").apply {
            assertStatus(HttpStatusCode.OK)
        }
        delay(100) // Wait for the purge to happen, it runs in the background
        assertEquals(1, limiter.internalMapSize)
    }
}

/*
 * Has some weirdness to avoid rounding errors
 */
fun HttpResponse.assertRateLimitedHeaders(
    expectedLimit: Long,
    expectedRemaining: Long,
    resetMax: Instant,
    inMillis: Boolean = false
) {
    val actualResetMax =
        if (inMillis)
            resetMax
        else
            Instant.ofEpochSecond(ceil(resetMax.toEpochMilli() / 1000.0).toLong())

    assertEquals(expectedLimit.toString(), headers["X-RateLimit-Limit"])
    assertEquals(expectedRemaining.toString(), headers["X-RateLimit-Remaining"])
    val resetAt =
        if (inMillis)
            Instant.ofEpochMilli((headers["X-RateLimit-Reset"]!!.toDouble() * 1000).toLong())
        else
            Instant.ofEpochSecond((headers["X-RateLimit-Reset"]!!.toLong()))
    headers["X-RateLimit-Reset"]!!.toLong()
    assertTrue(resetAt <= actualResetMax, "Resets before max")
    val resetAfter =
        if (inMillis)
            Duration.ofMillis((headers["X-RateLimit-Reset-After"]!!.toDouble() * 1000).toLong())
        else
            Duration.ofSeconds(headers["X-RateLimit-Reset-After"]!!.toLong())
    val expectedResetAfterMillis = Duration.between(Instant.now(), actualResetMax).toMillis()
    val expectedResetAfter =
        if (inMillis)
            Duration.ofMillis(expectedResetAfterMillis)
        else
            Duration.ofSeconds(ceil(expectedResetAfterMillis / 1000.0).toLong())
    assertTrue(
        resetAfter <= expectedResetAfter,
        "Reset after less than max"
    )
    assertNotNull(headers["X-RateLimit-Bucket"], "Has a bucket")
}

private fun HttpResponse.assertStatus(code: HttpStatusCode) {
    assertEquals(code, status)
}
