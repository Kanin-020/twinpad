package com.twinpad.android.app.surface

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.twinpad.android.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TEST-0143 — mapeo táctil como layout del desarrollador (CR-067):
 * `ProfileMapLayout.extract` infla `res/layout/profile_map.xml` fuera de
 * pantalla, normaliza las bounds a coords 0…1 (SPEC-008 §4) y valida con
 * las reglas compartidas del modelo (§9: rechazo con detalle).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ProfileMapLayoutTest {
    private fun inflater(): LayoutInflater =
        LayoutInflater.from(
            ApplicationProvider.getApplicationContext<Context>(),
        )

    private fun extractedRoot(): View {
        val wrapper = FrameLayout(inflater().context)
        inflater().inflate(R.layout.profile_map, wrapper, true)
        val root =
            wrapper.getChildAt(0)
                ?: error("profile_map.xml sin raíz")
        val size = FIXTURE_SIZE
        root.measure(
            View.MeasureSpec.makeMeasureSpec(size.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size.height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, size.width, size.height)
        return root
    }

    private fun controls(root: View): List<TwinpadControlView> =
        (root as ViewGroup).let { group ->
            (0 until group.childCount).mapNotNull { i ->
                group.getChildAt(i) as? TwinpadControlView
            }
        }

    @Test
    fun default_map_layout_maps_all_canonical_controls() {
        val profile = ProfileMapLayout.extract(inflater(), FIXTURE_SIZE.width, FIXTURE_SIZE.height)!!
        ControlProfileCodec.validate(profile)
        assertEquals(15, profile.controls.size)
        val byId = profile.controls.associateBy { it.id }
        for (id in setOf("btn_a", "dpad_cross", "stick_left", "stick_right")) {
            assertTrue("falta control '$id'", id in byId)
        }

        val btnA = byId.getValue("btn_a")
        assertEquals("button", btnA.kind)
        assertEquals("A", btnA.label)
        assertEquals("button_a", btnA.hid)

        val stickLeft = byId.getValue("stick_left")
        assertEquals("stick", stickLeft.kind)
        assertEquals("L", stickLeft.label)
        assertEquals("stick_l", stickLeft.hid)

        val dpad = byId.getValue("dpad_cross")
        assertEquals("dpad", dpad.kind)
        assertEquals("D-pad", dpad.label)
        assertEquals("dpad", dpad.hid)
    }

    @Test
    fun bounds_normalize_to_unit_surface_and_are_valid() {
        // CR-069: el desarrollador puede cambiar posiciones/tamaños del layout
        // libremente — no se fijan valores concretos, solo que el factor de
        // normalización produce coordenadas válidas (SPEC-008 §4/§9).
        val profile = ProfileMapLayout.extract(inflater(), FIXTURE_SIZE.width, FIXTURE_SIZE.height)!!
        val btnA = profile.controls.first { it.id == "btn_a" }
        assertTrue("w de btn_a fuera de 0…1", btnA.w in 0f..1f)
        assertTrue("h de btn_a fuera de 0…1", btnA.h in 0f..1f)
        for (control in profile.controls) {
            assertTrue("x fuera de 0…1: $control", control.x in 0f..1f)
            assertTrue("y fuera de 0…1: $control", control.y in 0f..1f)
        }
    }

    /**
     * Paridad estructural con el default del modelo (TEST-0143): el layout
     * de `profile_map.xml` debe representar los mismos 15 controles canónicos
     * con sus kind/label/hid exactos. Posiciones y tamaños quedan LIBRES
     * (CR-069: el desarrollador los ajusta en Android Studio sin editar el
     * default del modelo; en runtime el layout es la fuente del default).
     */
    @Test
    fun layout_matches_the_model_default_profile() {
        val profile = ProfileMapLayout.extract(inflater(), FIXTURE_SIZE.width, FIXTURE_SIZE.height)!!
        val expected = ControlProfileCodec.defaultProfile()
        assertEquals(
            expected.controls.map { it.id }.toSet(),
            profile.controls.map { it.id }.toSet(),
        )
        for (e in expected.controls) {
            val actual = profile.controls.first { it.id == e.id }
            assertEquals("kind de '${e.id}'", e.kind, actual.kind)
            assertEquals("label de '${e.id}'", e.label, actual.label)
            assertEquals("hid de '${e.id}'", e.hid, actual.hid)
            for (control in listOf(e, actual)) {
                assertTrue("bounds fuera de 0…1: ${control.id}", control.x in 0f..1f && control.y in 0f..1f)
                assertTrue("w/h fuera de 0…1: ${control.id}", control.w in 0f..1f && control.h in 0f..1f)
            }
        }
    }

    @Test
    fun invalid_hid_from_the_layout_is_rejected_with_detail() {
        val root = extractedRoot()
        controls(root).first().twinpadHid = "eje_inexistente"
        assertConversion(root).let { e ->
            assertEquals(ProfileError.INVALID_HID, e.reason)
            assertTrue(e.message!!.contains("eje_inexistente"))
        }
    }

    @Test
    fun duplicate_android_ids_map_to_duplicate_profile_ids() {
        val root = extractedRoot()
        val byId = controls(root).associateBy { it.twinpadLabel }
        // El id de perfil se deduce del recurso android:id: al compartir el
        // mismo recurso, la conversión produce el MISMO id → rechazo (§9).
        byId.getValue("A").id = byId.getValue("B").id
        assertConversion(root).let { e ->
            assertEquals(ProfileError.DUPLICATE_ID, e.reason)
        }
    }

    @Test
    fun control_without_id_or_label_is_a_field_error() {
        val root = extractedRoot()
        val control = controls(root).first()
        control.id = View.NO_ID
        control.twinpadLabel = ""
        assertConversion(root).let { e ->
            assertEquals(ProfileError.FIELD, e.reason)
            assertTrue(e.message!!.contains("twinpadLabel"))
        }
    }

    @Test
    fun bounds_out_of_range_are_rejected_like_the_model() {
        val root = extractedRoot()
        val control = controls(root).first { it.twinpadLabel == "A" }
        // Saca la vista por la izquierda: origen (x_px) queda negativo →
        // coordenada fuera de 0…1 (SPEC-008 §9).
        control.translationX = -control.x - control.width.toFloat()
        assertConversion(root).let { e ->
            assertEquals(ProfileError.OUT_OF_RANGE, e.reason)
        }
    }

    private fun assertConversion(root: View): ProfileException =
        assertThrows(ProfileException::class.java) {
            ProfileMapLayout.profileFrom(
                root,
                root.width.toFloat(),
                root.height.toFloat(),
            )
        }
}

private data class FixtureWidthHeight(
    val width: Int,
    val height: Int,
)

private val FIXTURE_SIZE = FixtureWidthHeight(1080, 608)
