package com.robertlevonyan.demo.camerax.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.GestureDetector
import android.view.View
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.core.impl.ImageCaptureConfig
import androidx.camera.core.impl.PreviewConfig
import androidx.camera.extensions.HdrImageCaptureExtender
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.robertlevonyan.demo.camerax.R
import com.robertlevonyan.demo.camerax.analyzer.LuminosityAnalyzer
import com.robertlevonyan.demo.camerax.databinding.FragmentCameraBinding
import com.robertlevonyan.demo.camerax.enums.CameraTimer
import com.robertlevonyan.demo.camerax.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.properties.Delegates

class CameraFragment : BaseFragment<FragmentCameraBinding>(R.layout.fragment_camera) {
    companion object {
        const val KEY_FLASH = "sPrefFlashCamera"
        const val KEY_GRID = "sPrefGridCamera"
        const val KEY_HDR = "sPrefHDR"

        private val TAG = CameraFragment::class.java.simpleName
    }

    private lateinit var displayManager: DisplayManager
    private lateinit var prefs: SharedPrefsManager
    private lateinit var preview: Preview
    private lateinit var imageCapture: ImageCapture
    private lateinit var imageAnalyzer: ImageAnalysis

    private var displayId = -1
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF

    private var hasGrid = false
    private var hasHdr = false
    private var selectedTimer = CameraTimer.OFF

    /**
     * A display listener for orientation changes that do not trigger a configuration
     * change, for example if we choose to override config change in manifest or for 180-degree
     * orientation changes.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraFragment.displayId) {
                // TODO: maybe need to re-create preview? preview.targetRotation
                imageCapture.setTargetRotation(view.display.rotation)
                imageAnalyzer.setTargetRotation(view.display.rotation)
            }
        } ?: Unit
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = SharedPrefsManager.newInstance(requireContext())
        flashMode = prefs.getInt(KEY_FLASH, ImageCapture.FLASH_MODE_OFF)
        hasGrid = prefs.getBoolean(KEY_GRID, false)
        hasHdr = prefs.getBoolean(KEY_HDR, false)
        initViews()

        displayManager =
            requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, null)

        binding.fragment = this // setting the variable for XML
        binding.viewFinder.addOnAttachStateChangeListener(object :
            View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(v: View) =
                displayManager.registerDisplayListener(displayListener, null)

            override fun onViewAttachedToWindow(v: View) =
                displayManager.unregisterDisplayListener(displayListener)
        })

        // This swipe gesture adds a fun gesture to switch between video and photo
        val swipeGestures = SwipeGestureDetector().apply {
            setSwipeCallback(right = {
                Navigation.findNavController(view).navigate(R.id.action_camera_to_video)
            })
        }
        val gestureDetectorCompat = GestureDetector(requireContext(), swipeGestures)
        view.setOnTouchListener { _, motionEvent ->
            if (gestureDetectorCompat.onTouchEvent(motionEvent)) return@setOnTouchListener false
            return@setOnTouchListener true
        }
    }

    /**
     * Create some initial states
     * */
    private fun initViews() {
        binding.buttonGrid.setImageResource(if (hasGrid) R.drawable.ic_grid_on else R.drawable.ic_grid_off)
        binding.groupGridLines.visibility = if (hasGrid) View.VISIBLE else View.GONE
        adjustInsets()
    }

    /**
     * This methods adds all necessary margins to some views based on window insets and screen orientation
     * */
    private fun adjustInsets() {
        binding.viewFinder.fitSystemWindows()
        binding.fabTakePicture.onWindowInsets { view, windowInsets ->
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                view.bottomMargin = windowInsets.systemWindowInsetBottom
            else view.endMargin = windowInsets.systemWindowInsetRight
        }
        binding.buttonTimer.onWindowInsets { view, windowInsets ->
            view.topMargin = windowInsets.systemWindowInsetTop
        }
    }

    /**
     * Change the facing of camera
     *  toggleButton() function is an Extension function made to animate button rotation
     * */
    @SuppressLint("RestrictedApi")
    fun toggleCamera() = binding.buttonSwitchCamera.toggleButton(
        lensFacing == CameraSelector.LENS_FACING_BACK, 180f,
        R.drawable.ic_outline_camera_rear, R.drawable.ic_outline_camera_front
    ) {
        lensFacing = if (it) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT

        CameraX.getCameraWithLensFacing(lensFacing)
        recreateCamera()
    }

