package com.twinpad.android.app.hid

/** Perfil nombrado de un modelo de mando (SPEC-011 §3). */
data class GamepadProfile(
    val name: String,
    val mapping: GamepadMapping,
)

/** Perfiles guardados de un fabricante+modelo, con el activo recordado. */
data class GamepadProfileSet(
    val modelKey: String,
    val active: String?,
    val profiles: List<GamepadProfile>,
) {
    fun activeProfile(): GamepadProfile? = profiles.firstOrNull { it.name == active }
}

/** Códec de texto de los perfiles: sin dependencias y determinista para poder
 * probarlo en JVM. Un documento ilegible devuelve `null` (SPEC-011 E2). */
object GamepadProfileCodec {
    private const val HEADER = "twinpad-gamepad-profiles 1"
    private const val PROFILE_PREFIX = "Perfil"
    private const val MODEL_PREFIX = "model="
    private const val ACTIVE_PREFIX = "active="
    private const val PROFILE_LINE_PREFIX = "profile="
    private const val ACTIVE_NONE = "-"
    private const val MODEL_INDEX = 1
    private const val ACTIVE_INDEX = 2
    private const val FIRST_PROFILE_INDEX = 3
    private const val MIN_LINES = 3
    private const val KEY_PARTS = 2
    private const val AXIS_PARTS = 3

    fun encode(set: GamepadProfileSet): String =
        buildString {
            appendLine(HEADER)
            appendLine("$MODEL_PREFIX${escape(set.modelKey)}")
            appendLine("$ACTIVE_PREFIX${set.active?.let(::escape) ?: ACTIVE_NONE}")
            for (profile in set.profiles) {
                appendLine("$PROFILE_LINE_PREFIX${escape(profile.name)}")
                for (control in MappedControl.entries) {
                    val inputs = profile.mapping.inputsOf(control)
                    if (inputs.isEmpty()) continue
                    append(control.name)
                    append('=')
                    appendLine(inputs.joinToString(",", transform = ::encodeInput))
                }
            }
        }

    fun decode(text: String): GamepadProfileSet? {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        val header =
            lines.takeIf { it.size >= MIN_LINES && it.first() == HEADER }?.let(::parseHeader)
        val profiles = header?.let { parseProfiles(lines.drop(FIRST_PROFILE_INDEX)) }
        return header?.let { h ->
            profiles
                ?.takeIf { activeIsKnown(h.active, it) }
                ?.let { GamepadProfileSet(h.model, h.active, it) }
        }
    }

    /** Nombre libre de un perfil nuevo que no choque con los existentes. */
    fun nextProfileName(existing: List<String>): String {
        var index = 1
        while (existing.contains("$PROFILE_PREFIX $index")) index++
        return "$PROFILE_PREFIX $index"
    }

    private fun activeIsKnown(
        active: String?,
        profiles: List<GamepadProfile>,
    ): Boolean = active == null || profiles.any { it.name == active }

    private data class Header(
        val model: String,
        val active: String?,
    )

    private fun parseHeader(lines: List<String>): Header? {
        val model =
            lineValue(lines, MODEL_INDEX, MODEL_PREFIX)
                ?.let(::unescape)
                ?.takeIf { it.isNotBlank() }
                ?: return null
        val activeRaw = lineValue(lines, ACTIVE_INDEX, ACTIVE_PREFIX) ?: return null
        return Header(model, activeRaw.takeIf { it != ACTIVE_NONE }?.let(::unescape))
    }

    private fun lineValue(
        lines: List<String>,
        index: Int,
        prefix: String,
    ): String? =
        lines.getOrNull(index)
            ?.takeIf { it.startsWith(prefix) }
            ?.removePrefix(prefix)

    private fun parseProfiles(lines: List<String>): List<GamepadProfile>? = ProfileParser().parse(lines)

    /** Parser incremental de bloques `profile=` y sus líneas de control. */
    private class ProfileParser {
        private val completed = mutableListOf<GamepadProfile>()
        private var name: String? = null
        private var inputs = mutableMapOf<MappedControl, List<PhysicalInput>>()

        fun parse(lines: List<String>): List<GamepadProfile>? {
            for (line in lines) {
                if (!accept(line)) return null
            }
            if (!flush()) return null
            return completed.takeIf { it.isNotEmpty() }
        }

        private fun accept(line: String): Boolean {
            return if (line.startsWith(PROFILE_LINE_PREFIX)) startProfile(line) else addControl(line)
        }

        private fun startProfile(line: String): Boolean {
            if (!flush()) return false
            name = unescape(line.removePrefix(PROFILE_LINE_PREFIX)).takeIf { it.isNotBlank() } ?: return false
            inputs = mutableMapOf()
            return true
        }

        private fun addControl(line: String): Boolean {
            name ?: return false
            val (control, values) = decodeControl(line) ?: return false
            return if (control in inputs) {
                false
            } else {
                inputs[control] = values
                true
            }
        }

        private fun decodeControl(line: String): Pair<MappedControl, List<PhysicalInput>>? {
            val control =
                MappedControl.entries.firstOrNull { line.startsWith("${it.name}=") } ?: return null
            val values = decodeInputs(line.removePrefix("${control.name}=")) ?: return null
            return control to values
        }

        /** Cierra el bloque vigente; `true` si no había ninguno abierto. */
        private fun flush(): Boolean {
            val current = name ?: return true
            completed += GamepadProfile(current, GamepadMapping(inputs))
            name = null
            return true
        }
    }

    private fun encodeInput(input: PhysicalInput): String =
        when (input) {
            is PhysicalInput.Key -> "k:${input.keyCode}"
            is PhysicalInput.Axis -> "a:${input.axis}:${input.sign}"
        }

    private fun decodeInputs(raw: String): List<PhysicalInput>? =
        if (raw.isEmpty()) {
            emptyList()
        } else {
            raw.split(',').map { decodeInput(it) ?: return null }
        }

    private fun decodeInput(raw: String): PhysicalInput? {
        val parts = raw.split(':')
        return when {
            parts.size == KEY_PARTS && parts[0] == "k" ->
                parts[1].toIntOrNull()?.let(PhysicalInput::Key)
            parts.size == AXIS_PARTS && parts[0] == "a" -> decodeAxis(parts)
            else -> null
        }
    }

    private fun decodeAxis(parts: List<String>): PhysicalInput.Axis? {
        val axis = parts[1].toIntOrNull() ?: return null
        val sign = parts[2].toIntOrNull() ?: return null
        return if (sign == 1 || sign == -1) PhysicalInput.Axis(axis, sign) else null
    }

    private fun escape(value: String): String =
        value
            .replace("%", "%25")
            .replace("\n", "%0A")
            .replace("=", "%3D")

    private fun unescape(value: String): String =
        value
            .replace("%3D", "=")
            .replace("%0A", "\n")
            .replace("%25", "%")
}
