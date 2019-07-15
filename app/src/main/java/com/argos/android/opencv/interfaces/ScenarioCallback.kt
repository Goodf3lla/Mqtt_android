package com.argos.android.opencv.interfaces

import com.argos.android.opencv.mqtt.MqttClientInstance

interface ScenarioCallback {

    var mQTTClient: MqttClientInstance
    fun updateFrontSensor(newVal: String)
    fun updateLeftFrontSensor(newVal: String)
    fun updateRearRightSensor(newVal: String)
    fun updateRearLeftSensor(newVal: String)
    fun updateLeftMidSensor(newVal: String)
    fun updateCurrSpeed(newVal: String)
    fun toggleAutonomous()
    fun updateACC()
    fun getMqttEnabled(): Boolean

}