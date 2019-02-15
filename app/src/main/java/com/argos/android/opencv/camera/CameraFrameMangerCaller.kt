package com.argos.android.opencv.camera

import com.argos.android.opencv.lanekeeping.LaneKeeper
import org.opencv.core.Mat

interface CameraFrameMangerCaller {
    fun getCopyOfCurrentFrame(): Mat
    fun setDistance(distance: Double)
    // Lane Keeping
    fun setSteerText(text : String)
    fun toggleAutonomous()
    fun showChangeIPDialog()
}


class NoCameraFrameInfoAvailableException(override val message: String): Exception(message)

class NoDebugImageAvailableException(override val message: String): Exception(message)

class NoCurrentFrameAvailableException(override val message: String): Exception(message)
