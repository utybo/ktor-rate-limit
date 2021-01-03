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
import io.ktor.features.origin
import org.slf4j.Logger
import java.net.Inet6Address

/**
 * Utility function for logging a lazily-constructed debug message.
 */
internal inline fun Logger.debug(lazyMsg: () -> String) {
    if (this.isDebugEnabled) {
        debug(lazyMsg())
    }
}

/**
 * The default caller key producer.
 */
fun ApplicationCall.defaultCallerKeyProducer(): ByteArray {
    val host = request.origin.remoteHost
    // Try parsing this as an IPV6 address.
    runCatching {
        Inet6Address.getByName("[$host]")
    }.onSuccess {
        // This is an IPv6 address: only take the first 64 bits (8 bytes)
        // as the caller key
        return it.address.copyOf(8)
    }
    return host.toByteArray()
}