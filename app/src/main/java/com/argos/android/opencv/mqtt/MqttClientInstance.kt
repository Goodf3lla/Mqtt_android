package com.argos.android.opencv.mqtt


import android.content.Context
import android.util.Log

import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage

import java.io.UnsupportedEncodingException

class MqttClientInstance(context: Context) {

    // private val client: MqttAndroidClient = MqttAndroidClient(context, "tcp://iot.eclipse.org:1883", "AndroidCamera")
    private val client: MqttAndroidClient = MqttAndroidClient(context, "tcp://192.168.43.199:1883", "AndroidCamera")
    private var instance: MqttClientInstance? = null

    private val mqttConnectionOption: MqttConnectOptions
        get() {
            val mqttConnectOptions = MqttConnectOptions()
            mqttConnectOptions.isAutomaticReconnect = true
            mqttConnectOptions.setWill("Front/Middle/SensorOff", "I am going offline".toByteArray(), 1, true)
            return mqttConnectOptions
        }


    @Throws(MqttException::class)
    fun connect() {
        Log.d(javaClass.simpleName, "connecting")

        val token = client.connect(mqttConnectionOption)
        token.actionCallback = object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken) {
                Log.d("MqttClientInstance", "Success")
            }

            override fun onFailure(asyncActionToken: IMqttToken, exception: Throwable) {
                Log.d("MqttClientInstance", "Failure $exception")
            }
        }
    }

    @Throws(MqttException::class)
    fun disconnect() {
        val mqttToken = client.disconnect()
        mqttToken.actionCallback = object : IMqttActionListener {
            override fun onSuccess(iMqttToken: IMqttToken) {
                Log.d("MqttClientInstance", "Successfully disconnected")
            }

            override fun onFailure(iMqttToken: IMqttToken, throwable: Throwable) {
                Log.d("MqttClientInstance", "Failed to disconnected $throwable")
            }
        }
    }


    @Throws(MqttException::class, UnsupportedEncodingException::class)
    fun publishMessage(msg: String, qos: Int, topic: String) {
        var encodedPayload = ByteArray(0)
        encodedPayload = msg.toByteArray(charset("UTF-8"))
        val message = MqttMessage(encodedPayload)
        message.id = 1
        message.isRetained = true
        message.qos = qos
        Log.d(javaClass.simpleName, "publishing topic $topic")
        Log.d(javaClass.simpleName, "with the message $message")
        Log.d(javaClass.simpleName, "with the payload $encodedPayload")
        client.publish("FrontCamera$topic", message)
    }
}
