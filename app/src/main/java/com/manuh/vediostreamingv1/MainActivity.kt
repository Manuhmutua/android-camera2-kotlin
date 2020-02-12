package com.manuh.vediostreamingv1

import android.Manifest
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat.requestPermissions

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

//        requestPermissions(
//            this, arrayOf(
//                Manifest.permission.CAMERA,
//                Manifest.permission.RECORD_AUDIO
//            ), 1
//        )

//        connection = RTMPConnection()
//        stream = RTMPStream(connection!!)
//        stream?.attachCamera(com.haishinkit.media.Camera(Camera.open()))
//        stream?.attachAudio(Audio())
//        connection?.addEventListener("rtmpStatus", this)
//
//        val button = findViewById<Button>(R.id.button)
//        button.setOnClickListener {
//            connection?.connect("rtmp://192.168.5.152/live?key=longSecurityKey")
//        }


        val fragment: CameraFragment?
        fragment = CameraFragment()

        val transaction = supportFragmentManager.beginTransaction()
        transaction.replace(R.id.container, fragment)
        transaction.commit()
    }

//    override fun onStart() {
//        super.onStart()
//        val view1 = findViewById<CameraView>(R.id.cameraView)
//        view1.attachStream(stream!!)
//    }

}

