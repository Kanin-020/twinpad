package com.twinpad.android.app.ui

import com.twinpad.android.app.surface.ControlProfile
import com.twinpad.android.app.surface.ControlProfileCodec
import com.twinpad.android.app.surface.TouchControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * TEST-0145 — prioridad de hitbox y margen de percepción (CR-072): en
 * solape, un tap se lo lleva el motivo de MENOR radio (los clicks L3/R3
 * sobre el plato casi tangente del stick), no el first-match del orden de
 * dibujo; y la hitbox admite un margen (px) alrededor de la forma dibujada
 * (CR-068 + margen). Geometría real del map (retrato 1080×2340):
 * stick_left r ≈ 97 px y button_l3 r ≈ 48.6 px con centros a ≈ 148 px.
 */
class TouchHitPriorityTest {
    /** Superficie del fixture: 1080 × 2340 px (retrato). */
    private val width = 1080f
    private val height = 2340f

    /** Plato del stick (0.18 × 0.24, centro (0.179, 0.810)). */
    private val stick =
        TouchControl("stick_left", ControlProfileCodec.KIND_STICK, "L", "stick_l", 0.0886f, 0.690f, 0.18f, 0.24f)

    /** Click L3 (0.09 × 0.11) junto al stick, casi tangente al plato. */
    private val l3 =
        TouchControl("button_l3", ControlProfileCodec.KIND_BUTTON, "L3", "button_l3", 0.2703f, 0.760f, 0.09f, 0.11f)

    private val profile = ControlProfile("", listOf(stick, l3))

    /** Datos de pantalla de ambos iconos del fixture (px). */
    private data class IconCenter(
        val x: Float,
        val y: Float,
        val radius: Float,
    )

    private fun center(control: TouchControl): IconCenter =
        IconCenter(
            x = (control.x + control.w / 2) * width,
            y = (control.y + control.h / 2) * height,
            radius = minOf(control.w * width, control.h * height) / 2f,
        )

    /** Densidad del fixture: 2.75 → margen 8 dp = 22 px. */
    private val margin = 8f * 2.75f

    @Test
    fun tap_between_the_icons_goes_to_the_smaller_one() {
        // Punto sobre el segmento entre centros, a 55 px del centro del L3:
        // fuera de su cara dibujada (48.6) y DENTRO del plato del stick
        // (148.1 − 55 = 93.1 < 97.2) — el first-match del orden de dibujo
        // (stick primero) se lo llevaba; el objetivo real es el click L3.
        val l3c = center(l3)
        val sc = center(stick)
        val dx = sc.x - l3c.x
        val dy = sc.y - l3c.y
        val centerDistance = sqrt(dx * dx + dy * dy)
        val besideL3 = 55f
        val tapX = l3c.x + dx / centerDistance * besideL3
        val tapY = l3c.y + dy / centerDistance * besideL3
        assertTrue("el tap cae a >48 px del centro del L3", besideL3 > l3c.radius)
        assertTrue("el tap cae dentro del plato del stick dibujado", centerDistance - besideL3 < sc.radius)
        assertEquals(
            "button_l3",
            profile.controls[hitIndex(profile, HitQuery(tapX, tapY, width, height, margin))].id,
        )
    }

    @Test
    fun taps_deep_in_the_stick_plate_reach_the_stick() {
        val c = center(stick)
        assertEquals("stick_left", profile.controls[hitIndex(profile, HitQuery(c.x, c.y, width, height, margin))].id)
    }

    @Test
    fun margin_extends_the_captured_zone_without_losing_the_shape() {
        // A 1.04·radio del centro: FUERA de la forma dibujada del L3 con el
        // contrato estricto de CR-068 (sin margen) y capturado con el margen
        // de percepción de CR-072.
        val c = center(l3)
        val justOutside = c.x + c.radius * 1.04f
        assertFalse(insideDrawnShape(HitQuery(justOutside, c.y, width, height), l3))
        assertTrue(insideDrawnShape(HitQuery(justOutside, c.y, width, height, margin), l3))
    }

    @Test
    fun far_from_every_control_there_is_no_hit() {
        assertEquals(-1, hitIndex(profile, HitQuery(width * 0.5f, height * 0.1f, width, height, margin)))
    }
}
