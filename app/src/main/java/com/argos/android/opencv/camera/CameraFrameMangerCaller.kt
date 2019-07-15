package com.argos.android.opencv.camera

import com.argos.android.opencv.mqtt.MqttClientInstance
import org.opencv.core.Mat

interface CameraFrameMangerCaller {
    var mQTTClient: MqttClientInstance
    fun getCopyOfCurrentFrame(): Mat
    fun setDistance(distance: Double)
    // Lane Keeping
    fun setSteerText(text : String)
    fun toggleAutonomous()
    fun showChangeIPDialog()
    fun getMqttEnabled(): Boolean
}


class NoCameraFrameInfoAvailableException(override val message: String): Exception(message)

class NoDebugImageAvailableException(override val message: String): Exception(message)

class NoCurrentFrameAvailableException(override val message: String): Exception(message)
