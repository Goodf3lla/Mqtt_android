package com.argos.android.opencv.scenario

import android.os.Handler
import android.util.Log
import com.android.volley.Request
import com.argos.android.opencv.activity.CameraActivity
import com.argos.android.opencv.interfaces.ScenarioCallback
import com.argos.android.opencv.network.APIController
import com.argos.android.opencv.network.ServiceVolley
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.properties.Delegates.observable

class ScenarioManager(private val scenarioCallback: ScenarioCallback) {

    private val service = ServiceVolley()
    private val apiController = APIController(service)

    private var accActive = false
    private var readyToOvertake = false
    private var autonomousWasDisabled = false

    private var frontStraightInProgress = false
    private var rearRightInProgress = false
    private var rearLeftInProgress = false
    private var middleLeftInProgress = false
    private var frontLeftInProgress = false

    private var timeRequestStarted = 0L
    private var timeRequestResponded = 0L

    private var speedOfCarInFront = 0

    private val speedMeasurements = mutableListOf<Double>()
    private var isMeasuringSpeed = true
    private var running = false


    private var sensorRearLeft by observable(-1.0) { _, old, new ->
        if (old != new) {
            if (new != 50.0) {
                scenarioCallback.updateRearLeftSensor(String.format("%.1f", new))
            } else {
                scenarioCallback.updateRearLeftSensor("")
            }
        }
    }

    private var sensorRearRight by observable(-1.0) { _, old, new ->
        if (old != new) {
            if (new != 50.0) {
                scenarioCallback.updateRearRightSensor(String.format("%.1f", new))
            } else {
                scenarioCallback.updateRearRightSensor("")
            }
        }

        if (new in 3..29) {
            if (readyToOvertake) {
                finishOvertakeManoeuvre()
            }
        }
    }

    private var sensorLeftMid by observable(-1.0) { _, old, new ->

        if (old != new) {
            if (new != 50.0) {
                scenarioCallback.updateLeftMidSensor(String.format("%.1f", new))
            } else {
                scenarioCallback.updateLeftMidSensor("")
            }
        }

    }

    private var sensorLeftFront by observable(-1.0) { _, old, new ->
        if (old != new) {
            if (new != 50.0) {
                scenarioCallback.updateLeftFrontSensor(String.format("%.1f", new))
            } else {
                scenarioCallback.updateLeftFrontSensor("")
            }
        }

    }

    private var sensorFront by observable(-1.0) { _, old, new ->

        if (new != 100.0 && old != 100.0 && new != -1.0 && old != -1.0) {
            if (new != old) {
                if (isMeasuringSpeed) {
                    // time passed in seconds
                    timeRequestResponded = System.currentTimeMillis()
                    val time = (timeRequestResponded - timeRequestStarted) * 0.001
                    // distance covered by argos vehicle in m
                    val travelled = time * CameraActivity.currentSpeed / 3.6
                    // distance covered by the vehicle in front
                    val delta = travelled - (old - new)
                    val speed = (delta / time) * 3.6

                    val finalSpeed = if (speed in 0..CameraActivity.currentSpeed) speed else 0.0

                    if (finalSpeed != 0.0) {
                        // set speed of car in front temporarily in case of final measurement has not yet been made
                        speedOfCarInFront = round(finalSpeed)
                        speedMeasurements.add(finalSpeed)
                    }


                    if (speedMeasurements.size >= 15) {
                        isMeasuringSpeed = false
                        val avg = speedMeasurements.average()
                        speedOfCarInFront = round(avg)
                    }

                }
            }

            scenarioCallback.updateFrontSensor(String.format("%.1f", new))

        } else {
            scenarioCallback.updateFrontSensor("")
        }


        if (new in 12..15 && !readyToOvertake) {

            if (sensorLeftFront < 50 || sensorLeftMid < 50) {
                // car in middle lane, speed measured, active acc
                if (!accActive && speedOfCarInFront != 0) {
                    adaptiveCruiseControl()
                }
            } else if (speedOfCarInFront < CameraActivity.currentSpeed) {
                overtakeManoeuvre()
            }

        }
    }


    private val exec = Executors.newSingleThreadExecutor()

    fun startACC() {
        running = true
        scenarioCallback.updateACC()
        exec.submit(object : Runnable {
            override fun run() {
                while (running) {
                    getDataForScenarios()
                }
                scenarioCallback.updateACC()
            }
        })
    }

    fun stopACC() {
        running = false
    }


    private fun disableAutonomous() {
        if (CameraActivity.isAutonomous.get()) {
            makeRequest(SET_STEER, Pair("steer", 0.0), Request.Method.POST)
            makeRequest(TOGGLE_AUTONOMOUS, null, Request.Method.POST)
            autonomousWasDisabled = true
        }
    }

    private fun enableAutonomous() {
        if (!CameraActivity.isAutonomous.get() && autonomousWasDisabled) {
            makeRequest(TOGGLE_AUTONOMOUS, null, Request.Method.POST)
            autonomousWasDisabled = false
        }
    }

