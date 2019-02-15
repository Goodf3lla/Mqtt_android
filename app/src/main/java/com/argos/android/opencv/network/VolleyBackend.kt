package com.argos.android.opencv.network

import android.app.Application
import android.text.TextUtils
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.toolbox.Volley
import java.util.*

class BackendVolley : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    private val requestQueue: ArrayList<RequestQueue> by lazy {
        ArrayList<RequestQueue>().also {
            for (i in 1..NUMBER_OF_THREADS) {
                it.add(Volley.newRequestQueue(applicationContext))
            }
        }
    }


    fun <T> addToRequestQueue(request: Request<T>, tag: String) {
        request.tag = if (TextUtils.isEmpty(tag)) TAG else tag
        val req = requestQueue.get(Random().nextInt(requestQueue.size))
        req.add(request)
    }

    fun <T> addToRequestQueue(request: Request<T>) {
        request.tag = TAG
        val req = requestQueue.get(Random().nextInt(requestQueue.size))
        req.add(request)

    }

    fun cancelPendingRequests(tag: Any) {
        if (requestQueue != null) {
            for (req in requestQueue) {
                req.cancelAll(tag)
            }
        }
    }

    companion object {

        const val NUMBER_OF_THREADS = 100

        private val TAG = BackendVolley::class.java.simpleName
        @Volatile
        @get:Synchronized
        var instance: BackendVolley? = null
            private set
    }
}