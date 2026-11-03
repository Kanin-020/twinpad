package com.twinpad.android.app.surface

import android.content.res.Resources
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.twinpad.android.app.R

/**
 * Extrae el `ControlProfile` desde el layout de mapeo del desarrollador —
 * CR-067: `res/layout/profile_map.xml`, editable con drag & drop en el
 * editor de Android Studio. Cada hijo `TwinpadControlView` se convierte a
 * un `TouchControl`: las bounds (px) se normalizan a 0…1 con el tamaño del
 * wrapper (SPEC-008 §4) y el `android:id` de la vista da el id del control.
 * La validación es la MISMA del modelo (≤ 24, hid canónico, coords 0…1 —
 * SPEC-008 §9: rechazo con detalle) y cualquier falla lanza
 * [ProfileException] para que el caller caiga al default.
 */
object ProfileMapLayout {
    /**
     * Infla el layout de mapeo fuera de pantalla con `[width]×[height]` y
     * devuelve el perfil; `null` si la superficie aún no está medida
     * (llamadas tempranas) — el caller usa store/default.
     */
    fun extract(
        inflater: LayoutInflater,
        width: Int,
        height: Int,
    ): ControlProfile? {
        if (width <= 0 || height <= 0) return null
        val wrapper = FrameLayout(inflater.context)
        inflater.inflate(R.layout.profile_map, wrapper, true)
        val root =
            wrapper.getChildAt(0)
                ?: throw ProfileException(ProfileError.MALFORMED, "profile_map sin raíz")
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        return profileFrom(root, width.toFloat(), height.toFloat())
    }

    /** Convierte un contenedor ya inflado y medido a [ControlProfile]. */
    internal fun profileFrom(
        root: View,
        width: Float,
        height: Float,
    ): ControlProfile {
        if (root !is ViewGroup) {
            throw ProfileException(ProfileError.MALFORMED, "raíz de profile_map no es ViewGroup")
        }
        val controls =
            (0 until root.childCount).mapNotNull { i ->
                val child = root.getChildAt(i)
                if (child is TwinpadControlView) toControl(child, root, width, height) else null
            }
        return ControlProfile("", controls).also(ControlProfileCodec::validate)
    }

    /** Un hijo marcador: attrs propios + bounds normalizadas al contenedor. */
    private fun toControl(
        view: TwinpadControlView,
        root: ViewGroup,
        width: Float,
        height: Float,
    ): TouchControl {
        val id = idFor(view, root)
        return TouchControl(
            id = id,
            kind = view.twinpadKind,
            label = view.twinpadLabel,
            hid = view.twinpadHid,
            x = view.x / width,
            y = view.y / height,
            w = view.width / width,
            h = view.height / height,
        )
    }

    /** `android:id` de la vista → nombre de recurso; si falta, la etiqueta. */
    private fun idFor(
        view: TwinpadControlView,
        root: ViewGroup,
    ): String {
        if (view.id == View.NO_ID) {
            if (view.twinpadLabel.isEmpty()) {
                throw ProfileException(
                    ProfileError.FIELD,
                    "TwinpadControlView sin android:id ni twinpadLabel (posición ${view.x}/${view.y})",
                )
            }
            return view.twinpadLabel
        }
        return try {
            root.resources.getResourceEntryName(view.id)
        } catch (e: Resources.NotFoundException) {
            view.twinpadLabel
        }
    }
}
