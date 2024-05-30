package com.difrancescogianmarco.arcore_flutter_plugin

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.*
import android.graphics.ImageFormat
import android.media.Image
import android.os.Bundle
import android.util.Log
import android.util.Pair
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.difrancescogianmarco.arcore_flutter_plugin.flutter_models.FlutterArCoreHitTestResult
import com.difrancescogianmarco.arcore_flutter_plugin.flutter_models.FlutterArCoreNode
import com.difrancescogianmarco.arcore_flutter_plugin.flutter_models.FlutterArCorePose
import com.difrancescogianmarco.arcore_flutter_plugin.models.RotatingNode
import com.difrancescogianmarco.arcore_flutter_plugin.utils.ArCoreUtils
import com.google.ar.core.*
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.google.ar.sceneform.*
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import java.io.ByteArrayOutputStream

class ArCoreView(val activity: Activity, context: Context, messenger: BinaryMessenger, id: Int, private val debug: Boolean) : PlatformView, MethodChannel.MethodCallHandler {
    private val methodChannel: MethodChannel = MethodChannel(messenger, "arcore_flutter_plugin_$id")
    private val activityLifecycleCallbacks: Application.ActivityLifecycleCallbacks
    private val arSceneView: ArSceneView
    private var mUserRequestedInstall = true
    private val tag: String = ArCoreView::class.java.name
    private val gestureDetector: GestureDetector
    private val permission = 0x123
    private val sceneUpdateListener: Scene.OnUpdateListener

    init {
        methodChannel.setMethodCallHandler(this)
        arSceneView = ArSceneView(context)

        gestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    onSingleTap(e)
                    return true
                }

