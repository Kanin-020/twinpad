package com.twinpad.android.app.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Display
import android.view.Surface
import com.twinpad.android.app.wire.MotionInput
import com.twinpad.android.app.wire.ProtocolConstants

/**
 * Sensores del teléfono usados como gyro del perfil y monitor de orientación.
 * Vive fuera de [PairingActivity] (límite de tamaño de clase, detekt): la
 * Activity registra/desregistra estos listeners en onResume/onPause.
 */
internal class PhoneMotionSensors(
    private val sensorManager: SensorManager,
    private val display: () -> Display,
    private val onMotion: (MotionInput?) -> Unit,
    private val onOrientationTurns: (List<Int>) -> Unit,
) {
    private val rotationVector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val orientationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    /** Gyro del perfil: aceleración + giroscopio con su propio estado. */
    private val motionController =
        ProfileGyroController { sample -> onMotion(sample) }

    private val accelerationListener =
        sensorListener { values, timestamp -> motionController.updateAcceleration(values, timestamp) }
    private val gyroscopeListener =
        sensorListener { values, timestamp -> motionController.updateGyroscope(values, timestamp) }
    private val orientationListener =
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(orientationMatrix, event.values)
                remapOrientationMatrix(orientationMatrix, display().rotation)
                SensorManager.getOrientation(orientationMatrix, orientationAngles)
                onOrientationTurns(GyroOrientationMapper.toScreenTurns(orientationAngles))
            }

            override fun onAccuracyChanged(
                sensor: Sensor?,
                accuracy: Int,
            ) = Unit
        }

    fun start() {
        motionController.reset()
        registerSensor(rotationVector, orientationListener)
        registerSensor(sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), accelerationListener)
        registerSensor(sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE), gyroscopeListener)
    }

    fun stop() {
        sensorManager.unregisterListener(accelerationListener)
        sensorManager.unregisterListener(gyroscopeListener)
        sensorManager.unregisterListener(orientationListener)
        motionController.reset()
    }

    /** Registra el listener solo si el sensor existe en el dispositivo. */
    private fun registerSensor(
        sensor: Sensor?,
        listener: SensorEventListener,
    ) {
        sensor?.let {
            sensorManager.registerListener(
                listener,
                it,
                SensorManager.SENSOR_DELAY_GAME,
                ProtocolConstants.SENSOR_REPORT_INTERVAL_US,
            )
        }
    }
}

/** Listener de sensor que solo reenvía valores y marca de tiempo; la precisión no se usa. */
private fun sensorListener(onValues: (FloatArray, Long) -> Unit): SensorEventListener =
    object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            onValues(event.values, event.timestamp)
        }

        override fun onAccuracyChanged(
            sensor: Sensor?,
            accuracy: Int,
        ) = Unit
    }

/** Reorienta la referencia de ejes del sensor para que siga las coordenadas visibles de la pantalla. */
internal fun remapOrientationMatrix(
    matrix: FloatArray,
    displayRotation: Int,
) {
    val (xAxis, yAxis) =
        when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
    val remapped = FloatArray(matrix.size)
    if (SensorManager.remapCoordinateSystem(matrix, xAxis, yAxis, remapped)) {
        remapped.copyInto(matrix)
    }
}
