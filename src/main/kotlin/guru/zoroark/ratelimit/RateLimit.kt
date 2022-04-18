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
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationCallPipeline.ApplicationPhase.Plugins
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.Hook
import io.ktor.server.application.RouteScopedPlugin
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
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
import java.util.Base64
import kotlin.properties.Delegates

private const val DEFAULT_IN_MEMORY_RATE_LIMITER_PURGE_SIZE = 100
@Suppress("MagicNumber")
private val DEFAULT_IN_MEMORY_RATE_LIMITER_PURGE_INTERVAL = Duration.ofMinutes(5)
private const val DEFAULT_PER_ROUTE_LIMIT = 50L

/**
 * Configuration class for the Rate Limiting plugin
 */
public class RateLimitConfiguration {
    /**
     * The limiter implementation. See [RateLimiter] for more information.
     *
     * The [RateLimiter] is intended for use with Strings here.
     *
     * Default: An [InMemoryRateLimiter] which needs 100 items stored to
     * purge itself (one purge per five minutes).
     */
    public var limiter: RateLimiter<String> =
        InMemoryRateLimiter(DEFAULT_IN_MEMORY_RATE_LIMITER_PURGE_SIZE, DEFAULT_IN_MEMORY_RATE_LIMITER_PURGE_INTERVAL)

    /**
     * The default limit (i.e. amount of requests) allowed for per-route
     * rate limits. Can be overridden via the [rateLimited] function.
     *
     * Default: 50 requests
     */
    public var limit: Long = DEFAULT_PER_ROUTE_LIMIT

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

public class RateLimitGlobalContext(
    public val limiter: RateLimiter<String>,
    public val limit: Long,
    public val timeBeforeReset: Long,
    public val callerKeyProducer: ApplicationCall.() -> ByteArray,
    public val random: SecureRandom
) {
    public companion object {
        public val key: AttributeKey<RateLimitGlobalContext> = AttributeKey("RateLimitGlobalContext")
    }
}

/**
 * This feature implements rate limiting functionality.
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
 * [callerKeyProducer][RateLimitConfiguration.callerKeyProducer].
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
public val RateLimit: ApplicationPlugin<RateLimitConfiguration> = createApplicationPlugin(
    name = "RateLimit", createConfiguration = ::RateLimitConfiguration
) {
    val random = SecureRandom()
    val context = RateLimitGlobalContext(
        pluginConfig.limiter,
        pluginConfig.limit,
        pluginConfig.timeBeforeReset.toMillis(),
        pluginConfig.callerKeyProducer,
        random
    )
    application.attributes.put(RateLimitGlobalContext.key, context)
}

private val logger = LoggerFactory.getLogger("guru.zoroark.ratelimit")

public class RouteRateLimitConfiguration {
    public lateinit var routeKey: ByteArray
    public var limit: Long by Delegates.notNull()
    public var timeBeforeReset: Long by Delegates.notNull()
    public var additionalKeyExtractor: ApplicationCall.() -> String by Delegates.notNull()
}

internal object RateLimitHook : Hook<suspend PipelineContext<Unit, ApplicationCall>.(ApplicationCall) -> Unit> {
    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: suspend PipelineContext<Unit, ApplicationCall>.(ApplicationCall) -> Unit
    ) {
        pipeline.intercept(Plugins) { handler(call) }
    }
}

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

private const val ROUTE_KEY_BYTE_ARRAY_SIZE = 64

/**
 * Intercepts every call made inside the route block and adds rate-limiting to it.
 *
 * This function requires the [RateLimit] feature to be installed.
 *
 * Optionally, you can override some parameters that will only apply to this route.
 *
 * @param limit Overrides the global limit set when configuring the feature.
 * Maximum amount of requests that can be performed before being rate-limited
 * and receiving HTTP 429 errors.
 * @param timeBeforeReset Overrides the global time before reset set when
 * configuring the feature. Time before a rate-limit expires.
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
    val rateLimitedRoute = createChild(RateLimitedRouteSelector())
    val globalContext =
        application.attributes.getOrNull(RateLimitGlobalContext.key) ?: error("RateLimit feature is not installed")

    val arr = ByteArray(ROUTE_KEY_BYTE_ARRAY_SIZE)
    globalContext.random.nextBytes(arr)
    rateLimitedRoute.install(RateLimitInterceptor) {
        routeKey = arr
        this.limit = limit ?: globalContext.limit
        this.timeBeforeReset = timeBeforeReset?.toMillis() ?: globalContext.timeBeforeReset
        this.additionalKeyExtractor = additionalKeyExtractor
    }

    callback(rateLimitedRoute)

    return rateLimitedRoute
}

public class RateLimitedRouteSelector : RouteSelector() {
    override fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation {
        return RouteSelectorEvaluation.Transparent
    }

    override fun toString(): String {
        return "(rate limited)"
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