    /**
     * Unbinds all the lifecycles from CameraX, then creates new with new parameters
     * */
    private fun recreateCamera() {
        CameraX.unbindAll()
        startCamera()
    }

    /**
     * Navigate to PreviewFragment
     * */
    fun openPreview() {
        if (!outputDirectory.listFiles().isNullOrEmpty())
            view?.let { Navigation.findNavController(it).navigate(R.id.action_camera_to_preview) }
    }

    /**
     * Show timer selection menu by circular reveal animation.
     *  circularReveal() function is an Extension function which is adding the circular reveal
     * */
    fun selectTimer() = binding.layoutTimerOptions.circularReveal(binding.buttonTimer)

    /**
     * This function is called from XML view via Data Binding to select a timer
     *  possible values are OFF, S3 or S10
     *  circularClose() function is an Extension function which is adding circular close
     * */
    fun closeTimerAndSelect(timer: CameraTimer) =
        binding.layoutTimerOptions.circularClose(binding.buttonTimer) {
            binding.buttonTimer.setImageResource(
                when (timer) {
                    CameraTimer.S3 -> {
                        selectedTimer = CameraTimer.S3
                        R.drawable.ic_timer_3
                    }
                    CameraTimer.S10 -> {
                        selectedTimer = CameraTimer.S10
                        R.drawable.ic_timer_10
                    }
                    else -> {
                        selectedTimer = CameraTimer.OFF
                        R.drawable.ic_timer_off
                    }
                }
            )
        }

    /**
     * Show flashlight selection menu by circular reveal animation.
     *  circularReveal() function is an Extension function which is adding the circular reveal
     * */
    fun selectFlash() = binding.layoutFlashOptions.circularReveal(binding.buttonFlash)

    /**
     * This function is called from XML view via Data Binding to select a FlashMode
     *  possible values are ON, OFF or AUTO
     *  circularClose() function is an Extension function which is adding circular close
     * */
    fun closeFlashAndSelect(flash: Int) =
        binding.layoutFlashOptions.circularClose(binding.buttonFlash) {
            prefs.putInt(KEY_FLASH, flash)
            imageCapture.flashMode = getFlashMode()
        }

    /**
     * Turns on or off the grid on the screen
     * */
    fun toggleGrid() {
        binding.buttonGrid.toggleButton(
            hasGrid,
            180f,
            R.drawable.ic_grid_off,
            R.drawable.ic_grid_on
        ) { flag ->
            hasGrid = flag
            prefs.putBoolean(KEY_GRID, flag)
            binding.groupGridLines.visibility = if (flag) View.VISIBLE else View.GONE
        }
    }

    /**
     * Turns on or off the HDR if available
     * */
    fun toggleHdr() {
        binding.buttonHdr.toggleButton(
            hasHdr,
            360f,
            R.drawable.ic_hdr_off,
            R.drawable.ic_hdr_on
        ) { flag ->
            hasHdr = flag
            prefs.putBoolean(KEY_HDR, flag)
            recreateCamera()
        }
    }

    override fun onPermissionGranted() {
        // Each time apps is coming to foreground the need permission check is being processed
        binding.viewFinder.let { vf ->
            vf.post {
                // Setting current display ID
                displayId = vf.display.displayId
                recreateCamera()
                lifecycleScope.launch(Dispatchers.IO) {
                    // Do on IO Dispatcher
                    // Check if there are any photos or videos in the app directory and preview the last one
                    outputDirectory.listFiles()?.firstOrNull()?.let {
                        setGalleryThumbnail(it)
                    }
                        ?: binding.buttonGallery.setImageResource(R.drawable.ic_no_picture) // or the default placeholder
                }
            }
        }
    }

