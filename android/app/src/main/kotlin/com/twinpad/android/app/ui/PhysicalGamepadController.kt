package com.twinpad.android.app.ui

import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.twinpad.android.app.R
import com.twinpad.android.app.hid.GamepadMapping
import com.twinpad.android.app.hid.gamepadModelKey

/**
 * SPEC-011: ciclo de vida del mando físico en la Activity — detección de
 * mandos externos, perfil activo, mapeador y acción «Mando físico» del
 * drawer. Mantiene `PairingActivity` en los umbrales de tamaño del repo.
 */
internal class PhysicalGamepadController(
    private val activity: AppCompatActivity,
    private val profiles: GamepadProfileStore,
    private val onMapping: (GamepadMapping) -> Unit,
) {
    private val inputManager = activity.getSystemService(InputManager::class.java)
    private val mapper =
        GamepadMapperDialog(activity, profiles) { mapping ->
            onMapping(mapping)
            refreshAction()
        }

    /** Modelos ya considerados en este proceso (la marca persistente vive en
     * [GamepadProfileStore]). */
    private val autoOpened = mutableSetOf<String>()

    private val listener =
        object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                val device = inputManager.getInputDevice(deviceId) ?: return
                if (isPhysicalGamepad(device)) onConnected(device)
            }

            override fun onInputDeviceRemoved(deviceId: Int) {
                mapper.onDeviceRemoved(deviceId)
                refreshAction()
            }

            override fun onInputDeviceChanged(deviceId: Int) = Unit
        }

    fun bind() {
        inputManager.registerInputDeviceListener(listener, null)
        activity.findViewById<Button>(R.id.btn_gamepad_mapper).setOnClickListener {
            connectedGamepad()?.let(mapper::show)
        }
        activity.window.decorView.post {
            connectedGamepad()?.let(::onConnected)
            refreshAction()
        }
    }

    fun stop() {
        inputManager.unregisterInputDeviceListener(listener)
        mapper.dismiss()
    }

    /** SPEC-011: el siguiente botón con una fila en captura es el mapeo. */
    fun onKey(event: KeyEvent): Boolean = mapper.onKey(event)

    /** SPEC-011: el siguiente eje movido con una fila en captura es el mapeo. */
    fun onMotion(event: MotionEvent): Boolean = mapper.onMotion(event)

    /** Mapeo del perfil activo del mando conectado, o el predeterminado. */
    fun mappingForConnected(): GamepadMapping =
        connectedGamepad()?.let { profiles.load(gamepadModelKey(it))?.activeProfile()?.mapping }
            ?: GamepadMapping.DEFAULT

    /** Refresca la acción del drawer (modelo, perfil y disponibilidad). */
    fun refreshAction() {
        val button = activity.findViewById<Button?>(R.id.btn_gamepad_mapper) ?: return
        val device = connectedGamepad()
        button.isEnabled = device != null
        button.text =
            if (device == null) {
                activity.getString(R.string.settings_gamepad_mapper)
            } else {
                val profile =
                    profiles.load(gamepadModelKey(device))?.active
                        ?: activity.getString(R.string.gamepad_mapper_default)
                activity.getString(
                    R.string.settings_gamepad_mapper_connected,
                    gamepadDisplayName(device),
                    profile,
                )
            }
    }

    private fun onConnected(device: InputDevice) {
        val modelKey = gamepadModelKey(device)
        val saved = profiles.load(modelKey)
        onMapping(mappingForConnected())
        // CR-118: solo la primera detección de este modelo en la app abre el
        // mapeador; reconectar o reiniciar no lo vuelve a abrir.
        if (saved == null && autoOpened.add(modelKey) && profiles.claimAutoOpen(modelKey)) {
            mapper.show(device)
        }
        refreshAction()
    }

    private fun connectedGamepad(): InputDevice? =
        inputManager.inputDeviceIds
            .toList()
            .mapNotNull { inputManager.getInputDevice(it) }
            .firstOrNull(::isPhysicalGamepad)

    /** SPEC-011 §2: mando externo (BT HID o USB). Los `uinput` del sistema
     * (p. ej. `uinput-xiaomi`) se anuncian como GAMEPAD pero no son externos
     * ni virtuales, y no deben abrir el mapeador. */
    private fun isPhysicalGamepad(device: InputDevice): Boolean {
        return isGamepadSource(device.sources) && device.isExternal && !device.isVirtual
    }
}