    private fun getDataForScenarios() {

        if (!frontStraightInProgress) {
            timeRequestStarted = System.currentTimeMillis()
            makeRequest(FRONT_STRAIGHT_SENSOR, null, Request.Method.GET)
            frontStraightInProgress = true
        }

        if (!frontLeftInProgress) {
            makeRequest(FRONT_LEFT_SENSOR, null, Request.Method.GET)
            frontLeftInProgress = true
        }

        if (!rearLeftInProgress) {
            makeRequest(REAR_LEFT_SENSOR, null, Request.Method.GET)
            rearLeftInProgress = true
        }

        if (!rearRightInProgress) {
            makeRequest(REAR_RIGHT_SENSOR, null, Request.Method.GET)
            rearRightInProgress = true
        }

        if (!middleLeftInProgress) {
            makeRequest(MIDDLE_LEFT_SENSOR, null, Request.Method.GET)
            middleLeftInProgress = true
        }
    }

    private fun makeRequest(path: String, parameter: Pair<String, Any>?, method: Int) {

        val json = JSONObject()

        if (parameter != null) {
            json.put(parameter.first, parameter.second)
        }


        apiController.request(CameraActivity.mServerString + path, json, { response ->
            handleResponse(path, response, parameter?.second)
        }, method)


    }

    private fun handleResponse(path: String, response: JSONObject?, parameter: Any?) {
        when (path) {
            FRONT_STRAIGHT_SENSOR -> {
                timeRequestResponded = System.currentTimeMillis()
                sensorFront = response?.getDouble("distance") ?: sensorFront
                frontStraightInProgress = false
            }
            REAR_LEFT_SENSOR -> {
                sensorRearLeft = response?.getDouble("distance") ?: sensorRearLeft
                rearLeftInProgress = false
            }
            REAR_RIGHT_SENSOR -> {
                sensorRearRight = response?.getDouble("distance") ?: sensorRearRight
                rearRightInProgress = false
            }
            MIDDLE_LEFT_SENSOR -> {
                sensorLeftMid = response?.getDouble("distance") ?: sensorLeftMid
                middleLeftInProgress = false
            }
            FRONT_LEFT_SENSOR -> {
                sensorLeftFront = response?.getDouble("distance") ?: sensorLeftFront
                frontLeftInProgress = false
            }

            MOVE_RIGHT -> {
                Log.d("overtake", "move right")
            }

            MOVE_LEFT -> {
                Log.d("overtake", "move left")
            }

            SET_SPEED -> {
                CameraActivity.currentSpeed = speedOfCarInFront
                scenarioCallback.updateCurrSpeed(speedOfCarInFront.toString())
            }

            TOGGLE_AUTONOMOUS -> {
                scenarioCallback.toggleAutonomous()
            }
        }
    }

    private fun overtakeManoeuvre() {
        readyToOvertake = true
        disableAutonomous()
        makeRequest(MOVE_LEFT, null, Request.Method.POST)
    }

    private fun finishOvertakeManoeuvre() {

        makeRequest(MOVE_RIGHT, null, Request.Method.POST)
        readyToOvertake = false
        accActive = false

        Handler().postDelayed({
            enableAutonomous()
        }, 4000)

    }


    private fun adaptiveCruiseControl() {

        // if estimated speed of car in front is less or equal to, we assume that it is not moving
        when (speedOfCarInFront) {
            in 0..10 -> {
                speedOfCarInFront = 0
                makeRequest(SET_SPEED, Pair("speed", 0.0), Request.Method.POST)
                makeRequest(SET_BRAKE, Pair("brake", 1.0), Request.Method.POST)

                // reset breaking after 3s
                Handler().postDelayed({
                    makeRequest(SET_BRAKE, Pair("brake", 0.0), Request.Method.POST)
                }, 3000)

            }

            in 10..CameraActivity.currentSpeed -> makeRequest(SET_SPEED, Pair("speed", speedOfCarInFront.toDouble()), Request.Method.POST)
        }

        accActive = true

    }

    private fun round(num: Double): Int {
        return (Math.ceil(num / 5.0) * 5).toInt()
    }

    companion object {

        const val FRONT_STRAIGHT_SENSOR = "/getSensor/0"
        const val REAR_RIGHT_SENSOR = "/getSensor/1"
        const val REAR_LEFT_SENSOR = "/getSensor/2"
        const val MIDDLE_LEFT_SENSOR = "/getSensor/3"
        const val FRONT_LEFT_SENSOR = "/getSensor/4"
        const val MOVE_LEFT = "/moveLeft"
        const val MOVE_RIGHT = "/moveRight"
        const val SET_SPEED = "/setSpeed"
        const val SET_STEER = "/setSteer"
        const val SET_BRAKE = "/setBrake"
        const val TOGGLE_AUTONOMOUS = "/toggleAutonomous"

    }
}