package com.twinpad.android.app.surface

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.twinpad.android.app.R

/**
 * Vista marcador de un control del mapeo táctil — CR-067: el desarrollador
 * la arrastra en `res/layout/profile_map.xml` con el editor de Android
 * Studio (drag & drop); la posición/tamaño llegan por las constraints del
 * layout y los attrs propios (`twinpadKind`/`twinpadLabel`/`twinpadHid`)
 * viajan al modelo `TouchControl` de SPEC-008 §4 en
 * [ProfileMapLayout.extract].
 */
class TwinpadControlView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        /** `button` | `dpad` | `stick` (SPEC-007 §4). */
        var twinpadKind: String

        /** Etiqueta visible (1–16 chars). */
        var twinpadLabel: String

        /** Elemento HID de SPEC-004 §2; "" = sin asignación. */
        var twinpadHid: String

        init {
            val a: TypedArray = context.obtainStyledAttributes(attrs, R.styleable.TwinpadControlView)
            twinpadKind = a.getString(R.styleable.TwinpadControlView_twinpadKind).orEmpty()
            twinpadLabel = a.getString(R.styleable.TwinpadControlView_twinpadLabel).orEmpty()
            twinpadHid = a.getString(R.styleable.TwinpadControlView_twinpadHid).orEmpty()
            a.recycle()
        }

        /** Preview del editor (drag & drop): silueta según kind, sin reglas de
         * render de la superficie; en runtime la superficie lo pinta el
         * renderer con los drawables de `res/drawable`, esta vista solo vive
         * inflada fuera de pantalla para extraer el mapeo. */
        override fun onDraw(canvas: Canvas) {
            if (isInEditMode) drawPreview(canvas)
        }

        private fun drawPreview(canvas: Canvas) {
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = PREVIEW_STROKE_WIDTH
                    color =
                        when (twinpadKind) {
                            ControlProfileCodec.KIND_STICK -> 0xFFB0BEC5.toInt()
                            ControlProfileCodec.KIND_DPAD -> 0xFF492F32.toInt()
                            else -> 0xFFBBBBBB.toInt()
                        }
                }
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) * PREVIEW_RADIUS_FRACTION
            when (twinpadKind) {
                ControlProfileCodec.KIND_STICK -> {
                    canvas.drawCircle(cx, cy, r, paint)
                    canvas.drawCircle(cx, cy, r / 2f, paint)
                }
                ControlProfileCodec.KIND_DPAD -> {
                    canvas.drawLine(cx, cy - r, cx, cy + r, paint)
                    canvas.drawLine(cx - r, cy, cx + r, cy, paint)
                }
                else -> canvas.drawCircle(cx, cy, r, paint)
            }
        }

        private companion object {
            /** Grosor de la silueta del preview del editor. */
            const val PREVIEW_STROKE_WIDTH = 3f

            /** Radio de la silueta respecto a la mitad del lado menor. */
            const val PREVIEW_RADIUS_FRACTION = 1f / 3f
        }
    }
