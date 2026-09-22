package com.vcamstudio.core.model

/** Immutable integer size. Used across engine boundaries (never android.util.Size). */
data class Size(val width: Int, val height: Int) {
    val aspect: Float
        get() = if (height > 0) width.toFloat() / height.toFloat() else 1f

    companion object {
        val ZERO = Size(0, 0)
    }
}