                override fun onDown(e: MotionEvent): Boolean {
                    return true
                }
            })

        sceneUpdateListener = Scene.OnUpdateListener {
            val frame = arSceneView.arFrame ?: return@OnUpdateListener

            if (frame.camera.trackingState != TrackingState.TRACKING) {
                return@OnUpdateListener
            }

            val updatedAugmentedImages = frame.getUpdatedTrackables(AugmentedImage::class.java)
            for (augmentedImage in updatedAugmentedImages) {
                if (augmentedImageTrackingMethodMap[augmentedImage.index] != augmentedImage.trackingMethod) {
                    augmentedImageTrackingMethodMap[augmentedImage.index] = augmentedImage.trackingMethod
                    debugLog( "${augmentedImage.name} ${augmentedImage.trackingMethod}")
                }
                when (augmentedImage.trackingState) {
                    TrackingState.PAUSED -> {
                    }

                    TrackingState.TRACKING -> {
                        if (!augmentedImageMap.containsKey(augmentedImage.index)) {
                            val centerPoseAnchor = augmentedImage.createAnchor(augmentedImage.centerPose)
                            val anchorNode = AnchorNode()
                            anchorNode.anchor = centerPoseAnchor
                            augmentedImageMap[augmentedImage.index] = Pair.create(augmentedImage, anchorNode)
                            sendAugmentedImageToFlutter(augmentedImage)
                        }
                    }

                    TrackingState.STOPPED -> {
                        val anchorNode = augmentedImageMap[augmentedImage.index]!!.second
                        augmentedImageMap.remove(augmentedImage.index)
                        arSceneView.scene?.removeChild(anchorNode)
                        debugLog( "Detected Image ${augmentedImage.name} STOPPED")
                    }

                    else -> {
                    }
                }

            }

            for (plane in frame.getUpdatedTrackables(Plane::class.java)) {
                if (plane.trackingState == TrackingState.TRACKING) {

                    val pose = plane.centerPose
                    val map: HashMap<String, Any> = HashMap()
                    plane.type.ordinal.also { map["type"] = it }
                    map["centerPose"] = FlutterArCorePose(pose.translation, pose.rotationQuaternion).toHashMap()
                    map["extentX"] = plane.extentX
                    map["extentZ"] = plane.extentZ

                    methodChannel.invokeMethod("onPlaneDetected", map)
                }
            }
        }

        activityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                debugLog("onActivityCreated")
            }

            override fun onActivityStarted(activity: Activity) {
                debugLog("onActivityStarted")
            }

            override fun onActivityResumed(activity: Activity) {
                debugLog("onActivityResumed")
                onResume()
            }

            override fun onActivityPaused(activity: Activity) {
                debugLog("onActivityPaused")
                onPause()
            }

            override fun onActivityStopped(activity: Activity) {
                debugLog("onActivityStopped (Just so you know)")
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
                debugLog("onActivitySaveInstanceState (Just so you know)")

            }

            override fun onActivityDestroyed(activity: Activity) {
                debugLog("onActivityDestroyed (Just so you know)")
            }
        }

        activity.application.registerActivityLifecycleCallbacks(this.activityLifecycleCallbacks)
        ArCoreUtils.requestCameraPermission(activity, permission)
    }

    private val augmentedImageMap = java.util.HashMap<Int, Pair<AugmentedImage, AnchorNode>>()
    private val augmentedImageTrackingMethodMap = java.util.HashMap<Int, AugmentedImage.TrackingMethod>()


    private fun sendAugmentedImageToFlutter(augmentedImage: AugmentedImage) {
        val map: java.util.HashMap<String, Any> = java.util.HashMap<String, Any>()
        map["name"] = augmentedImage.name
        map["index"] = augmentedImage.index
        map["extentX"] = augmentedImage.extentX
        map["extentZ"] = augmentedImage.extentZ
        map["centerPose"] = FlutterArCorePose.fromPose(augmentedImage.centerPose).toHashMap()
        map["trackingMethod"] = augmentedImage.trackingMethod.ordinal
        activity.runOnUiThread {
            methodChannel.invokeMethod("onTrackingImage", map)
        }
    }

    fun debugLog(message: String) {
        if (debug) {
            Log.i(tag, message)
        }
    }


    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "init" -> {
                arSceneViewInit(call, result)
            }
            "load_single_image_on_db" -> {
                onLoadSingleImageOnDb(call, result)
            }
            "attachObjectToAugmentedImage" -> {
                onAttachObjectToAugmentedImage(call, result)
            }
            "addArCoreNode" -> {
                debugLog(" addArCoreNode")
                val map = call.arguments as HashMap<*, *>
                val flutterNode = FlutterArCoreNode(map)
                onAddNode(flutterNode, result)
            }
            "addArCoreNodeWithAnchor" -> {
                debugLog(" addArCoreNode")
                val map = call.arguments as HashMap<*, *>
                val flutterNode = FlutterArCoreNode(map)
                 addNodeWithAnchor(flutterNode, result)
            }
            "removeARCoreNode" -> {
                debugLog(" removeARCoreNode")
                val map = call.arguments as HashMap<*, *>
                removeNode(map["nodeName"] as String, result)
            }
            "positionChanged" -> {
                debugLog(" positionChanged")

            }
            "getCameraPosition" -> {
                val cameraPosition = getCameraPosition()
                if (cameraPosition != null) {
                    result.success(cameraPosition)
                } else {
                    result.error("UNAVAILABLE", "Could not fetch camera position.", null)
                }
            }
            "getNodePosition" -> {
                val name = call.arguments as String
                result.success(getNodePosition(name))
            }
            "rotationChanged" -> {
                debugLog(" rotationChanged")
                updateRotation(call, result)

            }
            "updateMaterials" -> {
                debugLog(" updateMaterials")
                updateMaterials(call, result)

            }
            "takePicture" -> {
                onTakePicture(result)
            }
            "dispose" -> {
                debugLog("Disposing ARCore now")
                dispose()
            }
            "resume" -> {
                debugLog("Resuming ARCore now")
                onResume()
            }
            "getTrackingState" -> {
                debugLog("1/3: Requested tracking state, returning that back to Flutter now")

                val trState = arSceneView.arFrame?.camera?.trackingState
                debugLog("2/3: Tracking state is " + trState.toString())
                methodChannel.invokeMethod("getTrackingState", trState.toString())
            }
            "togglePlaneRenderer" -> {
                debugLog(" Toggle planeRenderer visibility" )
                arSceneView.planeRenderer.isVisible = !arSceneView.planeRenderer.isVisible
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    private fun onAttachObjectToAugmentedImage(call: MethodCall, result: MethodChannel.Result) {
        debugLog( "on attachObjectToAugmentedImage")
        val map = call.arguments as java.util.HashMap<*, *>
        val flutterArCoreNode = FlutterArCoreNode(map["node"] as java.util.HashMap<*, *>)
        val index = map["index"] as Int
        if (augmentedImageMap.containsKey(index)) {
            val anchorNode = augmentedImageMap[index]!!.second
            NodeFactory.makeNode(activity.applicationContext, flutterArCoreNode, debug) { node, throwable ->
                debugLog( "inserted ${node?.name}")
                val renderable = node?.renderable
                if (renderable != null) {
                    if (flutterArCoreNode.withShadows) {
                        renderable.isShadowCaster = true
                        renderable.isShadowReceiver = true
                    } else {
                        renderable.isShadowCaster = false
                        renderable.isShadowReceiver = false
                    }
                }
                if (node != null) {
                    node.setParent(anchorNode)
                    arSceneView.scene?.addChild(anchorNode)
                    result.success(null)
                } else if (throwable != null) {
                    result.error("attachObjectToAugmentedImage error", throwable.localizedMessage, null)
                }
            }
        } else {
            result.error("attachObjectToAugmentedImage error", "Augmented image there isn't ona hashmap", null)
        }
    }

    private fun onLoadSingleImageOnDb(call: MethodCall, result: MethodChannel.Result) {
        debugLog("on load_single_image_on_db")
        try {
            // clear augmentedImageMap
            augmentedImageMap.clear()
            val map = call.arguments as java.util.HashMap<*, *>
            val bytes = map["bytes"] as? ByteArray
            val imageWidth = map["imageWidth"] as? Float
            val session = arSceneView.session ?: return
            val config = Config(session)
            config.focusMode = Config.FocusMode.AUTO
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            bytes?.let {
                    if (!addImageToAugmentedImageDatabase(config, bytes, imageWidth)) {
                        throw Exception("Could not setup augmented image database")
                    }
            }
            session.configure(config)
            result.success(null)
        } catch (ex: Exception) {
            ex.localizedMessage?.let { debugLog(it) }
            result.error("load_single_image_on_db error", ex.localizedMessage, null)
        }
    }

    private fun addImageToAugmentedImageDatabase(config: Config, bytes: ByteArray, imageWidth : Float?): Boolean {
        debugLog( "addImageToAugmentedImageDatabase")
        try {
            val augmentedImageBitmap = loadAugmentedImageBitmap(bytes) ?: return false
            val augmentedImageDatabase = AugmentedImageDatabase(arSceneView.session)
            if (imageWidth == null) {
                augmentedImageDatabase.addImage("image_name", augmentedImageBitmap)
            } else {
                augmentedImageDatabase.addImage("image_name", augmentedImageBitmap, imageWidth)
            }
            config.augmentedImageDatabase = augmentedImageDatabase
            return true
        } catch (ex:Exception) {
            ex.localizedMessage?.let { debugLog(it) }
            return false
        }
    }

    private fun loadAugmentedImageBitmap(bitmapData: ByteArray): Bitmap? {
        debugLog( "loadAugmentedImageBitmap")
        return try {
            BitmapFactory.decodeByteArray(bitmapData, 0, bitmapData.size)
        } catch (e: Exception) {
            Log.e(tag, "IO exception loading augmented image bitmap.", e)
            null
        }
    }

    private fun onSingleTap(tap: MotionEvent?) {
        debugLog(" onSingleTap")
        val frame = arSceneView.arFrame
        if (frame != null) {
            if (tap != null && frame.camera.trackingState == TrackingState.TRACKING) {
                val hitList = frame.hitTest(tap)
                val list = ArrayList<HashMap<String, Any>>()
                for (hit in hitList) {
                    val trackable = hit.trackable
                    if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                        hit.hitPose
                        val distance: Float = hit.distance
                        val translation = hit.hitPose.translation
                        val rotation = hit.hitPose.rotationQuaternion
                        val flutterArCoreHitTestResult = FlutterArCoreHitTestResult(distance, translation, rotation)
                        val arguments = flutterArCoreHitTestResult.toHashMap()
                        list.add(arguments)
                    }
                }
                methodChannel.invokeMethod("onPlaneTap", list)
            }
        }
    }

    private fun onTakePicture(result: MethodChannel.Result) {
        try {
            debugLog("on takePicture")
            val image: Image? = arSceneView.arFrame?.acquireCameraImage()
            if (image == null) {
                result.error("takePicture error", "Image is null", null)
                return
            }

            val imageBitmap = imageToBitmap(image)
            val imageBytes = convertBitmapToByteArray(imageBitmap)
            result.success(imageBytes)
            image.close()
        } catch (e: Exception) {
            result.error("takePicture error", e.localizedMessage, null)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
        val imageBytes = out.toByteArray()

        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun convertBitmapToByteArray(bitmap: Bitmap): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        return outputStream.toByteArray()
    }

    private fun arSceneViewInit(call: MethodCall, result: MethodChannel.Result) {
        debugLog("arSceneViewInit")

        val enableTapRecognizer: Boolean? = call.argument("enableTapRecognizer")
        if (enableTapRecognizer != null && enableTapRecognizer) {
            arSceneView
                    .scene
                    ?.setOnTouchListener { hitTestResult: HitTestResult, event: MotionEvent ->
                        if (hitTestResult.node != null) {
                            debugLog(" onNodeTap " + hitTestResult.node?.name)
                            debugLog(hitTestResult.node?.localPosition.toString())
                            debugLog(hitTestResult.node?.worldPosition.toString())
                            methodChannel.invokeMethod("onNodeTap", hitTestResult.node?.name)
                            return@setOnTouchListener true
                        }
                        return@setOnTouchListener gestureDetector.onTouchEvent(event)
                    }
        }

        val enableUpdateListener: Boolean? = call.argument("enableUpdateListener")
        if (enableUpdateListener != null && enableUpdateListener) {
            arSceneView.scene?.addOnUpdateListener(sceneUpdateListener)
        }

        val enablePlaneRenderer: Boolean? = call.argument("enablePlaneRenderer")
        if (enablePlaneRenderer != null && !enablePlaneRenderer) {
            debugLog(" The plane renderer (enablePlaneRenderer) is set to $enablePlaneRenderer")
            arSceneView.planeRenderer.isVisible = false
        }

        result.success(null)
    }

    private fun addNodeWithAnchor(flutterArCoreNode: FlutterArCoreNode, result: MethodChannel.Result) {
        RenderableCustomFactory.makeRenderable(activity.applicationContext, flutterArCoreNode) { renderable, t ->
            if (renderable != null) {
                if (flutterArCoreNode.withShadows) {
                    renderable.isShadowCaster = true
                    renderable.isShadowReceiver = true
                } else {
                    renderable.isShadowCaster = false
                    renderable.isShadowReceiver = false
                }
            }
            if (t != null) {
                result.error("Make Renderable Error", t.localizedMessage, null)
                return@makeRenderable
            }
            val myAnchor = arSceneView.session?.createAnchor(Pose(flutterArCoreNode.getPosition(), flutterArCoreNode.getRotation()))
            if (myAnchor != null) {
                val anchorNode = AnchorNode(myAnchor)
                anchorNode.name = flutterArCoreNode.name
                anchorNode.renderable = renderable

                debugLog("addNodeWithAnchor inserted ${anchorNode.name}")
                attachNodeToParent(anchorNode, flutterArCoreNode.parentNodeName)

                for (node in flutterArCoreNode.children) {
                    node.parentNodeName = flutterArCoreNode.name
                    onAddNode(node, null)
                }
            }
            result.success(null)
        }
    }

    private fun onAddNode(flutterArCoreNode: FlutterArCoreNode, result: MethodChannel.Result?) {

        debugLog(flutterArCoreNode.toString())
        NodeFactory.makeNode(activity.applicationContext, flutterArCoreNode, debug) { node, _ ->
            debugLog("onAddNode inserted ${node?.name}")
            if (node != null) {
                attachNodeToParent(node, flutterArCoreNode.parentNodeName)
                for (n in flutterArCoreNode.children) {
                    n.parentNodeName = flutterArCoreNode.name
                    onAddNode(n, null)
                }
            }

        }
        result?.success(null)
    }

     private fun getCameraPosition(): List<Double>? {
        // Assuming you have an ArSceneView instance (arSceneView)
        val arFrame = arSceneView.arFrame ?: return null
        val camera = arFrame.camera
        val pose = camera.pose
        return listOf(pose.tx().toDouble(), pose.ty().toDouble(), pose.tz().toDouble())
    }

    private fun getNodePosition(name: String): List<Double>? {
        val node: Node? = arSceneView.scene?.findByName(name)
        val worldPosition = node?.worldPosition ?: return null

        return listOf(worldPosition.x.toDouble(), worldPosition.y.toDouble(), worldPosition.z.toDouble())
    }


    private fun attachNodeToParent(node: Node?, parentNodeName: String?) {
        if (parentNodeName != null) {
            debugLog("addNodeToSceneWithGeometry: PARENT_NODE_NAME $parentNodeName NODE ${node?.name}")
            val parentNode: Node? = arSceneView.scene?.findByName(parentNodeName)
            parentNode?.addChild(node)
        } else {
            debugLog("addNodeToSceneWithGeometry: NOT PARENT_NODE_NAME NODE ${node?.name}")
            arSceneView.scene?.addChild(node)
        }
    }

    private fun removeNode(name: String, result: MethodChannel.Result) {
        val node = arSceneView.scene?.findByName(name)
        if (node != null) {
            arSceneView.scene?.removeChild(node)
            debugLog("removed ${node.name}")
        }

        result.success(null)
    }

    private fun updateRotation(call: MethodCall, result: MethodChannel.Result) {
        val name = call.argument<String>("name")
        val node = arSceneView.scene?.findByName(name) as RotatingNode
        debugLog("rotating node:  $node")
        val degreesPerSecond = call.argument<Double?>("degreesPerSecond")
        debugLog("rotating value:  $degreesPerSecond")
        if (degreesPerSecond != null) {
            debugLog("rotating value:  ${node.degreesPerSecond}")
            node.degreesPerSecond = degreesPerSecond.toFloat()
        }
        result.success(null)
    }

    private fun updateMaterials(call: MethodCall, result: MethodChannel.Result) {
        val name = call.argument<String>("name")
        val materials = call.argument<ArrayList<HashMap<String, *>>>("materials")!!
        val node = arSceneView.scene?.findByName(name)
        val oldMaterial = node?.renderable?.material?.makeCopy()
        if (oldMaterial != null) {
            val material = MaterialCustomFactory.updateMaterial(oldMaterial, materials[0])
            node.renderable?.material = material
        }
        result.success(null)
    }

    override fun getView(): View {
        return arSceneView
    }

    override fun dispose() {
        onPause()
        onDestroy()
    }

    fun onResume() {
        debugLog("onResume()")

        // request camera permission if not already requested
        if (!ArCoreUtils.hasCameraPermission(activity)) {
            ArCoreUtils.requestCameraPermission(activity, permission)
        }

        if (arSceneView.session == null) {
            debugLog("session is null")
            try {
                val session = ArCoreUtils.createArSession(activity, mUserRequestedInstall, false)
                if (session == null) {
                    mUserRequestedInstall = false
                    return
                } else {
                    val config = Config(session)
                    config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    config.focusMode = Config.FocusMode.AUTO
                    session.configure(config)
                    arSceneView.setupSession(session)
                }
            } catch (ex: UnavailableUserDeclinedInstallationException) {
                ArCoreUtils.handleSessionException(activity, ex)
                return
            } catch (e: UnavailableException) {
                ArCoreUtils.handleSessionException(activity, e)
                return
            }
        }

        try {
            arSceneView.resume()
        } catch (ex: CameraNotAvailableException) {
            ArCoreUtils.displayError(activity, "Unable to get camera", ex)
            return
        }

        if (arSceneView.session != null) {
            debugLog("Searching for surfaces")
        }
    }

    fun onPause() {
        debugLog("onPause()")
        arSceneView.pause()
    }

    private fun onDestroy() {
        try {
            debugLog("Destroying arSceneView")
            activity.application.unregisterActivityLifecycleCallbacks(activityLifecycleCallbacks)
            arSceneView.scene.removeOnUpdateListener(sceneUpdateListener)
            arSceneView.session?.pause()
            arSceneView.session?.close()
            arSceneView.destroy()
            debugLog("arSceneView destroyed")
        } catch (e : Exception) {
            debugLog("Error destroying arSceneView" + e.localizedMessage)
            e.printStackTrace()
        }
    }
}

