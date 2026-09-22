package com.vcamstudio.core.result

/** Lightweight result type for engine operations that can fail without crashing. */
sealed interface AppResult<out T> {
    data class Ok<T>(val value: T) : AppResult<T>
    data class Err(val message: String, val cause: Throwable? = null) : AppResult<Nothing>

    companion object {
        inline fun <T> of(block: () -> T): AppResult<T> = try {
            Ok(block())
        } catch (t: Throwable) {
            Err(t.message ?: t.javaClass.simpleName, t)
        }
    }
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Ok -> AppResult.Ok(transform(value))
    is AppResult.Err -> this
}

inline fun <T> AppResult<T>.onErr(block: (AppResult.Err) -> Unit): AppResult<T> {
    if (this is AppResult.Err) block(this)
    return this
}
