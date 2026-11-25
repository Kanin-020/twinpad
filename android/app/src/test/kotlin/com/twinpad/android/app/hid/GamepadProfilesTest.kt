package com.twinpad.android.app.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TEST-0210 — SPEC-011: perfiles nombrados por fabricante y modelo,
 * guardado/lectura determinista, perfil activo y rechazo de datos corruptos.
 */
class GamepadProfilesTest {
    @Test
    fun profiles_round_trip_with_the_active_one() {
        val custom =
            GamepadMapping.DEFAULT
                .with(MappedControl.TRIGGER_L2, PhysicalInput.Axis(PhysicalAxes.AXIS_RX, -1))
                .with(MappedControl.TRIGGER_R2, PhysicalInput.Key(PhysicalGamepadAlgo.KeyCodes.BUTTON_R1))
        val set =
            GamepadProfileSet(
                modelKey = "8BitDo|Pro 2",
                active = "Mi perfil",
                profiles =
                    listOf(
                        GamepadProfile("Mi perfil", custom),
                        GamepadProfile("Otro", GamepadMapping.DEFAULT),
                    ),
            )

        val decoded = GamepadProfileCodec.decode(GamepadProfileCodec.encode(set))

        assertEquals(set, decoded)
        assertEquals(custom, decoded?.activeProfile()?.mapping)
    }

    @Test
    fun a_new_profile_name_does_not_repeat() {
        assertEquals("Perfil 1", GamepadProfileCodec.nextProfileName(emptyList()))
        assertEquals("Perfil 2", GamepadProfileCodec.nextProfileName(listOf("Perfil 1")))
        assertEquals("Perfil 3", GamepadProfileCodec.nextProfileName(listOf("Perfil 1", "Perfil 2")))
    }

    @Test
    fun corrupt_documents_are_rejected() {
        assertNull(GamepadProfileCodec.decode(""))
        assertNull(GamepadProfileCodec.decode("twinpad-gamepad-profiles 1\nmodel=x\nactive=-"))
        assertNull(
            GamepadProfileCodec.decode(
                """
                twinpad-gamepad-profiles 1
                model=x
                active=-
                profile=Uno
                NO_EXISTE=k:96
                """.trimIndent(),
            ),
        )
        assertNull(
            GamepadProfileCodec.decode(
                """
                twinpad-gamepad-profiles 1
                model=x
                active=Falta
                profile=Uno
                A=k:96
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun empty_profile_mapping_means_every_control_unassigned() {
        val set =
            GamepadProfileSet(
                modelKey = "x",
                active = "Vacío",
                profiles = listOf(GamepadProfile("Vacío", GamepadMapping(emptyMap()))),
            )

        val decoded = GamepadProfileCodec.decode(GamepadProfileCodec.encode(set))

        assertEquals("Vacío", decoded?.active)
        assertEquals(emptyList<PhysicalInput>(), decoded?.activeProfile()?.mapping?.inputsOf(MappedControl.A))
    }
}
