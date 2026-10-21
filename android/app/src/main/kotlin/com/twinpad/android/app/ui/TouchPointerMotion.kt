package com.twinpad.android.app.ui

import android.view.MotionEvent
import android.view.View

/** One normalized pointer sample used by touch control routing. */
internal data class TouchPointerMotion(
    val event: MotionEvent,
    val pointerIndex: Int,
    val pointerId: Int,
    val normalizedX: Float,
    val normalizedY: Float,
    val view: View,
)
