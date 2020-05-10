package guru.zoroark.ratelimit

import org.slf4j.Logger

/**
 * Utility function for logging a lazily-constructed debug message.
 */
internal inline fun Logger.debug(lazyMsg: () -> String) {
    if (this.isDebugEnabled) {
        debug(lazyMsg())
    }
}