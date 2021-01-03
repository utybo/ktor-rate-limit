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

import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.XForwardedHeaderSupport
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.testing.TestApplicationCall
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.withTestApplication
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil
import kotlin.test.*

class RateLimitTest {
    @Test
    fun `Test rate limiting headers`(): Unit = withTestApplication {
        val resetDur = Duration.ofMinutes(1)
        with(application) {
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
        }
        // Add a second to account for possible lag or delays
        val max = Instant.now() + resetDur + Duration.ofSeconds(1)
        repeat(5) { iteration ->
            handleRequest(HttpMethod.Get, "/").apply {
                assertRateLimitedHeaders(5, 4L - iteration, max)
                assertStatus(HttpStatusCode.OK)
            }
        }

        handleRequest(HttpMethod.Get, "/").apply {
            assertStatus(HttpStatusCode.TooManyRequests)
            assertRateLimitedHeaders(5, 0, max)
            assertEquals(
                response.headers["X-RateLimit-Reset-After"]!!,
                response.headers["Retry-After"]
            )
        }
    }

    @Test
    fun `Different buckets have different counters`(): Unit =
        withTestApplication {
            val resetDur = Duration.ofMinutes(1)
            var useMe = "One"
            with(application) {
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
            }
            // Add a second to account for possible lag or delays
            val max = Instant.now() + resetDur + Duration.ofSeconds(1)

            // Simulate first client
            repeat(4) { iteration ->
                handleRequest(HttpMethod.Get, "/").apply {
                    assertRateLimitedHeaders(5, 4L - iteration, max)
                    assertStatus(HttpStatusCode.OK)
                }
            }
            // Simulate second client
            useMe = "Two"
            repeat(3) { iteration ->
                handleRequest(HttpMethod.Get, "/").apply {
                    assertRateLimitedHeaders(5, 4L - iteration, max)
                }
            }
            // Simulate first client, whose bucket should not have expired
            useMe = "One"
            handleRequest(HttpMethod.Get, "/").apply {
                assertRateLimitedHeaders(5, 0L, max)
                assertStatus(HttpStatusCode.OK)
            }
            handleRequest(HttpMethod.Get, "/").apply {
                assertStatus(HttpStatusCode.TooManyRequests)
                assertRateLimitedHeaders(5, 0, max)
                assertEquals(
                    response.headers["X-RateLimit-Reset-After"]!!,
                    response.headers["Retry-After"]
                )
            }
        }

    @Test
    fun `Different routes have different counters`(): Unit =
        withTestApplication {
            val resetDur = Duration.ofMinutes(1)
            with(application) {
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
            }
            // Add a second to account for possible lag or delays
            val max = Instant.now() + resetDur + Duration.ofSeconds(1)

            // Simulate first route
            repeat(4) { iteration ->
                handleRequest(HttpMethod.Get, "/one").apply {
                    assertRateLimitedHeaders(5, 4L - iteration, max)
                    assertStatus(HttpStatusCode.OK)
                }
            }
            // Simulate second route
            repeat(3) { iteration ->
                handleRequest(HttpMethod.Get, "/two").apply {
                    assertRateLimitedHeaders(5, 4L - iteration, max)
                }
            }
            // Simulate second route, whose bucket should not have expired
            handleRequest(HttpMethod.Get, "/one").apply {
                assertRateLimitedHeaders(5, 0L, max)
                assertStatus(HttpStatusCode.OK)
            }
            handleRequest(HttpMethod.Get, "/one").apply {
                assertStatus(HttpStatusCode.TooManyRequests)
                assertRateLimitedHeaders(5, 0, max)
                assertEquals(
                    response.headers["X-RateLimit-Reset-After"]!!,
                    response.headers["Retry-After"]
                )
            }
        }

    @Test
    fun `Different additional key have different counters`(): Unit =
        withTestApplication {
            val resetDur = Duration.ofMinutes(1)
            with(application) {
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
            }
            // Add a second to account for possible lag or delays
            val max = Instant.now() + resetDur + Duration.ofSeconds(1)

            // Simulate first key
            repeat(4) { iteration ->
                handleRequest(HttpMethod.Get, "/one").apply {
                    assertRateLimitedHeaders(5, 4L - iteration, max)
                    assertStatus(HttpStatusCode.OK)
                }
            }
            // Simulate second key
            repeat(3) { iteration ->
                handleRequest(HttpMethod.Get, "/two").apply {
                    assertRateLimitedHeaders(5, 4L - iteration, max)
                }
            }
            // Simulate second key, whose bucket should not have expired
            handleRequest(HttpMethod.Get, "/one").apply {
                assertRateLimitedHeaders(5, 0L, max)
                assertStatus(HttpStatusCode.OK)
            }
            handleRequest(HttpMethod.Get, "/one").apply {
                assertStatus(HttpStatusCode.TooManyRequests)
                assertRateLimitedHeaders(5, 0, max)
                assertEquals(
                    response.headers["X-RateLimit-Reset-After"]!!,
                    response.headers["Retry-After"]
                )
            }
        }

