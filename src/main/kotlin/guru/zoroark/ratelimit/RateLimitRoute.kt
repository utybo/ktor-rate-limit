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

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.application
import java.time.Duration

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
