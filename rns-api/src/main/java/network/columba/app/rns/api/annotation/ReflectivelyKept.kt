package network.columba.app.rns.api.annotation

/**
 * Marks a class that is invoked **by name across a boundary R8 cannot see** —
 * Chaquopy (Python → Kotlin via `callAttr` / a callback's `onEvent`/`generate`)
 * or JNI-by-name. R8's static analysis never sees those call sites, so without
 * this marker it is free to rename or strip the class and its members, which
 * silently breaks the bridge in minified release builds (see the inbound-voice
 * `PyTwoArgCallback` regression that motivated this).
 *
 * **Protection is class-level.** A single rule in `app/proguard-rules.pro`:
 * ```
 * -keep @network.columba.app.rns.api.annotation.ReflectivelyKept class * { *; }
 * ```
 * keeps the annotated class and **all** its members — current and future — so
 * adding a method to an annotated bridge needs no further action.
 *
 * **Enforced.** The `ReflectivelyKeptRequired` detekt rule (`:detekt-rules`)
 * requires this annotation on the two Chaquopy bridge shapes — `fun interface`s
 * in `:rns-backend-py` and `Kotlin*Bridge` classes — so a new bridge cannot be
 * merged unprotected. The R8 mapping check (when wired) enumerates every
 * `@ReflectivelyKept` class and asserts R8 left its name unchanged.
 *
 * Retention is BINARY (in the class file, not visible to runtime reflection):
 * R8 reads it at build time; nothing needs it at runtime.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class ReflectivelyKept
