package com.twinpad.android.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.twinpad.android.app.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** TEST-063 — SPEC-008: profile control shapes render from editable XML drawables. */
@RunWith(AndroidJUnit4::class)
class ProfileSurfaceRenderingTest {
    @Test
    fun profile_controls_render_on_android_canvas() {
        val scenario = ActivityScenario.launch(PairingActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val surface = ProfileSurface(activity) {}
                val width = 800
                val height = 450
                surface.layout(0, 0, width, height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    surface.draw(Canvas(bitmap))
                    val buttonCenterX = (0.82f + 0.09f / 2f) * width
                    val buttonCenterY = (0.49f + 0.11f / 2f) * height
                    val pixel = bitmap.getPixel(buttonCenterX.toInt(), buttonCenterY.toInt())
                    assertTrue(
                        "The default profile button should be painted by its XML drawable",
                        pixel != android.graphics.Color.TRANSPARENT,
                    )
                    assertTrue(
                        "The surface should load the editable touch-button resource",
                        activity.resources.getDrawable(R.drawable.touch_button, activity.theme) != null,
                    )
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            scenario.close()
        }
    }
}
