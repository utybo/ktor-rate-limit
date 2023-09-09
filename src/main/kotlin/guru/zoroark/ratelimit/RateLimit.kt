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

import guru.zoroark.ratelimit.RateLimit.Configuration
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.BaseApplicationPlugin
import io.ktor.server.application.call
import io.ktor.server.application.plugin
import io.ktor.server.plugins.origin
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.header
import io.ktor.server.response.ApplicationResponse
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.application
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlin.math.ceil

/**
 * This plugin implements rate limiting functionality.
 *
 * Rate limiting is when an API will only allow a certain number of calls be
 * made during a certain amount of time. This plugin implements this behavior.
 * Rate limiting can be done on a per-route basis, meaning that different routes
 * may have different rate limits and be timed differently.
 *
 * This rate-limiting functionality is close to the one
 * [implemented by Discord](https://discordapp.com/developers/docs/topics/rate-limits),
 * with the notable exception that the `Retry-After` header returns durations in
 * **seconds**, not milliseconds.
 * [Discord's implementation does not follow standards](https://github.com/discord/discord-api-docs/issues/1463).
 *
 * This plugin by itself does not limit anything, you need to define which
 * routes are rate limited using the [rateLimited] function. For example:
 *
 * ```
 *  install(RateLimit)
 *  // ...
 *  routing {
 *      route("user") {
 *          rateLimited(...) {
 *              get("something") { ... }
 *              post("somethingElse") { ... }
 *          }
 *      }
 *  }
 * ```
 *
 * Each rate-limited route can have its own limits and reset times. Check the
 * [rateLimited] function for more information.
 *
 * Each rate limit has a unique identifier (called a bucket) that is made of:
 *
 * - A **caller key** unique to the requester, determined using the
 * [callerKeyProducer][Configuration.callerKeyProducer].
 *
 * - A **routing key** unique to the route (where the [rateLimited] function is
 * used). Randomly generated.
 *
 * - An **additional key**, which is especially useful if your route matches
 * multiple paths (e.g. there is some ID in your path) and you want each path to
 * have its own individual limit.
 *
 * All of these keys are SHA-1'd together and turned into a Base 64 string: that
 * is the bucket we return.
 *
 * When rate-limited, this plugin will end the pipeline immediately, returning
 * a HTTP 429 error with a JSON object.
 *
 * ```
 *  {
 *      "message": "You are being rate limited.",
 *      "retry_after": 12345,
 *      "global": false
 *  }
 * ```
 *
 * `global` is always false (global rate limits are not implemented yet) and
 * `retry_after` has the same value as the `Retry-After` header
 *
 * Global rate limits are not implemented yet.
 */
public class RateLimit(configuration: Configuration) {

    /**
     * The (non-standard) headers used by the rate limiting plugins. The names
     * are identical to the ones used by Discord.
     */
    public object Headers {
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

    /**
     * Configuration class for the Rate Limiting plugin
     */
    public class Configuration {
        /**
         * The limiter implementation. See [RateLimiter] for more information.
         *
         * The [RateLimiter] is intended for use with Strings here.
         *
         * Default: An [InMemoryRateLimiter] which needs 100 items stored to
         * purge itself (one purge per hour).
         */
        public var limiter: RateLimiter<String> =
            InMemoryRateLimiter(100, Duration.ofHours(1))

        /**
         * The default limit (i.e. amount of requests) allowed for per-route
         * rate limits. Can be overridden via the [rateLimited] function.
         *
         * Default: 50 requests
         */
        public var limit: Long = 50L

        /**
         * The default amount of time before a rate limit is reset. Can be
         * overridden via the [rateLimited] function.
         *
         * Default: 2 minutes
         */
        public var timeBeforeReset: Duration = Duration.ofMinutes(2)

        /**
         * This is the function that generates caller keys. The default uses the
         * remote host as the caller key.
         */
        public var callerKeyProducer: ApplicationCall.() -> ByteArray = {
            request.origin.remoteHost.toByteArray()
        }
    }

    internal val random = SecureRandom()
    private val rateLimiter = configuration.limiter
    private val limit = configuration.limit
    private val timeBeforeReset = configuration.timeBeforeReset.toMillis()
    private val keyProducer = configuration.callerKeyProducer
    private val logger = LoggerFactory.getLogger("guru.zoroark.ratelimit")

    /**
     * Plugin companion object for the rate limiting plugin
     */
    public companion object Plugin : BaseApplicationPlugin<ApplicationCallPipeline, Configuration, RateLimit> {
        override val key: AttributeKey<RateLimit> = AttributeKey("RateLimit")

        override fun install(
            pipeline: ApplicationCallPipeline,
            configure: Configuration.() -> Unit
        ): RateLimit {
            return RateLimit(Configuration().apply(configure))
        }
    }

