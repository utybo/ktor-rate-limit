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

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.RouteScopedPlugin
import io.ktor.server.application.application
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.plugins.origin
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.header
import io.ktor.server.response.ApplicationResponse
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64

private val logger = LoggerFactory.getLogger("guru.zoroark.ratelimit")

public val RateLimitInterceptor: RouteScopedPlugin<RouteRateLimitConfiguration> = createRouteScopedPlugin(
    "RateLimitInterceptor", ::RouteRateLimitConfiguration
) {
    val routeKey = requireNotNull(pluginConfig.routeKey) { "Internal error: route key must be set" }
    val routeConfig = pluginConfig
    on(RateLimitHook) { call ->
        val globalContext = application.attributes[RateLimitGlobalContext.key]
        // This is the key generation. We simply SHA1 together all three keys.
        val bucket = sha1(
            globalContext.callerKeyProducer(call),
            routeConfig.additionalKeyExtractor(call).toByteArray(),
            routeKey
        )

        val actualLimit = routeConfig.limit
        val actualTimeBeforeReset = routeConfig.timeBeforeReset
        val rateContext = RateLimitingContext(actualLimit, actualTimeBeforeReset)

        // Handle the rate limit
        val rate = globalContext.limiter.handle(rateContext, bucket)

        // Append information to reply
        val inMillis = call.request.shouldRateLimitTimeBeInMillis()
        val remainingTimeBeforeReset =
            Duration.between(Instant.now(), rate.resetAt)
        call.response.appendRateLimitHeaders(
            rate,
            inMillis,
            remainingTimeBeforeReset,
            rateContext,
            bucket
        )

        // Interrupt call if we should limit it
        if (rate.shouldLimit()) {
            logger.debug {
                "Bucket $bucket (remote host ${call.request.origin.remoteHost}) is being rate limited, " +
                    "resets at ${rate.resetAt}"
            }
            val retryAfter = toSecondsStringWithOptionalDecimals(
                false,
                remainingTimeBeforeReset
            )
            call.response.header(HttpHeaders.RetryAfter, retryAfter) // Always in seconds
            call.respondText(
                ContentType.Application.Json,
                HttpStatusCode.TooManyRequests
            ) {
                """{"message":"You are being rate limited.","retry_after":$retryAfter,"global":false}"""
            }
            finish()
        } else {
            logger.debug {
                "Bucket $bucket (remote host ${call.request.origin.remoteHost}) passes rate limit, " +
                    "remaining = ${rate.remainingRequests - 1}, resets at ${rate.resetAt}"
            }
            proceed()
        }
    }
}

/**
 * Shortcut function for checking if the precision of the rate limit should be in milliseconds (true) or seconds (false)
 */
private fun ApplicationRequest.shouldRateLimitTimeBeInMillis(): Boolean =
    header(RateLimitHeaders.Precision) == "millisecond"

/**
 * Appends rate-limiting related headers to the response
 */
private fun ApplicationResponse.appendRateLimitHeaders(
    rate: Rate,
    withDecimal: Boolean,
    remainingTimeBeforeReset: Duration,
    context: RateLimitingContext,
    bucket: String
) {
    header(RateLimitHeaders.Limit, context.limit)
    header(
        RateLimitHeaders.Remaining,
        (rate.remainingRequests - 1).coerceAtLeast(0)
    )
    header(
        RateLimitHeaders.Reset,
        toSecondsStringWithOptionalDecimals(withDecimal, Duration.ofMillis(rate.resetAt.toEpochMilli()))
    )
    header(
        RateLimitHeaders.ResetAfter,
        toSecondsStringWithOptionalDecimals(withDecimal, remainingTimeBeforeReset)
    )
    header(RateLimitHeaders.Bucket, bucket)
}

private const val MILLIS_IN_ONE_SECOND = 1000L

/**
 * Turns [duration] into either seconds (integer) if [withDecimal] is false or seconds with milliseconds precision
 * (double) if [withDecimal] is true and convert them to a String.
 */
private fun toSecondsStringWithOptionalDecimals(
    withDecimal: Boolean,
    duration: Duration
): String =
    if (withDecimal) (duration.seconds + (duration.toMillisPart() / MILLIS_IN_ONE_SECOND)).toString()
    else duration.toSeconds().toString()

/**
 * Creates a Base 64 string from SHA-1'ing all of the arrays, treating them as a single byte array
 */
private suspend fun sha1(vararg arr: ByteArray): String =
    withContext(Dispatchers.Default) {
        val md = MessageDigest.getInstance("SHA-1")
        arr.forEach { md.update(it) }
        Base64.getEncoder().encodeToString(md.digest())
    }