    @Test
    fun `Rate limit expires`(): Unit = withTestApplication {
        val resetDur = Duration.ofMillis(300)
        with(application) {
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
        }
        repeat(2) {
            handleRequest(HttpMethod.Get, "/one").apply {
                assertStatus(HttpStatusCode.OK)
            }
        }
        repeat(3) {
            handleRequest(HttpMethod.Get, "/two").apply {
                assertStatus(HttpStatusCode.OK)
            }
        }
        handleRequest(HttpMethod.Get, "/two").apply {
            assertStatus(HttpStatusCode.TooManyRequests)
        }
        Thread.sleep(300)
        handleRequest(HttpMethod.Get, "/one").apply {
            assertStatus(HttpStatusCode.OK)
            assertEquals("2", response.headers["X-RateLimit-Remaining"])
        }
        handleRequest(HttpMethod.Get, "/two").apply {
            assertStatus(HttpStatusCode.OK)
            assertEquals("2", response.headers["X-RateLimit-Remaining"])
        }
    }

    // The following test cannot be ran automatically. Uncomment it and run it.
    // You should see in the DEBUG logs a lot of output from
    // guru.zoroark.ratelimit.inmemory like "Removing stale bucket abcdefg...=":
    // that means the purge has ran.
    /*
    @Test
    fun `Check purge has run`(): Unit = withTestApplication {
        val resetDur = Duration.ofMillis(300)
        with(application) {
            install(RateLimit) {
                limit = 3
                timeBeforeReset = resetDur
                limiter = InMemoryRateLimiter(5, Duration.ofMillis(400))
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
        }
        repeat(100) {
            handleRequest(HttpMethod.Get, "/$it").apply {
                assertStatus(HttpStatusCode.OK)
            }
        }
        Thread.sleep(400)
        handleRequest(HttpMethod.Get, "/1").apply {
            assertStatus(HttpStatusCode.OK)
        }
        Thread.sleep(1000) // Wait for the purge to happen, it runs in the background
    }
    */

    @Test
    fun `Test rate limiting same header on IPv6 same network prefix`() {
        withTestApplication {
            with(application) {
                install(RateLimit)
                routing {
                    rateLimited {
                        get("/") {
                            call.respond(HttpStatusCode.OK)
                        }
                    }
                }

                install(XForwardedHeaderSupport)
            }
            val response1 = handleRequest(HttpMethod.Get, "/") {
                addHeader(
                    HttpHeaders.XForwardedFor,
                    "1c68:5440:2594:c1e6:bfdd:8b97:e61d:b2ff"
                )
            }
            val response2 = handleRequest(HttpMethod.Get, "/") {
                addHeader(
                    HttpHeaders.XForwardedFor,
                    "1c68:5440:2594:c1e6:efc5:7a53:7135:41f"
                )
            }
            assertEquals(
                response1.response.headers["X-RateLimit-Bucket"],
                response2.response.headers["X-RateLimit-Bucket"]
            )
        }
    }


    @Test
    fun `Test rate limiting same header on IPv6 different network prefix`() {
        withTestApplication {
            with(application) {
                install(RateLimit)
                routing {
                    rateLimited {
                        get("/") {
                            call.respond(HttpStatusCode.OK)
                        }
                    }
                }

                install(XForwardedHeaderSupport)
            }
            val response1 = handleRequest(HttpMethod.Get, "/") {
                addHeader(
                    HttpHeaders.XForwardedFor,
                    "1c68:5440:2594:c1e6:bfdd:8b97:e61d:b2ff"
                )
            }
            val response2 = handleRequest(HttpMethod.Get, "/") {
                addHeader(
                    HttpHeaders.XForwardedFor,
                    "7e92:2075:327e:f32b:4341:97a2:1440:5a2f"
                )
            }
            assertNotEquals(
                response1.response.headers["X-RateLimit-Bucket"],
                response2.response.headers["X-RateLimit-Bucket"]
            )
        }
    }
}

/*
 * Has some weirdness to avoid rounding errors
 */
fun TestApplicationCall.assertRateLimitedHeaders(
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

    assertEquals(
        expectedLimit.toString(),
        response.headers["X-RateLimit-Limit"]
    )
    assertEquals(
        expectedRemaining.toString(),
        response.headers["X-RateLimit-Remaining"]
    )
    val resetAt =
        if (inMillis)
            Instant.ofEpochMilli((response.headers["X-RateLimit-Reset"]!!.toDouble() * 1000).toLong())
        else
            Instant.ofEpochSecond((response.headers["X-RateLimit-Reset"]!!.toLong()))
    response.headers["X-RateLimit-Reset"]!!.toLong()
    println("Check Reset at ${resetAt.toEpochMilli()} <= actual reset max ${actualResetMax.toEpochMilli()}")
    assertTrue(resetAt <= actualResetMax, "Resets before max")
    val resetAfter =
        if (inMillis)
            Duration.ofMillis((response.headers["X-RateLimit-Reset-After"]!!.toDouble() * 1000).toLong())
        else
            Duration.ofSeconds(response.headers["X-RateLimit-Reset-After"]!!.toLong())
    val expectedResetAfterMillis =
        Duration.between(Instant.now(), actualResetMax).toMillis()
    val expectedResetAfter =
        if (inMillis)
            Duration.ofMillis(expectedResetAfterMillis)
        else
            Duration.ofSeconds(ceil(expectedResetAfterMillis / 1000.0).toLong())
    println("Check reset after ${resetAfter.toMillis()} <= ${expectedResetAfter.toMillis()}}")
    assertTrue(
        resetAfter <= expectedResetAfter,
        "Reset after less than max"
    )
    assertNotNull(response.headers["X-RateLimit-Bucket"], "Has a bucket")
}

private fun ApplicationCall.assertStatus(code: HttpStatusCode) {
    assertEquals(code, this.response.status())
}