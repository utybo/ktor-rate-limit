# ktor-rate-limit

## Deprecation notice

**This library is deprecated.** Ktor now has built-in support for rate-limiting, so this library is no longer useful. It will be updated for Ktor 2 for migration purposes - please move to Ktor's own implementation.

## Introduction

A rate-limiting feature for Ktor servers.

```kotlin
install(RateLimit)

routing {
    rateLimited {
        // Rate limits apply to these routes
        get("/limited") {
            // ...
        }
    }
}
```

This library's implementation is loosely inspired by [mantono/ktor-rate-limiting](https://github.com/mantono/ktor-rate-limiting) but aims to be more reliable and have less code weirdnesses. It is *not* a fork, rather a new library that implements some of the good ideas of the original library.

This feature was originally implemented in [EpiLink](https://github.com/EpiLink/EpiLink) but has since been separated
into its own thing. `ktor-rate-limit`'s original code was under the MPL2 (no copyleft exception), but is available under
the Apache 2.0 license in this repository.

## Documentation

Documentation is on zgdocs [here](https://docs.zoroark.guru/#/ktor-rate-limit/). You can also check the KDoc comments,
which have a lot of information.
