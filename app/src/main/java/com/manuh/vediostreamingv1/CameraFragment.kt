package com.manuh.vediostreamingv1

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.*
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.manuh.vediostreamingv1.camera.AutoFitTextureView
import java.io.IOException
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class CameraFragment : Fragment(), View.OnClickListener,
    ActivityCompat.OnRequestPermissionsResultCallback, ImageReader.OnImageAvailableListener {

    override fun onImageAvailable(reader: ImageReader?) {
        val image = reader!!.acquireNextImage()
        val buffer = image.planes[0].buffer
        val bytes: Byte = buffer.remaining().toByte()
        buffer.get(bytes.toInt())
        image.close()

        val activity = activity
        if (null != activity) {
            Toast.makeText(activity, bytes.toInt(), Toast.LENGTH_SHORT).show()
        }

    }

    private var mTextureView: AutoFitTextureView? = null

    private var mButtonVideo: Button? = null

    private var mCameraDevice: CameraDevice? = null

//    private var mNetworkWorking = false

    private var mPreviewSession: CameraCaptureSession? = null


    private val mSurfaceTextureListener = object : TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(
            surfaceTexture: SurfaceTexture,
            width: Int, height: Int
        ) {
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(
            surfaceTexture: SurfaceTexture,
            width: Int, height: Int
        ) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
            return true
        }

        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}

    }

    private var mPreviewSize: Size? = null

    private var mVideoSize: Size? = null

    private var mMediaRecorder: MediaRecorder? = null

    private var mIsRecordingVideo: Boolean = false

    private var mBackgroundThread: HandlerThread? = null

    private var mBackgroundHandler: Handler? = null

    private val mCameraOpenCloseLock = Semaphore(1)

    private val mStateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(cameraDevice: CameraDevice) {
            mCameraDevice = cameraDevice
            startPreview()
            mCameraOpenCloseLock.release()
            if (null != mTextureView) {
                configureTransform(mTextureView!!.width, mTextureView!!.height)
            }
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            mCameraOpenCloseLock.release()
            cameraDevice.close()
            mCameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            mCameraOpenCloseLock.release()
            cameraDevice.close()
            mCameraDevice = null
            val activity = activity
            activity?.finish()
        }

    }
    private var mSensorOrientation: Int? = null
    private var mNextVideoAbsolutePath: String? = null
    private var mPreviewBuilder: CaptureRequest.Builder? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_camera, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mTextureView = view.findViewById(R.id.texture)
        mButtonVideo = view.findViewById(R.id.video)
        mButtonVideo!!.setOnClickListener(this)
        view.findViewById<View>(R.id.info).setOnClickListener(this)
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (mTextureView!!.isAvailable) {
            openCamera(mTextureView!!.width, mTextureView!!.height)
        } else {
            mTextureView!!.surfaceTextureListener = mSurfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.video -> {
                if (mIsRecordingVideo) {
                    stopRecordingVideo()
                } else {
                    startRecordingVideo()
                }
            }
            R.id.info -> {
                val activity = activity
                if (null != activity) {
                    AlertDialog.Builder(activity)
                        .setMessage(R.string.intro_message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    private fun startBackgroundThread() {
        mBackgroundThread = HandlerThread("CameraBackground")
        mBackgroundThread!!.start()
        mBackgroundHandler = Handler(mBackgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        mBackgroundThread!!.quitSafely()
        try {
            mBackgroundThread!!.join()
            mBackgroundThread = null
            mBackgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

    }

    private fun shouldShowRequestPermissionRationale(): Boolean {
        for (permission in VIDEO_PERMISSIONS) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    Objects.requireNonNull<FragmentActivity>(
                        this.activity
                    ), permission
                )
            ) {
                return true
            }
        }
        return false
    }

    private fun requestVideoPermissions() {
        if (shouldShowRequestPermissionRationale()) {
            ConfirmationDialog().show(childFragmentManager, FRAGMENT_DIALOG)
        } else {
            ActivityCompat.requestPermissions(
                Objects.requireNonNull<FragmentActivity>(this.activity),
                VIDEO_PERMISSIONS,
                REQUEST_VIDEO_PERMISSIONS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>,
        grantResults: IntArray
    ) {
        Log.d(TAG, "onRequestPermissionsResult")
        if (requestCode == REQUEST_VIDEO_PERMISSIONS) {
            if (grantResults.size == VIDEO_PERMISSIONS.size) {
                for (result in grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        ErrorDialog.newInstance(getString(R.string.permission_request))
                            .show(childFragmentManager, FRAGMENT_DIALOG)
                        break
                    }
                }
            } else {
                ErrorDialog.newInstance(getString(R.string.permission_request))
                    .show(childFragmentManager, FRAGMENT_DIALOG)
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private fun hasPermissionsGranted(): Boolean {
        for (permission in VIDEO_PERMISSIONS) {
            if (ActivityCompat.checkSelfPermission(
                    Objects.requireNonNull<FragmentActivity>(activity),
                    permission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(width: Int, height: Int) {
        if (!hasPermissionsGranted()) {
            requestVideoPermissions()
            return
        }
        val activity = activity
        if (null == activity || activity.isFinishing) {
            return
        }
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            Log.d(TAG, "tryAcquire")
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            val cameraId = manager.cameraIdList[0]

            // Choose the sizes for camera preview and video recording
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
            if (map == null) {
                throw RuntimeException("Cannot get available preview/video sizes")
            }
            mVideoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder::class.java))
            mPreviewSize = chooseOptimalSize(
                map.getOutputSizes(SurfaceTexture::class.java),
                width, height, mVideoSize!!
            )

            val orientation = resources.configuration.orientation
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                mTextureView!!.setAspectRatio(mPreviewSize!!.width, mPreviewSize!!.height)
            } else {
                mTextureView!!.setAspectRatio(mPreviewSize!!.height, mPreviewSize!!.width)
            }
            configureTransform(width, height)
            mMediaRecorder = MediaRecorder()

            manager.openCamera(cameraId, mStateCallback, null)
        } catch (e: CameraAccessException) {
            Toast.makeText(activity, "Cannot access the camera.", Toast.LENGTH_SHORT).show()
            activity.finish()
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                .show(childFragmentManager, FRAGMENT_DIALOG)
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.")
        }

    }

    private fun closeCamera() {
        try {
            mCameraOpenCloseLock.acquire()
            closePreviewSession()
            if (null != mCameraDevice) {
                mCameraDevice!!.close()
                mCameraDevice = null
            }
            if (null != mMediaRecorder) {
                mMediaRecorder!!.release()
                mMediaRecorder = null
            }
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.")
        } finally {
            mCameraOpenCloseLock.release()
        }
    }

    private fun startPreview() {
        if (null == mCameraDevice || !mTextureView!!.isAvailable || null == mPreviewSize) {
            return
        }
        try {
            closePreviewSession()
            val texture = mTextureView!!.surfaceTexture!!
            texture.setDefaultBufferSize(mPreviewSize!!.width, mPreviewSize!!.height)
            mPreviewBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

            val previewSurface = Surface(texture)
            mPreviewBuilder!!.addTarget(previewSurface)

            mCameraDevice!!.createCaptureSession(
                listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(session: CameraCaptureSession) {
                        mPreviewSession = session
                        updatePreview()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        val activity = activity
                        if (null != activity) {
                            Toast.makeText(activity, "Failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }, mBackgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

    }

    private fun updatePreview() {
        if (null == mCameraDevice) {
            return
        }
        try {
            setUpCaptureRequestBuilder(mPreviewBuilder!!)
            val thread = HandlerThread("CameraPreview")
            thread.start()
            mPreviewSession!!.setRepeatingRequest(
                mPreviewBuilder!!.build(),
                null,
                mBackgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

    }

    private fun setUpCaptureRequestBuilder(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val activity = activity
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            return
        }
        val rotation = activity.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect =
            RectF(0f, 0f, mPreviewSize!!.height.toFloat(), mPreviewSize!!.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale =
                (viewHeight.toFloat() / mPreviewSize!!.height).coerceAtLeast(viewWidth.toFloat() / mPreviewSize!!.width)
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        }
        mTextureView!!.setTransform(matrix)
    }

    @Throws(IOException::class)
    private fun setUpMediaRecorder() {
        val activity = activity ?: return
        mMediaRecorder!!.setAudioSource(MediaRecorder.AudioSource.MIC)
        mMediaRecorder!!.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mMediaRecorder!!.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        if (mNextVideoAbsolutePath == null || mNextVideoAbsolutePath!!.isEmpty()) {
            mNextVideoAbsolutePath = getVideoFilePath(getActivity()!!)
        }
        mMediaRecorder!!.setOutputFile(mNextVideoAbsolutePath)
        mMediaRecorder!!.setVideoEncodingBitRate(10000000)
        mMediaRecorder!!.setVideoFrameRate(30)
        mMediaRecorder!!.setVideoSize(mVideoSize!!.width, mVideoSize!!.height)
        mMediaRecorder!!.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mMediaRecorder!!.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        val rotation = activity.windowManager.defaultDisplay.rotation
        when (mSensorOrientation) {
            SENSOR_ORIENTATION_DEFAULT_DEGREES -> mMediaRecorder!!.setOrientationHint(
                DEFAULT_ORIENTATIONS.get(rotation)
            )
            SENSOR_ORIENTATION_INVERSE_DEGREES -> mMediaRecorder!!.setOrientationHint(
                INVERSE_ORIENTATIONS.get(rotation)
            )
        }
        mMediaRecorder!!.prepare()
    }

    private fun getVideoFilePath(context: Context): String {
        val dir = context.getExternalFilesDir(null)
        return ((if (dir == null) "" else dir.absolutePath + "/")
                + System.currentTimeMillis() + ".mp4")
    }

    private fun startRecordingVideo() {
        if (null == mCameraDevice || !mTextureView!!.isAvailable || null == mPreviewSize) {
            return
        }
        try {
            closePreviewSession()
            setUpMediaRecorder()
            val texture = mTextureView!!.surfaceTexture!!
            texture.setDefaultBufferSize(mPreviewSize!!.width, mPreviewSize!!.height)
            mPreviewBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            val surfaces = ArrayList<Surface>()

            // Set up Surface for the camera preview
            val previewSurface = Surface(texture)
            surfaces.add(previewSurface)
            mPreviewBuilder!!.addTarget(previewSurface)

            // Set up Surface for the MediaRecorder
            val recorderSurface = mMediaRecorder!!.surface
            surfaces.add(recorderSurface)
            mPreviewBuilder!!.addTarget(recorderSurface)

            // Start a capture session
            // Once the session starts, we can update the UI and start recording
            mCameraDevice!!.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        mPreviewSession = cameraCaptureSession
                        updatePreview()
                        Objects.requireNonNull<FragmentActivity>(activity).runOnUiThread {
                            // UI
                            mButtonVideo!!.setText(R.string.stop)
                            mIsRecordingVideo = true

                            // Start recording
                            mMediaRecorder!!.start()
                        }
                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        val activity = activity
                        if (null != activity) {
                            Toast.makeText(activity, "Failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                mBackgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }

    }

    private fun closePreviewSession() {
        if (mPreviewSession != null) {
            mPreviewSession!!.close()
            mPreviewSession = null
        }
    }

    private fun stopRecordingVideo() {
        // UI
        mIsRecordingVideo = false
        mButtonVideo!!.setText(R.string.record)
        // Stop recording
        mMediaRecorder!!.stop()
        mMediaRecorder!!.reset()

        val activity = activity
        if (null != activity) {
            Toast.makeText(
                activity, "Video saved: " + mNextVideoAbsolutePath!!,
                Toast.LENGTH_SHORT
            ).show()
            Log.d(TAG, "Video saved: " + mNextVideoAbsolutePath!!)
        }
        mNextVideoAbsolutePath = null
        startPreview()
    }

    internal class CompareSizesByArea : Comparator<Size> {

        override fun compare(lhs: Size, rhs: Size): Int {
            // We cast here to ensure the multiplications won't overflow
            return java.lang.Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
        }

    }

    class ErrorDialog : DialogFragment() {

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val activity = activity!!
            assert(arguments != null)
            return AlertDialog.Builder(activity)
                .setMessage(arguments!!.getString(ARG_MESSAGE))
                .setPositiveButton(android.R.string.ok) { _, _ -> activity.finish() }
                .create()
        }

        companion object {

            private const val ARG_MESSAGE = "message"

            internal fun newInstance(message: String): ErrorDialog {
                val dialog = ErrorDialog()
                val args = Bundle()
                args.putString(ARG_MESSAGE, message)
                dialog.arguments = args
                return dialog
            }
        }

    }

    class ConfirmationDialog : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val parent = parentFragment
            return AlertDialog.Builder(Objects.requireNonNull<FragmentActivity>(activity))
                .setMessage(R.string.permission_request)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    assert(parent != null)
                    ActivityCompat.requestPermissions(
                        Objects.requireNonNull<FragmentActivity>(parent!!.activity),
                        VIDEO_PERMISSIONS,
                        REQUEST_VIDEO_PERMISSIONS
                    )
                }
                .setNegativeButton(
                    android.R.string.cancel
                ) { _, _ ->
                    assert(parent != null)
                    Objects.requireNonNull<FragmentActivity>(parent!!.activity).finish()
                }
                .create()
        }

    }

    companion object {

        private const val SENSOR_ORIENTATION_DEFAULT_DEGREES = 90
        private const val SENSOR_ORIENTATION_INVERSE_DEGREES = 270
        private val DEFAULT_ORIENTATIONS = SparseIntArray()
        private val INVERSE_ORIENTATIONS = SparseIntArray()

        private const val TAG = "Camera2VideoFragment"
        private const val REQUEST_VIDEO_PERMISSIONS = 1
        private const val FRAGMENT_DIALOG = "dialog"

        private val VIDEO_PERMISSIONS =
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

        init {
            DEFAULT_ORIENTATIONS.append(Surface.ROTATION_0, 90)
            DEFAULT_ORIENTATIONS.append(Surface.ROTATION_90, 0)
            DEFAULT_ORIENTATIONS.append(Surface.ROTATION_180, 270)
            DEFAULT_ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }

        init {
            INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 270)
            INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 180)
            INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 90)
            INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 0)
        }


        private fun chooseVideoSize(choices: Array<Size>): Size {
            for (size in choices) {
                if (size.width == size.height * 4 / 3 && size.width <= 1080) {
                    return size
                }
            }
            Log.e(TAG, "Couldn't find any suitable video size")
            return choices[choices.size - 1]
        }


        private fun chooseOptimalSize(
            choices: Array<Size>,
            width: Int,
            height: Int,
            aspectRatio: Size
        ): Size {
            // Collect the supported resolutions that are at least as big as the preview Surface
            val bigEnough = ArrayList<Size>()
            val w = aspectRatio.width
            val h = aspectRatio.height
            for (option in choices) {
                if (option.height == option.width * h / w &&
                    option.width >= width && option.height >= height
                ) {
                    bigEnough.add(option)
                }
            }
            // Pick the smallest of those, assuming we found any
            return if (bigEnough.size > 0) {
                Collections.min(bigEnough, CompareSizesByArea())
            } else {
                Log.e(TAG, "Couldn't find any suitable preview size")
                choices[0]
            }
        }
    }

}
