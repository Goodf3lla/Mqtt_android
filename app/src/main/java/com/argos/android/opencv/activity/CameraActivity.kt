package com.argos.android.opencv.activity

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.text.InputType
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.widget.EditText
import com.android.volley.Request
import com.argos.android.opencv.R
import com.argos.android.opencv.camera.*
import com.argos.android.opencv.driving.DnnHelper
import com.argos.android.opencv.interfaces.ScenarioCallback
import com.argos.android.opencv.lanekeeping.LaneKeeper
import com.argos.android.opencv.model.Feature
import com.argos.android.opencv.model.FpsCounter
import com.argos.android.opencv.mqtt.MqttClientInstance
import com.argos.android.opencv.network.APIController
import com.argos.android.opencv.network.ServiceVolley
import com.argos.android.opencv.scenario.ScenarioManager
import kotlinx.android.synthetic.main.activity_camera.*
import kotlinx.android.synthetic.main.layout_lane_keeping_debug.*
import org.json.JSONObject
import org.opencv.android.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max


class CameraActivity : AppCompatActivity(), CameraBridgeViewBase.CvCameraViewListener2, CameraFrameMangerCaller, ScenarioCallback {
    companion object {
        const val SCREEN_WIDTH = 1280
        const val SCREEN_HEIGHT = 720

        const val PARALELL_HEIGHT = 510.0

        // Paralell line
        const val PARALLEL_RIGHT_X_START = 640.0
        const val PARALLEL_RIGHT_X_END = 1280.0

        var mServerString = "http://10.0.0.3:9080"

        @Volatile
        var rightMidPoint = Point((PARALLEL_RIGHT_X_START + PARALLEL_RIGHT_X_END) / 2, PARALELL_HEIGHT)

        @Volatile
        var isAutonomous = AtomicBoolean(false)
        val fallbackPoints = ConcurrentLinkedQueue<Double>()

        // ACC
        var currentSpeed = 40

    }

    private var usqMqtt = false


    private var decorView: View? = null
    override var mQTTClient: MqttClientInstance = MqttClientInstance(this)

    private var cameraView: CameraBridgeViewBase? = null

    private lateinit var mFeatureString: String
    private var cascadeFilePath: String? = null

    //Overtaking scenario
    private var dnnHelper: DnnHelper = DnnHelper()
    private val service = ServiceVolley()
    private val apiController = APIController(service)
    private var mSpeed = 44.0
    private val mSlowSpeed = 20.0
    private val mFastSpeed = 50.0
    private var mDistance = 0.0
    private var isOvertaking = false

    private var isAccActive = false


    private val laneKeeper = LaneKeeper(this)
    private val scenarioManager = ScenarioManager(this)


    private val loader = object : BaseLoaderCallback(this) {
        override fun onManagerConnected(status: Int) {
            when (status) {
                LoaderCallbackInterface.SUCCESS -> {
                    System.loadLibrary("opencv_java3")
                    cameraView!!.enableView()
                }
                else -> super.onManagerConnected(status)
            }
        }
    }

    private lateinit var mCameraFrameManager: CameraFrameManager

