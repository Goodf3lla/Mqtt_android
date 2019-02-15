package com.argos.android.opencv.lanekeeping

import android.util.Log
import com.android.volley.Request
import com.argos.android.opencv.activity.CameraActivity
import com.argos.android.opencv.activity.CameraActivity.Companion.PARALLEL_RIGHT_X_END
import com.argos.android.opencv.activity.CameraActivity.Companion.PARALLEL_RIGHT_X_START
import com.argos.android.opencv.activity.CameraActivity.Companion.rightMidPoint
import com.argos.android.opencv.camera.CameraFrameMangerCaller
import com.argos.android.opencv.network.APIController
import com.argos.android.opencv.network.ServiceVolley
import org.json.JSONObject
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.absoluteValue
import kotlin.math.sign

class LaneKeeper(val observer: CameraFrameMangerCaller) {

    private var mServerString: String = "http://192.168.43.202:9080"
    private val service = ServiceVolley()
    private val apiController = APIController(service)

    val cropPoints = mutableListOf<Point>()


    private val kernel by lazy {
        Mat(5, 5, CvType.CV_8U).setTo(Scalar.all(1.0))
    }

    // Steer values
    private val STEER_RIGHT = -1
    private val STEER_LEFT = 1

    private var steerValue = 0.0

    init {
        cropPoints.add(Point(0.0, 720.0))
        cropPoints.add(Point(0.0, 430.0))
        cropPoints.add(Point(1280.0, 430.0))
        cropPoints.add(Point(1280.0, 720.0))
    }

    @Synchronized
    fun postRequest(path: String, parameter: Pair<String, Any>?) {

        val json = JSONObject()

        if (parameter != null) {
            json.put(parameter.first, parameter.second)
        }

        Log.d(javaClass.simpleName, "$path request: ${System.currentTimeMillis()}")

        apiController.request(mServerString + path, json, { response ->
            handleResponse(path, parameter?.second.toString())
        }, Request.Method.POST)

    }

    @Synchronized
    private fun handleResponse(path: String, postValue: String) {

        Log.d(javaClass.simpleName, "$path respone: ${System.currentTimeMillis()}")
        when (path) {
            STEER -> observer.setSteerText(postValue)
            TOGGLE_AUTONOMOUS -> {
                observer.toggleAutonomous()
            }
        }
    }
    // New Version

    fun initImageProcessing(frame: Mat): Mat {

        // Image processing
        imageProcessing(frame)
        // Apply mask to create ROI)
        applyMaskForROI(frame)
        // Find contours
        val contours = findContours(frame)
        //draw parallles
        drawParallels(frame)
        drawMidPointOfParallels(frame)

        return steerAndDrawMarker(contours, frame)
    }

