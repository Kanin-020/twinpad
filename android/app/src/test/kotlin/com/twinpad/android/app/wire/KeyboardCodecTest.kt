package com.twinpad.android.app.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** TEST-0109 — verifies SPEC-006 (CR-044): strict `keyboard_request` decoding and
 * canonical `keyboard_input` encoding (base64url UTF-8 full form text). */
class KeyboardCodecTest {
    private fun textOf(line: String): String = line.substringAfter("\"text\":\"").substringBefore("\"}")

    @Test
    fun decodes_on_with_text_and_max_length() {
        val request =
            KeyboardRequestCodec.decode(
                """{"type":"keyboard_request","on":true,"text":"aG9sYQ==","max_length":24}""",
            )!!
        assertTrue(request.on)
        assertEquals("hola", request.text)
        assertEquals(24, request.maxLength)
        // El texto UTF-8 multi-byte round-trips por el contrato del wire.
        val multiline = Base64.getUrlEncoder().withoutPadding().encodeToString("ñá".toByteArray())
        val decoded =
            KeyboardRequestCodec.decode(
                "{\"type\":\"keyboard_request\",\"on\":true,\"text\":\"$multiline\",\"max_length\":64}",
            )!!
        assertEquals("ñá", decoded.text)
        // El form recién abierto llega con texto vacío (base64url ""):
        // es una demanda on válida (paridad con core; form sin texto).
        val empty =
            KeyboardRequestCodec.decode(
                """{"type":"keyboard_request","on":true,"text":"","max_length":8}""",
            )!!
        assertTrue(empty.on)
        assertEquals("", empty.text)
        assertEquals(8, empty.maxLength)
    }

    @Test
    fun decodes_off_without_text_or_limit() {
        assertEquals(
            KeyboardRequestMessage(false, "", 0),
            KeyboardRequestCodec.decode("""{"type":"keyboard_request","on":false}"""),
        )
    }

    @Test
    fun rejects_incoherent_requests() {
        // `on` true sin texto o sin techo.
        assertNull(KeyboardRequestCodec.decode("""{"type":"keyboard_request","on":true}"""))
        assertNull(
            KeyboardRequestCodec.decode("""{"type":"keyboard_request","on":true,"text":"aG9sYQ=="}"""),
        )
        // Techo 0 y fuera de rango (paridad con core 1–4096).
        assertNull(
            KeyboardRequestCodec.decode(
                """{"type":"keyboard_request","on":true,"text":"aG9sYQ==","max_length":0}""",
            ),
        )
        assertNull(
            KeyboardRequestCodec.decode(
                """{"type":"keyboard_request","on":true,"text":"aG9sYQ==","max_length":4097}""",
            ),
        )
        // Texto que no es base64url.
        assertNull(
            KeyboardRequestCodec.decode(
                """{"type":"keyboard_request","on":true,"text":"!!","max_length":8}""",
            ),
        )
        // `off` con techo presente.
        assertNull(
            KeyboardRequestCodec.decode(
                """{"type":"keyboard_request","on":false,"max_length":8}""",
            ),
        )
        // Tipo distinto o JSON inválido.
        assertNull(KeyboardRequestCodec.decode("""{"type":"mic_request","on":true}"""))
        assertNull(KeyboardRequestCodec.decode("no-es-json"))
    }

    @Test
    fun encodes_canonical_update_confirm_cancel() {
        val update = KeyboardInputCodec.encode(KeyboardAction.Update, "hola")
        assertTrue(
            update.startsWith("""{"type":"keyboard_input","action":"update","text":""""),
        )
        // base64url sin relleno: "hola" no produce sufijo '='.
        assertEquals("aG9sYQ", textOf(update))
        assertEquals("hola", String(Base64.getUrlDecoder().decode(textOf(update)), Charsets.UTF_8))

        assertTrue(
            KeyboardInputCodec.encode(KeyboardAction.Confirm, "")
                .startsWith("""{"type":"keyboard_input","action":"confirm","text":""}"""),
        )
        assertTrue(
            KeyboardInputCodec.encode(KeyboardAction.Cancel, "")
                .startsWith("""{"type":"keyboard_input","action":"cancel","text":""}"""),
        )
    }

    @Test
    fun rejects_updates_outside_the_contract() {
        // El vacío con `update` es válido: borra todo el texto del form
        // (toca comprobar que la línea canónica lleva el texto vacío).
        assertTrue(
            KeyboardInputCodec.encode(KeyboardAction.Update, "")
                .startsWith("""{"type":"keyboard_input","action":"update","text":""}"""),
        )
        // `confirm`/`cancel` con texto.
        assertThrows(IllegalArgumentException::class.java) {
            KeyboardInputCodec.encode(KeyboardAction.Confirm, "hola")
        }
        // Texto que excede el techo de bytes (paridad con core 2048).
        assertThrows(IllegalArgumentException::class.java) {
            KeyboardInputCodec.encode(KeyboardAction.Update, "x".repeat(2049))
        }
    }
}