    private var mFpsCounter = FpsCounter()
    private lateinit var mCurrentFrame: Mat
    private var mShowDebug = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        mQTTClient.connect()
        scenarioManager.setMQTTCLient(mQTTClient)
        laneKeeper.setMQTTCLient(mQTTClient)
        initExtras()
        initView()
        initListener()
    }

    override fun getMqttEnabled(): Boolean {
        return usqMqtt
    }


    private fun initExtras() {
        mFeatureString = intent.getStringExtra("feature")
        cascadeFilePath = intent.extras.getString("cascadeFilePath")

        when (mFeatureString) {
            Feature.OVERTAKING -> showInputDialogue()
            Feature.LANE_DETECTION -> mSwitchDebugCamera.visibility = View.VISIBLE
            Feature.LANE_KEEPING -> {
                lane_keeping_status.visibility = View.VISIBLE
                showStartDialog()
                showMQTTDialog()

                lane_keeping_change_mode.setOnClickListener {
                    laneKeeper.toggleAutonomous()
                }

                lane_keeping_set_speed.setOnClickListener {
                    showSetSpeedDialog()
                }

                lane_keeping_calibrate_camera.setOnClickListener {
                    laneKeeper.calibrateValues(getCopyOfCurrentFrame())
                }

                lane_keeping_toggle_acc.setOnClickListener {
                    if (!isAccActive) {
                        scenarioManager.startACC()
                    } else {
                        scenarioManager.stopACC()
                    }
                }
            }

        }
    }

    private fun initView() {
        decorView = window.decorView
        decorView!!.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        cameraView = findViewById(R.id.opencvCameraView)
        cameraView!!.visibility = SurfaceView.VISIBLE
        cameraView!!.setMaxFrameSize(SCREEN_WIDTH, SCREEN_HEIGHT)

        mSwitchDebugCamera.setOnCheckedChangeListener { _, isChecked -> mShowDebug = isChecked }
    }

    private fun initListener() {
        cameraView!!.setCvCameraViewListener(this)
    }

    override fun onPause() {

        if (cameraView != null)
            cameraView!!.disableView()

        mCameraFrameManager.finish()


        super.onPause()

    }

    override fun onDestroy() {
        mQTTClient.disconnect()
        if (cameraView != null)
            cameraView!!.disableView()
        super.onDestroy()

    }

    override fun onResume() {
        super.onResume()

        if (OpenCVLoader.initDebug()) {
            Log.d(CameraActivity::class.java.simpleName, "OpenCV successfully loaded")
            loader.onManagerConnected(LoaderCallbackInterface.SUCCESS)
        } else {
            Log.d(CameraActivity::class.java.simpleName, "OpenCV load failed")
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_2_0, this, loader)
        }

        mCameraFrameManager = CameraFrameManager(this, mFeatureString, dnnHelper, mQTTClient)
        mCameraFrameManager.start()
    }

    override fun onCameraViewStarted(width: Int, height: Int) {
        cameraView!!.enableFpsMeter()
        dnnHelper.onCameraViewStarted(this)

    }

    override fun onCameraViewStopped() {


    }


    override fun onCameraFrame(inputFrame: CameraBridgeViewBase.CvCameraViewFrame): Mat {
        mCurrentFrame = inputFrame.rgba()
        val image = mCurrentFrame.clone()
        Imgproc.cvtColor(image, image, Imgproc.COLOR_RGB2BGR)

        try {
            val frameInfo = mCameraFrameManager.getFrameInfo()
            Core.addWeighted(image, 1.0, frameInfo, 0.7, 0.0, image)
        } catch (e: NoCameraFrameInfoAvailableException) {
        }

        if (mShowDebug)
            setImage(createDebugImage(image))
        else
            setImage(image)

        setFps()

        if (mFeatureString == Feature.OVERTAKING) {
            getCurrentLane()
            setCurrentSpeed()
        }
        return mCurrentFrame
    }

    private fun createDebugImage(image: Mat): Mat {
        return try {
            val debugImage = mCameraFrameManager.getDebugImage()
            val displayedImage = Mat(Size((image.width() + debugImage.width()).toDouble(), max(image.height(), debugImage.height()).toDouble()), CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0))
            image.copyTo(displayedImage.submat(Rect(0, 0, image.width(), image.height())))
            debugImage.copyTo(displayedImage.submat(Rect(image.width(), 0, debugImage.width(), debugImage.height())))
            displayedImage
        } catch (e: NoDebugImageAvailableException) {
            image
        }
    }

    private fun setFps() {
        runOnUiThread {
            txtFps.text = mFpsCounter.getFps()
        }
    }

    override fun getCopyOfCurrentFrame(): Mat {
        mFpsCounter.newFrame()
        try {
            return mCurrentFrame.clone()
        } catch (e: UninitializedPropertyAccessException) {
            throw NoCurrentFrameAvailableException("Current frame not initialized")
        }
    }

    private fun setImage(image: Mat) {
        Imgproc.cvtColor(image, image, Imgproc.COLOR_BGR2RGB)
        val bitmap = Bitmap.createBitmap(image.width(), image.height(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(image, bitmap)

        runOnUiThread {
            imageView!!.setImageBitmap(bitmap)
        }
    }

    private fun showInputDialogue() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Enter server ip")

        // Set up the input
        val input = EditText(this)
        // Specify the type of input expected; this, for example, sets the input as a password, and will mask the text
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_CLASS_NUMBER
        input.setText("10.0.0.3")
        builder.setView(input)

        // Set up the buttons
        builder.setPositiveButton("OK", { dialog, which ->
            mServerString = "http://" + input.text.toString() + ":9080"
            val path = "/setSpeed/"
            val params = JSONObject()
            params.put("speed", mSlowSpeed)
            apiController.request(mServerString + path, params, { response ->
                mSpeed = mSlowSpeed
            }, Request.Method.POST)


            params.put("speed", mFastSpeed)
            val handler = Handler()
            handler.postDelayed({
                apiController.request(mServerString + path, params, { response ->
                    mSpeed = mFastSpeed
                }, Request.Method.POST)
            }, 7000)


        })
        builder.setNegativeButton("Cancel", { dialog, which -> dialog.cancel() })

        builder.show()
    }

    var distanceTreshHold = 0.0

    @SuppressLint("SetTextI18n")
    override fun setDistance(distance: Double) {
        runOnUiThread {
            if (distance < 0.5)
                txtDistance.text = "-"
            else
                txtDistance.text = "${distance}m"
        }
        if (!usqMqtt) {
            checkOverTaking(distance)
        }
    }

    private fun checkOverTaking(distance: Double) {
        if (distance < 10 && mSpeed >= mFastSpeed && mDistance != distance && !isOvertaking) {
            distanceTreshHold++
        }
        mDistance = distance
        if (distanceTreshHold >= 12) {
            distanceTreshHold = 0.0

            val path = "/moveLeft/"
            val params = JSONObject()
            apiController.request(mServerString + path, params, { response ->
                txtCurrentLane.setText("Lane: " + response?.getInt("lane"))
            }, Request.Method.GET)

            isOvertaking = true
            var timePassed = 0
            thread(start = true) {
                while (isOvertaking) {

                    Thread.sleep(250)
                    timePassed += 250

                    val path = "/getSensor/1"
                    apiController.request(mServerString + path, JSONObject(), { response ->
                        if (response != null) {
                            val sensorDistance = response.getDouble("distance")
                            if (sensorDistance < 20 && sensorDistance > 5 || timePassed > 20 * 1000) {
                                isOvertaking = false

                            }
                        }
                    }, Request.Method.GET)
                }
                Thread.sleep(3000)

                val path2 = "/moveRight/"
                apiController.request(mServerString + path2, JSONObject(), { _ ->
                }, Request.Method.GET)

            }
        }

    }

    var i = 0
    private fun getCurrentLane() {
        if (i % 30 == 0 && !mServerString.isNullOrEmpty()) {
            val path = "/getLane/"
            val params = JSONObject()
            apiController.request(mServerString + path, params, { response ->
                txtCurrentLane.setText("Lane: " + response?.getInt("lane"))
            }, Request.Method.GET)
            i = 0

        }
        i++
    }

    private fun setCurrentSpeed() {
        runOnUiThread { txtCurrentSpeed.setText("" + mSpeed + " km/h") }
    }

    private fun showSetSpeedDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Enter speed in km/h")

        // Set up the input
        val input = EditText(this)
        // Specify the type of input expected; this, for example, sets the input as a password, and will mask the text
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.setText("0")
        builder.setView(input)

        // Set up the buttons
        builder.setPositiveButton("OK", { dialog, which ->
            val speed = input.text.toString().toDouble()

            val json = JSONObject()
            json.put("speed", speed)

            apiController.request(mServerString + "/setSpeed/", json, { response ->
                currentSpeed = speed.toInt()
                updateCurrSpeed(currentSpeed.toString())
            }, Request.Method.POST)


        })

        builder.setNegativeButton("Cancel", { dialog, which -> dialog.cancel() })
        builder.show()
    }

    override fun updateCurrSpeed(newVal: String) {
        runOnUiThread {
            lane_keeping_tv_speed.text = newVal
        }
    }

    private fun showMQTTDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Enable Mqtt?")
        // Set up the buttons
        builder.setPositiveButton("YES", { dialog, which -> this.usqMqtt = true })

        builder.setNegativeButton("No", { dialog, which -> this.usqMqtt = false })
        builder.show()


    }

    private fun showStartDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Enter server ip")

        // Set up the input
        val input = EditText(this)
        // Specify the type of input expected; this, for example, sets the input as a password, and will mask the text
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_CLASS_NUMBER
        input.setText("192.168.43.202")
        builder.setView(input)

        // Set up the buttons
        builder.setPositiveButton("OK", { dialog, which ->
            mServerString = "http://" + input.text.toString() + ":9080"
        })

        builder.setNegativeButton("Cancel", { dialog, which -> dialog.cancel() })
        builder.show()
    }

    override fun setSteerText(text: String) {
        runOnUiThread { lane_keeping_status_steer.setText("STEER: " + text) }
    }

    override fun updateACC() {
        if (!isAccActive) {
            runOnUiThread {
                lane_keeping_status_acc.text = "ACC: ON"
                isAccActive = true
            }
        } else {
            runOnUiThread {
                lane_keeping_status_acc.text = "ACC: OFF"
                isAccActive = false
            }
        }
    }

    override fun toggleAutonomous() {

        val newVal = !CameraActivity.isAutonomous.get()
        CameraActivity.isAutonomous.set(newVal)

        if (!isAutonomous.get()) {
            runOnUiThread {
                lane_keeping_status_autonomous.setText("LANE KEEP: OFF")
            }
        } else {
            runOnUiThread {
                lane_keeping_status_autonomous.setText("LANE KEEP: ON")
            }
        }

    }


    override fun showChangeIPDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Enter server ip")

        // Set up the input
        val input = EditText(this)
        // Specify the type of input expected; this, for example, sets the input as a password, and will mask the text
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_CLASS_NUMBER
        input.setText("192.168.0.1")
        builder.setView(input)

        // Set up the buttons
        builder.setPositiveButton("OK", { dialog, which ->
            mServerString = input.text.toString()
        })

        builder.setNegativeButton("Cancel", { dialog, which -> dialog.cancel() })
        builder.show()
    }


    override fun updateFrontSensor(newVal: String) {
        runOnUiThread {
            tv_sensor_front.text = newVal
        }
    }

    override fun updateLeftFrontSensor(newVal: String) {

        runOnUiThread {
            tv_sensor_left_front.text = newVal
        }

    }

    override fun updateRearRightSensor(newVal: String) {
        runOnUiThread {
            tv_sensor_rear_right.text = newVal
        }

    }

    override fun updateRearLeftSensor(newVal: String) {
        runOnUiThread {

            tv_sensor_rear_left.text = newVal
        }

    }

    override fun updateLeftMidSensor(newVal: String) {
        runOnUiThread {
            tv_sensor_left_mid.text = newVal
        }
    }
}