    private fun imageProcessing(frame: Mat) {
        Imgproc.cvtColor(frame, frame, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(frame, frame, Size(5.0, 5.0), 0.0)
        Imgproc.dilate(frame, frame, kernel)
        Imgproc.Canny(frame, frame, 100.0, 200.0)
    }

    private fun applyMaskForROI(frame: Mat) {
        val matOfPoint = MatOfPoint()
        matOfPoint.fromList(cropPoints)
        val mask = frame.clone()
        mask.setTo(Scalar.all(0.0))
        Imgproc.fillConvexPoly(mask, matOfPoint, Scalar(255.0, 0.0, 0.0))
        Core.bitwise_and(mask, frame, frame)
    }

    private fun findContours(frame: Mat): MutableList<MatOfPoint> {
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(frame, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE)
        Imgproc.cvtColor(frame, frame, Imgproc.COLOR_GRAY2BGR)
        Imgproc.drawContours(frame, contours, -1, Scalar(0.0, 255.0, 0.0), 2)

        return contours
    }

    fun calibrateValues(frame: Mat) {

        Imgproc.cvtColor(frame, frame, Imgproc.COLOR_BGR2GRAY)
        val stencil = frame.clone()
        stencil.setTo(Scalar.all(0.0))
        Imgproc.Canny(frame, frame, 100.0, 200.0)
        val matOfPoint = MatOfPoint()
        matOfPoint.fromList(cropPoints)
        Imgproc.fillConvexPoly(stencil, matOfPoint, Scalar(255.0, 0.0, 0.0))
        Core.bitwise_and(stencil, frame, frame)


        val hierarchy = Mat()

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(frame, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE)
        Imgproc.cvtColor(frame, frame, Imgproc.COLOR_GRAY2BGR)
        drawParallels(frame)

        val rightLaneBoundary = findIntersection(contours)


        if (rightLaneBoundary != null) {
            rightMidPoint = rightLaneBoundary
            Log.d(javaClass.simpleName, "calibration in process...")
        }

        drawMidPointOfParallels(frame)


    }

    private fun drawMidPointOfParallels(frame: Mat) {
        Imgproc.circle(frame, CameraActivity.rightMidPoint, 3, Scalar(200.0, 0.0, 0.0), 2)

    }

    private fun drawParallels(frame: Mat) {
        Imgproc.line(frame, Point(CameraActivity.PARALLEL_RIGHT_X_START, CameraActivity.PARALELL_HEIGHT), Point(CameraActivity.PARALLEL_RIGHT_X_END, CameraActivity.PARALELL_HEIGHT), Scalar(255.0, 0.0, 255.0), 1)
    }


    private fun findIntersection(contours: MutableList<MatOfPoint>): Point? {
        val intersectionPoints = mutableListOf<Point>()
        for (contour in contours) {
            val listOfPoints = contour.toList()
            for (point in listOfPoints) {
                if (point.y in CameraActivity.PARALELL_HEIGHT - 3..CameraActivity.PARALELL_HEIGHT + 3 && (point.x in PARALLEL_RIGHT_X_START..PARALLEL_RIGHT_X_END)) {
                    intersectionPoints.add(point)
                }
            }
        }
        val rightLaneBoundary = intersectionPoints.asSequence().minBy { it.x }
        return rightLaneBoundary
    }

    private fun steerAndDrawMarker(contours: MutableList<MatOfPoint>, frame: Mat): Mat {

        val rightLaneBoundary = findIntersection(contours)
        var distRight = 0.0

        if (rightLaneBoundary != null) {
            Imgproc.line(frame, Point(rightLaneBoundary.x, rightLaneBoundary.y - 20.0), Point(rightLaneBoundary.x, rightLaneBoundary.y + 20.0),
                    Scalar(0.0, 0.0, 255.0), 1)
            distRight = rightLaneBoundary.x - rightMidPoint.x
            Log.d(this.javaClass.simpleName, "right point distance from mid: $distRight")
        }

        val sign = distRight.sign
        val steerDir = if (sign < 0) STEER_LEFT else STEER_RIGHT
        doSteering(distRight, steerDir)

        return frame
    }


    private fun doSteering(distance: Double, steerDirection: Int) {

        val absDistance = distance.absoluteValue

        if (CameraActivity.fallbackPoints.size >= 2) {
            val prevDistance = CameraActivity.fallbackPoints.remove()
            CameraActivity.fallbackPoints.remove()
            val delta = absDistance - prevDistance
            // distance inreased from old val
            // try to do a sharper steer
            if (absDistance >= 13) {
                when (delta) {
                    in -2..2 -> return
                    in 2..10 -> steerValue += (0.04 * steerDirection)
                    in 10..20 -> steerValue += (0.05 * steerDirection)
                    in 20..30 -> steerValue += (0.06 * steerDirection)
                    in 30..40 -> steerValue += (0.07 * steerDirection)
                    in 40..50 -> steerValue += (0.08 * steerDirection)
                    in 100..300 -> steerValue += (0.5 * steerDirection)

                    in -10..-2 -> steerValue -= (0.04 * steerDirection)
                    in -20..-10 -> steerValue -= (0.05 * steerDirection)
                    in -30..-20 -> steerValue -= (0.06 * steerDirection)
                    in -40..-30 -> steerValue -= (0.07 * steerDirection)
                    in -50..-40 -> steerValue -= (0.08 * steerDirection)
                    in -300..-100 -> steerValue -= (0.5 * steerDirection)

                }
            }
        } else {
            steerValue = steerDirection * absDistance / 4000.0
            steerValue = roundToDecimals(steerValue, 5)
        }

        if (distance in -4..4) {
            steerValue = 0.0
        }
        if (absDistance >= 300 && CameraActivity.isAutonomous.get()) {
            toggleAutonomous()
            return
        }

        CameraActivity.fallbackPoints.add(absDistance)
        postRequest(STEER, Pair("steer", steerValue))

    }

    private fun roundToDecimals(number: Double, numDecimalPlaces: Int): Double {
        val factor = Math.pow(10.0, numDecimalPlaces.toDouble())

        return Math.round(number * factor) / factor
    }

    fun toggleAutonomous() {
        steerValue = 0.0
        postRequest(STEER, Pair("steer", 0.0))
        postRequest(TOGGLE_AUTONOMOUS, null)
    }


    companion object {
        private const val STEER = "/setSteer"
        private const val TOGGLE_AUTONOMOUS = "/toggleAutonomous"
    }

}