    /**
     * Handles a rate-limited call.
     *
     * @param limit Limit override
     * @param timeBeforeReset Reset time override (in milliseconds)
     */
    internal suspend fun handleRateLimitedCall(
        limit: Long?,
        timeBeforeReset: Long?,
        context: PipelineContext<Unit, ApplicationCall>,
        fullKeyProcessor: suspend (ByteArray) -> String
    ) = with(context.call) {
        // Initialize values
        val bucket = fullKeyProcessor(context.call.keyProducer())
        val actualLimit = limit ?: this@RateLimit.limit
        val actualTimeBeforeReset =
            timeBeforeReset ?: this@RateLimit.timeBeforeReset
        val rateContext =
            RateLimitingContext(actualLimit, actualTimeBeforeReset)
        // Handle the rate limit
        val rate = rateLimiter.handle(rateContext, bucket)
        // Append information to reply
        val inMillis = request.shouldRateLimitTimeBeInMillis()
        val remainingTimeBeforeReset =
            Duration.between(Instant.now(), rate.resetAt)
        response.appendRateLimitHeaders(
            rate,
            inMillis,
            remainingTimeBeforeReset,
            rateContext,
            bucket
        )
        // Interrupt call if we should limit it
        if (rate.shouldLimit()) {
            logger.debug { "Bucket $bucket (remote host ${request.origin.remoteHost}) is being rate limited, resets at ${rate.resetAt}" }
            val retryAfter = toHeaderValueWithPrecision(
                false,
                remainingTimeBeforeReset.toMillis()
            )
            // Always in seconds
            response.header(HttpHeaders.RetryAfter, retryAfter)
            respondText(
                ContentType.Application.Json,
                HttpStatusCode.TooManyRequests
            ) {
                """{"message":"You are being rate limited.","retry_after":$retryAfter,"global":false}"""
            }
            context.finish()
        } else {
            logger.debug {
                "Bucket $bucket (remote host ${request.origin.remoteHost}) passes rate limit, remaining = ${rate.remainingRequests - 1}, resets at ${rate.resetAt}"
            }
            context.proceed()
        }
    }
}

/**
 * Intercepts every call made inside the route block and adds rate-limiting to it.
 *
 * This function requires the [RateLimit] plugin to be installed.
 *
 * Optionally, you can override some parameters that will only apply to this route.
 *
 * @param limit Overrides the global limit set when configuring the plugin.
 * Maximum amount of requests that can be performed before being rate-limited
 * and receiving HTTP 429 errors.
 * @param timeBeforeReset Overrides the global time before reset set when
 * configuring the plugin. Time before a rate-limit expires.
 * @param additionalKeyExtractor Function used for retrieving the additional
 * key. See [RateLimit] for more information.
 * @param callback Block for configuring the rate-limited route
 */
public fun Route.rateLimited(
    limit: Long? = null,
    timeBeforeReset: Duration? = null,
    additionalKeyExtractor: ApplicationCall.() -> String = { "" },
    callback: Route.() -> Unit
): Route {
    // Create the route
    val rateLimitedRoute = createChild(object : RouteSelector() {
        override fun evaluate(
            context: RoutingResolveContext,
            segmentIndex: Int
        ) =
            RouteSelectorEvaluation.Constant
    })
    // Rate limiting plugin object
    val rateLimiting = application.plugin(RateLimit)
    // Generate a key for this route
    val arr = ByteArray(64)
    rateLimiting.random.nextBytes(arr)
    // Intercepting every call and checking the rate limit
    rateLimitedRoute.intercept(ApplicationCallPipeline.Plugins) {
        rateLimiting.handleRateLimitedCall(
            limit,
            timeBeforeReset?.toMillis(),
            this
        ) {
            // This is the key generation. We simply SHA1 together all three keys.
            sha1(it, call.additionalKeyExtractor().toByteArray(), arr)
        }
    }
    rateLimitedRoute.callback()
    return rateLimitedRoute
}

/**
 * Shortcut function for checking if the precision of the rate limit should be in milliseconds (true) or seconds (false)
 */
private fun ApplicationRequest.shouldRateLimitTimeBeInMillis(): Boolean =
    header(RateLimit.Headers.Precision) == "millisecond"

/**
 * Appends rate-limiting related headers to the response
 */
private fun ApplicationResponse.appendRateLimitHeaders(
    rate: Rate,
    inMillis: Boolean,
    remainingTimeBeforeReset: Duration,
    context: RateLimitingContext,
    bucket: String
) {
    header(RateLimit.Headers.Limit, context.limit)
    header(
        RateLimit.Headers.Remaining,
        (rate.remainingRequests - 1).coerceAtLeast(0)
    )
    header(
        RateLimit.Headers.Reset,
        toHeaderValueWithPrecision(inMillis, rate.resetAt.toEpochMilli())
    )
    header(
        RateLimit.Headers.ResetAfter,
        toHeaderValueWithPrecision(
            inMillis,
            remainingTimeBeforeReset.toMillis()
        )
    )
    header(RateLimit.Headers.Bucket, bucket)
}

/**
 * Turns [millis] into either seconds (integer) if [inMillis] is false or seconds with milliseconds precision (double)
 * if [inMillis] is true and convert them to a String.
 *
 * @param millis A value in milliseconds
 */
private fun toHeaderValueWithPrecision(
    inMillis: Boolean,
    millis: Long
): String {
    return if (inMillis)
        (millis / 1000.0).toString()
    else
        ceil(millis / 1000.0).toInt().toString()
}

/**
 * Creates a Base 64 string from SHA-1'ing all of the arrays, treating them as a single byte array
 */
private suspend fun sha1(vararg arr: ByteArray): String =
    withContext(Dispatchers.Default) {
        val md = MessageDigest.getInstance("SHA-1")
        arr.forEach { md.update(it) }
        Base64.getEncoder().encodeToString(md.digest())
    }
