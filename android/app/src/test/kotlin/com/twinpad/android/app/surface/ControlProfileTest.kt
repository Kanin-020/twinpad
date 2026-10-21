package com.twinpad.android.app.surface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST-023 — verifies SPEC-008 (§4: ≤ 24 controles, coords 0…1, asignaciones
 * de SPEC-004 §2; §9: rechazo con detalle; §8.2: normalización del formato
 * twinpad-config-v1 de SPEC-007).
 */
class ControlProfileTest {
    private fun button(
        id: String,
        hid: String,
        x: Float = 0.1f,
        y: Float = 0.1f,
    ): TouchControl = TouchControl(id, "button", id.uppercase(), hid, x, y, 0.1f, 0.1f)

    @Test
    fun default_profile_is_valid_and_encodes() {
        val profile = ControlProfileCodec.defaultProfile("pacdemo")
        ControlProfileCodec.validate(profile)
        val round = ControlProfileCodec.decode(ControlProfileCodec.encode(profile))
        assertEquals(profile, round)
    }

    @Test
    fun default_profile_includes_modern_gamepad_controls() {
        val controls = ControlProfileCodec.defaultProfile().controls
        val assignments = controls.map { it.hid }.toSet()

        assertEquals(15, controls.size)
        assertTrue(
            assignments.containsAll(
                setOf(
                    "button_a", "button_b", "button_x", "button_y", "dpad",
                    "stick_l", "stick_r", "button_start", "button_select",
                    "button_l1", "trigger_l2", "button_r1", "trigger_r2",
                ),
            ),
        )
        assertEquals(1, controls.count { it.kind == ControlProfileCodec.KIND_DPAD })
        assertEquals(2, controls.count { it.kind == ControlProfileCodec.KIND_STICK })
        ControlProfileCodec.validate(ControlProfile("", controls))
    }

    @Test
    fun more_than_24_controls_is_rejected() {
        val controls = (0 until 25).map { i -> button("b$i", "button_a", x = (i % 10) / 10f) }
        val e =
            assertThrows(ProfileException::class.java) {
                ControlProfileCodec.validate(ControlProfile("game", controls))
            }
        assertEquals(ProfileError.TOO_MANY_CONTROLS, e.reason)
    }

    @Test
    fun duplicate_id_is_rejected() {
        val controls = listOf(button("a", "button_a"), button("a", "button_b", x = 0.5f))
        val e =
            assertThrows(ProfileException::class.java) {
                ControlProfileCodec.validate(ControlProfile("game", controls))
            }
        assertEquals(ProfileError.DUPLICATE_ID, e.reason)
    }

    @Test
    fun invalid_hid_from_spec004_is_rejected_with_detail() {
        val controls = listOf(button("a", "boton_x"))
        val e =
            assertThrows(ProfileException::class.java) {
                ControlProfileCodec.validate(ControlProfile("game", controls))
            }
        assertEquals(ProfileError.INVALID_HID, e.reason)
        assertTrue(e.message!!.contains("boton_x"))
    }

    @Test
    fun kind_hid_mismatch_is_rejected() {
        // Stick asignado a un botón: inválido (R1 reforzada).
        val stick = TouchControl("s", "stick", "L", "button_a", 0.1f, 0.1f, 0.2f, 0.3f)
        val e =
            assertThrows(ProfileException::class.java) {
                ControlProfileCodec.validate(ControlProfile("game", listOf(stick)))
            }
        assertEquals(ProfileError.INVALID_HID, e.reason)
    }

    @Test
    fun out_of_bounds_coords_are_rejected() {
        val e =
            assertThrows(ProfileException::class.java) {
                ControlProfileCodec.validate(ControlProfile("game", listOf(button("a", "button_a", x = 0.95f))))
            }
        assertEquals(ProfileError.OUT_OF_RANGE, e.reason)
    }

    @Test
    fun unassigned_control_is_valid_but_flagged() {
        // §8.1: sin asignación se permite el perfil; el UI lo atenúa (no envía).
        ControlProfileCodec.validate(ControlProfile("game", listOf(button("a", ""))))
    }

    @Test
    fun external_config_twinpad_v1_normalizes_to_profile() {
        val config =
            """
            {"format":"twinpad-config-v1","game":"mygame","controls":[
              {"id":"jump","kind":"button","label":"Jump","hid":"button_a"},
              {"id":"left","kind":"stick","label":"Stick","hid":"stick_l"}
            ]}
            """.trimIndent()
        val profile = ControlProfileCodec.decodeExternalConfig(config, gameOverride = "")
        assertEquals("mygame", profile.game)
        assertEquals(2, profile.controls.size)
        ControlProfileCodec.validate(profile) // posiciones auto-dispersas válidas
    }

    @Test
    fun external_config_with_bad_format_is_rejected() {
        val e =
            assertThrows(ProfileException::class.java) {
                ControlProfileCodec.decodeExternalConfig(
                    """{"format":"other","game":"x","controls":[]}""",
                    "",
                )
            }
        assertEquals(ProfileError.FIELD, e.reason)
    }
}