    private val cameraSelector: CameraSelector
    get() {
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            return CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            return CameraSelector.DEFAULT_BACK_CAMERA
        }
    }


    private fun startCamera() {
        // This is the Texture View where the camera will be rendered
        val viewFinder = binding.viewFinder

        // The ratio for the output image and preview
        val ratio = AspectRatio.RATIO_4_3

        // The Configuration of how we want to preview the camera
        val previewConfig = Preview.Builder().apply {
            setTargetAspectRatio(ratio) // setting the aspect ration
            lensFacing = lensFacing // setting the lens facing (front or back)
            setTargetRotation(viewFinder.display.rotation) // setting the rotation of the camera
        }.build()

        // Create an instance of Camera Preview
        preview = AutoFitPreviewBuilder.build(previewConfig, viewFinder)

        // The Configuration of how we want to capture the image
        val imageCaptureConfig = ImageCapture.Builder().apply {
            setTargetAspectRatio(ratio) // setting the aspect ration
            lensFacing = lensFacing // setting the lens facing (front or back)
            setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY) // setting to have pictures with highest quality possible
            setTargetRotation(viewFinder.display.rotation) // setting the rotation of the camera
            setFlashMode(getFlashMode()) // setting the flash
        }

        imageCapture = imageCaptureConfig.build()
        binding.imageCapture = imageCapture

        // Create a Vendor Extension for HDR
        val hdrImageCapture = HdrImageCaptureExtender.create(imageCaptureConfig)
        // Check if the extension is available on the device
        if (!hdrImageCapture.isExtensionAvailable(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            // If not, hide the HDR button
            binding.buttonHdr.visibility = View.GONE
        } else if (hasHdr) {
            // If yes, turn on if the HDR is turned on by the user
            hdrImageCapture.enableExtension()
        }

        // The Configuration for image analyzing
        val analyzerConfig = ImageAnalysis.Builder().apply {
            // In our analysis, we care more about the latest image than
            // analyzing *every* image
            setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        }.build()

        // Create an Image Analyzer Use Case instance for luminosity analysis
        val analyzerUseCase = analyzerConfig.apply {
            // Use a worker thread for image analysis to prevent glitches
            val analyzerThread = HandlerThread("LuminosityAnalysis").apply { start() }
            setAnalyzer(ThreadExecutor(Handler(analyzerThread.looper)), LuminosityAnalyzer())
        }

        CameraX.bindToLifecycle(viewLifecycleOwner, cameraSelector, imageCapture, analyzerUseCase)
    }

    private fun getFlashMode() = flashMode

    @Suppress("NON_EXHAUSTIVE_WHEN")
    fun takePicture(imageCapture: ImageCapture) = lifecycleScope.launch(Dispatchers.Main) {
        // Show a timer based on user selection
        when (selectedTimer) {
            CameraTimer.S3 -> for (i in 3 downTo 1) {
                binding.textCountDown.text = i.toString()
                delay(1000)
            }
            CameraTimer.S10 -> for (i in 10 downTo 1) {
                binding.textCountDown.text = i.toString()
                delay(1000)
            }
        }
        binding.textCountDown.text = ""
        captureImage(imageCapture)
    }

    /** Helper function used to create a timestamped file */
    private fun createFile(baseFolder: File, format: String, extension: String) =
        File(baseFolder, SimpleDateFormat(format, Locale.US)
            .format(System.currentTimeMillis()) + extension)

    private fun captureImage(imageCapture: ImageCapture) {
        // Create output file to hold the image
        val imageFile = createFile(outputDirectory, "${System.currentTimeMillis()}", "jpg")

        // Setup image capture metadata
        val metadata = ImageCapture.Metadata().apply {

            // Mirror image when using the front camera
            isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(imageFile)
            .setMetadata(metadata)
            .build()
        // Capture the image, first parameter is the file where the image should be stored, the second parameter is the callback after taking a photo
        imageCapture.takePicture(
            outputOptions,
            requireContext().mainExecutor(),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) { // the resulting file of taken photo
                    val savedUri = output.savedUri ?: Uri.fromFile(imageFile)
                    Log.d(TAG, "Photo capture succeeded: $savedUri")
                    // Create small preview
                    setGalleryThumbnail(imageFile)
                }

                override fun onError(exc: ImageCaptureException) {
                    // This function is called if there is some error during capture process
                    val msg = "Photo capture failed: ${exc.message}"
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    Log.e(TAG, msg, exc)
                }
            })
    }

    private fun setGalleryThumbnail(file: File) = binding.buttonGallery.let {
        // Do the work on view's thread, this is needed, because the function is called in a Coroutine Scope's IO Dispatcher
        it.post {
            Glide.with(requireContext())
                .load(file)
                .apply(RequestOptions.circleCropTransform())
                .into(it)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        displayManager.unregisterDisplayListener(displayListener)
    }

    override fun onBackPressed() {
        when {
            binding.layoutTimerOptions.visibility == View.VISIBLE -> binding.layoutTimerOptions.circularClose(
                binding.buttonTimer
            )
            binding.layoutFlashOptions.visibility == View.VISIBLE -> binding.layoutFlashOptions.circularClose(
                binding.buttonFlash
            )
            else -> requireActivity().finish()
        }
    }
}