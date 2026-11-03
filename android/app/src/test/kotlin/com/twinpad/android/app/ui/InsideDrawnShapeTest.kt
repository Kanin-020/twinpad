package com.twinpad.android.app.ui

import com.twinpad.android.app.surface.ControlProfileCodec
import com.twinpad.android.app.surface.TouchControl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST-0144 — hitbox del toque = forma dibujada (CR-068): todo control se
 * captura dentro del círculo inscrito en sus bounds (lado menor), el MISMO
 * que pinta `ProfileSurfaceRenderer` (cara del botón, plato del stick y del
 * D-pad). Puntos en las esquinas del rectángulo de bounds —que antes eran
 * hitbox completa— quedan fuera.
 */
class InsideDrawnShapeTest {
    /** Surface de referencia: 1080 × 608 px (misma del TEST-0143). */
    private val width = 1080f
    private val height = 608f

    /** Control 0.2×0.1 centrado en (0.5, 0.5): círculo inscrito r = 0.1·h/2. */
    private val wide =
        TouchControl("t", ControlProfileCodec.KIND_BUTTON, "T", "button_a", 0.4f, 0.45f, 0.2f, 0.1f)

    @Test
    fun center_and_rim_of_the_drawn_circle_capture() {
        val cx = 0.5f * width
        val cy = 0.5f * height
        val r = minOf(0.2f * width, 0.1f * height) / 2f
        assertTrue(insideDrawnShape(HitQuery(cx, cy, width, height), wide))
        // Al borde exacto del círculo (±r horizontal y vertical).
        assertTrue(insideDrawnShape(HitQuery(cx + r * 0.99f, cy, width, height), wide))
        assertTrue(insideDrawnShape(HitQuery(cx, cy + r * 0.99f, width, height), wide))
        assertTrue(insideDrawnShape(HitQuery(cx + r * 0.7f, cy + r * 0.7f, width, height), wide))
    }

    @Test
    fun corners_of_the_bounds_rect_are_outside() {
        val cx = 0.5f * width
        val cy = 0.5f * height
        val r = minOf(0.2f * width, 0.1f * height) / 2f
        // Esquinas del rect: dx = ±r, dy = ±r → fuera del círculo.
        assertFalse(insideDrawnShape(HitQuery(cx + r, cy + r, width, height), wide))
        assertFalse(insideDrawnShape(HitQuery(cx - r, cy - r, width, height), wide))
        assertFalse(insideDrawnShape(HitQuery(cx - r, cy + r, width, height), wide))
        assertFalse(insideDrawnShape(HitQuery(cx + r, cy - r, width, height), wide))
        // Justo fuera del lado menor, donde el rect aún cubría.
        assertFalse(insideDrawnShape(HitQuery(cx - r * 1.01f, cy, width, height), wide))
    }

    @Test
    fun circles_for_equal_sides_match_the_whole_bounds() {
        val square =
            TouchControl("s", ControlProfileCodec.KIND_BUTTON, "S", "button_b", 0.1f, 0.1f, 0.1f, 0.1f)
        val cx = (0.1f + 0.05f) * width
        val cy = (0.1f + 0.05f) * height
        val r = 0.05f * width
        assertTrue(insideDrawnShape(HitQuery(cx, cy, width, height), square))
        assertFalse(insideDrawnShape(HitQuery(cx + r * 1.01f, cy, width, height), square))
    }

    @Test
    fun same_rule_for_stick_and_dpad_kinds() {
        val stick =
            TouchControl("st", ControlProfileCodec.KIND_STICK, "L", "stick_l", 0.1f, 0.2f, 0.15f, 0.12f)
        val dpad =
            TouchControl("dp", ControlProfileCodec.KIND_DPAD, "D", "dpad", 0.3f, 0.2f, 0.2f, 0.16f)
        for (control in listOf(stick, dpad)) {
            val cx = (control.x + control.w / 2) * width
            val cy = (control.y + control.h / 2) * height
            val r = minOf(control.w * width, control.h * height) / 2f
            assertTrue(control.id, insideDrawnShape(HitQuery(cx, cy, width, height), control))
            assertFalse(control.id, insideDrawnShape(HitQuery(cx, cy - r * 1.01f, width, height), control))
        }
    }
}
