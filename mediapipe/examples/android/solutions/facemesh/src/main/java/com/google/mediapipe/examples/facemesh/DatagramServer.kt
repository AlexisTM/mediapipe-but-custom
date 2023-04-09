package com.google.mediapipe.examples.facemesh

import androidx.core.math.MathUtils
import com.google.mediapipe.formats.proto.LandmarkProto
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList
import com.google.mediapipe.solutions.facemesh.FaceMeshResult
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

fun toQuaternion(
    roll: Float,
    pitch: Float,
    yaw: Float
): FloatArray // [x, y, z, w]
{
    // Abbreviations for the various angular functions
    val cr: Float = cos(roll * 0.5f)
    val sr: Float = sin(roll * 0.5f)
    val cp: Float = cos(pitch * 0.5f)
    val sp: Float = sin(pitch * 0.5f)
    val cy: Float = cos(yaw * 0.5f)
    val sy: Float = sin(yaw * 0.5f)
    val w = cr * cp * cy + sr * sp * sy
    val x = sr * cp * cy - cr * sp * sy
    val y = cr * sp * cy + sr * cp * sy
    val z = cr * cp * sy - sr * sp * cy
    return floatArrayOf(x, y, z, w)
}

object DatagramServer {
    private val socket: DatagramSocket = DatagramSocket()
    private var queue: BlockingQueue<NormalizedLandmarkList> = LinkedBlockingQueue()

    init {
        thread(start = true, isDaemon = false, name = "datagramSender") {
            try {
                while (true) {
                    val lm = queue.take()
                    val json = JSONObject()
                    calculateMouth(lm, json)
                    calculateEyeLandmarks(lm, json)

                    val livelink_format = JSONObject()
                    val livelink_params = JSONArray()
                    for (key in json.keys()) {
                        val param = JSONObject()
                        param.put("Name", key)
                        param.put("Value", json.get(key))
                        livelink_params.put(param)
                    }
                    livelink_format.put("Parameter", livelink_params)

                    val livelink_bone = JSONArray()
                    val livelink_headbone = JSONObject()
                    // val real_rotation = euler?.withX(-euler.x)?.withZ(-euler.z)?.withY(-euler.y)?.let { toQuaternion(.fromEuler(it) }
                    livelink_headbone.put("Name","Head")
                    livelink_headbone.put("Parent",-1)
                    livelink_headbone.put("Location",JSONArray(listOf(0,0,0)))
                    // livelink_headbone.put("Rotation",JSONArray(listOf(real_rotation?.xyzw?.x,real_rotation?.xyzw?.y!!,real_rotation?.xyzw?.z!!,real_rotation?.xyzw?.w)))
                    livelink_headbone.put("Rotation",JSONArray(listOf(0, 0, 0, 1)))
                    livelink_headbone.put("Scale",JSONArray(listOf(0,0,0)))
                    livelink_bone.put(livelink_headbone)
                    livelink_format.put("Bone", livelink_bone)

                    val livelink_wrap = JSONObject()
                    livelink_wrap.put("android", livelink_format)

                    val result = livelink_wrap.toString(0)
                    val buf = result.toByteArray()
                    val packet = DatagramPacket(buf, buf.size, InetAddress.getByName("192.168.178.50"), 54321)
                    socket.send(packet)
                }
            } catch (ex: InterruptedException) {

            }
        }
    }

    fun send(result: FaceMeshResult) {
        val data = result.multiFaceLandmarks()
        if (data.isNotEmpty()) {

            queue.put(data[0])
        }
    }

    private fun landmarkToBlenshapes(result: FaceMeshResult) {
        if (result.multiFaceLandmarks().isEmpty()) {
            return
        }
        val face = result.multiFaceLandmarks()[0]
    }
}

// Converts the data from range [min; max] to [0; 1]
fun normalize(data: Float, min: Float, max: Float): Float {
    return (MathUtils.clamp(data, min, max) - min) / (max - min)
}

private fun normalizeBlendshape(config: BlendShapeConfig, data: Float): Float {
    return normalize(data, config.min, config.max)
}

private fun distance2d(lm1: LandmarkProto.NormalizedLandmark, lm2: LandmarkProto.NormalizedLandmark): Float {
    return sqrt((lm1.x - lm2.x).pow(2) + (lm1.y - lm2.y).pow(2))
}

private fun distance3d(lm1: LandmarkProto.NormalizedLandmark, lm2: LandmarkProto.NormalizedLandmark): Float {
    return sqrt((lm1.x - lm2.x).pow(2) + (lm1.y - lm2.y).pow(2) + (lm1.z - lm2.z).pow(2))
}

private fun add(
    lm1: LandmarkProto.NormalizedLandmark,
    lm2: LandmarkProto.NormalizedLandmark
): LandmarkProto.NormalizedLandmark {
    return LandmarkProto.NormalizedLandmark.newBuilder().setX(lm1.x + lm2.x).setY(lm1.y + lm2.y)
        .setZ(lm1.z + lm2.z).build()
}

private fun sub(
    lm1: LandmarkProto.NormalizedLandmark,
    lm2: LandmarkProto.NormalizedLandmark
): LandmarkProto.NormalizedLandmark {
    return LandmarkProto.NormalizedLandmark.newBuilder().setX(lm1.x - lm2.x).setY(lm1.y - lm2.y)
        .setZ(lm1.z - lm2.z).build()
}

private fun div(
    lm1: LandmarkProto.NormalizedLandmark,
    by: Float
): LandmarkProto.NormalizedLandmark {
    return LandmarkProto.NormalizedLandmark.newBuilder().setX(lm1.x / by).setY(lm1.y / by).setZ(lm1.z / by).build()
}

// Converted from https://github.com/JimWest/MeFaMo/blob/main/mefamo/blendshapes/blendshape_calculator.py
fun calculateMouth(lm: NormalizedLandmarkList, blendshapes: JSONObject)  {
    val upper_lip = lm.getLandmark(LandmarkIndices.upperLip[0])
    val upper_outer_lip = lm.getLandmark(LandmarkIndices.upperOuterLip[0])
    val lower_lip = lm.getLandmark(LandmarkIndices.lowerLip[0])

    val mouth_corner_left = lm.getLandmark(LandmarkIndices.mouthCornerLeft[0])
    val mouth_corner_right = lm.getLandmark(LandmarkIndices.mouthCornerRight[0])
    val lowest_chin = lm.getLandmark(LandmarkIndices.lowestChin[0])
    val nose_tip = lm.getLandmark(LandmarkIndices.noseTip[0])
    val upper_head = lm.getLandmark(LandmarkIndices.upperHead[0])

    val mouth_width = distance2d(mouth_corner_left, mouth_corner_right)
    val mouth_center = div(add(upper_lip, lower_lip), 2f)
    val mouth_open_dist = distance2d(upper_lip, lower_lip)
    val mouth_center_nose_dist = distance2d(mouth_center, nose_tip)

    val jaw_nose_dist = distance2d(lowest_chin, nose_tip)
    val head_height = distance2d(upper_head, lowest_chin)
    val jaw_open_ratio = jaw_nose_dist / head_height

    // # self._live_link_face.set_blendshape(ARKitFace.MouthFrownRight, max(min(mouth_frown_right, 1), 0))
    val jaw_open = normalizeBlendshape(
        BlendShapeConfigs.JawOpen, jaw_open_ratio)
    blendshapes.put(BlendShapeConfigs.JawOpen.name, jaw_open)

    val mouth_open = normalizeBlendshape(
        BlendShapeConfigs.MouthClose, mouth_center_nose_dist - mouth_open_dist)
    blendshapes.put(BlendShapeConfigs.MouthClose.name, mouth_open)

    // # TODO mouth open but teeth closed
    val smile_left = upper_lip.y - mouth_corner_left.y
    val smile_right = upper_lip.y - mouth_corner_right.y

    val mouth_smile_left = 1 - normalizeBlendshape(BlendShapeConfigs.MouthSmileLeft, smile_left)
    val mouth_smile_right = 1 - normalizeBlendshape(BlendShapeConfigs.MouthSmileRight, smile_right)

    blendshapes.put(BlendShapeConfigs.MouthSmileLeft.name, mouth_smile_left)
    blendshapes.put(BlendShapeConfigs.MouthSmileRight.name, mouth_smile_right)

    blendshapes.put(BlendShapeConfigs.MouthDimpleLeft.name, mouth_smile_left / 2)
    blendshapes.put(BlendShapeConfigs.MouthDimpleRight.name,  mouth_smile_right / 2)

    val mouth_frown_left = (mouth_corner_left.y - lm.getLandmark(LandmarkIndices.mouthFrownLeft[0]).y)
    val mouth_frown_right = (mouth_corner_right.y - lm.getLandmark(LandmarkIndices.mouthFrownRight[0]).y)

    blendshapes.put(BlendShapeConfigs.MouthFrownLeft.name, 1 - normalizeBlendshape(BlendShapeConfigs.MouthFrownLeft, mouth_frown_left))
    blendshapes.put(BlendShapeConfigs.MouthFrownRight.name, 1 - normalizeBlendshape(BlendShapeConfigs.MouthFrownRight, mouth_frown_right))

    // # todo: also strech when laughing, need to be fixed
    val mouth_left_stretch_point = lm.getLandmark(LandmarkIndices.mouthLeftStretch[0])
    val mouth_right_stretch_point = lm.getLandmark(LandmarkIndices.mouthRightStretch[0])

    // # only interested in the axis coordinates here
    val mouth_left_stretch = mouth_corner_left.x - mouth_left_stretch_point.x
    val mouth_right_stretch = mouth_right_stretch_point.x - mouth_corner_right.x
    val mouth_center_left_stretch = mouth_center.x - mouth_left_stretch_point.x
    val mouth_center_right_stretch = mouth_center.x - mouth_right_stretch_point.x

    val mouth_left = normalizeBlendshape(BlendShapeConfigs.MouthLeft, mouth_center_left_stretch)
    val mouth_right = 1 - normalizeBlendshape(BlendShapeConfigs.MouthRight, mouth_center_right_stretch)

    blendshapes.put(BlendShapeConfigs.MouthLeft.name, mouth_left)
    blendshapes.put(BlendShapeConfigs.MouthRight.name, mouth_right)
    // # self._live_link_face.set_blendshape(ARKitFace.MouthRight, 1 - remap(mouth_left_right, -1.5, 0.0))

    val stretch_normal_left = -0.7f + (0.42f * mouth_smile_left) + (0.36f * mouth_left)
    val stretch_max_left = -0.45f + (0.45f * mouth_smile_left) + (0.36f * mouth_left)

    val stretch_normal_right = -0.7f + 0.42f * mouth_smile_right + (0.36f * mouth_right)
    val stretch_max_right = -0.45f + (0.45f * mouth_smile_right) + (0.36f * mouth_right )

    blendshapes.put(BlendShapeConfigs.MouthStretchLeft.name, normalize(mouth_left_stretch, stretch_normal_left, stretch_max_left))
    blendshapes.put(BlendShapeConfigs.MouthStretchRight.name, normalize(mouth_right_stretch, stretch_normal_right, stretch_max_right))

    val uppest_lip = lm.getLandmark(LandmarkIndices.uppestLip[0])

    // # jaw only interesting on x yxis
    val jaw_right_left = nose_tip.x - lowest_chin.x

    // # TODO: this is not face rotation resistant
    blendshapes.put(BlendShapeConfigs.JawLeft.name, 1 - normalizeBlendshape(BlendShapeConfigs.JawLeft, jaw_right_left))
    blendshapes.put(BlendShapeConfigs.JawRight.name, normalizeBlendshape(BlendShapeConfigs.JawRight, jaw_right_left))

    val lowest_lip = lm.getLandmark(LandmarkIndices.lowestLip[0])
    val under_lip = lm.getLandmark(LandmarkIndices.underLip[0])

    val outer_lip_dist = distance2d(lower_lip, lowest_lip)
    val upper_lip_dist = distance2d(upper_lip, upper_outer_lip)

    val mouth_pucker = normalizeBlendshape(
        BlendShapeConfigs.MouthPucker, mouth_width)

    val mouthPuckerBlendshape = 1 - mouth_pucker
    blendshapes.put(BlendShapeConfigs.MouthPucker.name, mouthPuckerBlendshape)
    blendshapes.put(BlendShapeConfigs.MouthRollLower.name, 1 - normalizeBlendshape(BlendShapeConfigs.MouthRollLower, outer_lip_dist))
    blendshapes.put(BlendShapeConfigs.MouthRollUpper.name, 1 - normalizeBlendshape(BlendShapeConfigs.MouthRollUpper, upper_lip_dist))

    val upper_lip_nose_dist = nose_tip.y - uppest_lip.y
    blendshapes.put(BlendShapeConfigs.MouthShrugUpper.name, 1 - normalizeBlendshape(BlendShapeConfigs.MouthShrugUpper, upper_lip_nose_dist))

    val over_upper_lip = lm.getLandmark(LandmarkIndices.overUpperLip[0])
    val mouth_shrug_lower = distance2d(lowest_lip, over_upper_lip)

    blendshapes.put(BlendShapeConfigs.MouthShrugLower.name, 1 - normalizeBlendshape(BlendShapeConfigs.MouthShrugLower, mouth_shrug_lower))

    val lower_down_left = distance2d(lm.getLandmark(
        424), lm.getLandmark(319)) + mouth_open_dist * 0.5f
    val lower_down_right = distance2d(lm.getLandmark(
        204), lm.getLandmark(89)) + mouth_open_dist * 0.5f

    blendshapes.put(BlendShapeConfigs.MouthLowerDownLeft.name, 1 - normalizeBlendshape(BlendShapeConfigs.MouthLowerDownLeft, lower_down_left))
    blendshapes.put(BlendShapeConfigs.MouthLowerDownRight.name, 1 - normalizeBlendshape(BlendShapeConfigs.MouthLowerDownRight, lower_down_right))

    // # mouth funnel only can be seen if mouth pucker is really small
    if (mouthPuckerBlendshape < 0.5) {
        blendshapes.put(BlendShapeConfigs.MouthFunnel.name,  1 - normalizeBlendshape(BlendShapeConfigs.MouthFunnel, mouth_width))
    }
    else {
        blendshapes.put(BlendShapeConfigs.MouthFunnel.name, 0f)
    }

    val left_upper_press = distance2d(
        lm.getLandmark(LandmarkIndices.leftUpperPress[0]),
        lm.getLandmark(LandmarkIndices.leftUpperPress[1])
    )
    val left_lower_press = distance2d(
        lm.getLandmark(LandmarkIndices.leftLowerPress[0]),
        lm.getLandmark(LandmarkIndices.leftLowerPress[1])
    )
    val mouth_press_left = (left_upper_press + left_lower_press) / 2

    val right_upper_press = distance2d(
        lm.getLandmark(LandmarkIndices.rightUpperPress[0]),
        lm.getLandmark(LandmarkIndices.rightUpperPress[1])
    )
    val right_lower_press = distance2d(
        lm.getLandmark(LandmarkIndices.rightLowerPress[0]),
        lm.getLandmark(LandmarkIndices.rightLowerPress[1])
    )
    val mouth_press_right = (right_upper_press + right_lower_press) / 2

    blendshapes.put(BlendShapeConfigs.MouthPressLeft.name, 1 - normalizeBlendshape(BlendShapeConfigs.MouthPressLeft, mouth_press_left))
    blendshapes.put(BlendShapeConfigs.MouthPressRight.name,  1 - normalizeBlendshape(BlendShapeConfigs.MouthPressRight, mouth_press_right))
}

fun eyelidDistance(lm: NormalizedLandmarkList, eye_points: IntArray): Float {
    val eye_width = distance2d(lm.getLandmark(
        eye_points[0]), lm.getLandmark(eye_points[1]))
    val eye_outer_lid = distance2d(lm.getLandmark(
        eye_points[2]), lm.getLandmark(eye_points[5]))
    val eye_mid_lid = distance2d(lm.getLandmark(
        eye_points[3]), lm.getLandmark(eye_points[6]))
    val eye_inner_lid = distance2d(lm.getLandmark(
        eye_points[4]), lm.getLandmark(eye_points[7]))
    val eye_lid_avg = (eye_outer_lid + eye_mid_lid + eye_inner_lid) / 3
    val ratio = eye_lid_avg / eye_width
    return ratio
}

fun getEyeOpenRation(lm: NormalizedLandmarkList, points: IntArray): Float {
    val eye_distance = eyelidDistance(lm, points)
    val max_ratio = 0.285f
    val ratio = MathUtils.clamp(eye_distance / max_ratio, 0f, 2f)
    return ratio
}

// Converted from https://github.com/JimWest/MeFaMo/blob/main/mefamo/blendshapes/blendshape_calculator.py
fun calculateEyeLandmarks(lm: NormalizedLandmarkList, blendshapes: JSONObject) {
    // # Adapted from Kalidokit, https://github.com/yeemachine/kalidokit/blob/main/src/FaceSolver/calcEyes.ts
    val eye_open_ratio_left = getEyeOpenRation(lm, LandmarkIndices.eyeLeft)
    val eye_open_ratio_right = getEyeOpenRation(lm, LandmarkIndices.eyeRight)

    val blink_left = 1 - normalizeBlendshape(BlendShapeConfigs.EyeBlinkLeft, eye_open_ratio_left)
    val blink_right = 1 - normalizeBlendshape(BlendShapeConfigs.EyeBlinkRight, eye_open_ratio_right)

    blendshapes.put(BlendShapeConfigs.EyeBlinkLeft.name, blink_left) //, True)
    blendshapes.put(BlendShapeConfigs.EyeBlinkRight.name, blink_right) //, True)

    blendshapes.put(BlendShapeConfigs.EyeWideLeft.name, normalizeBlendshape(BlendShapeConfigs.EyeWideLeft, eye_open_ratio_left))
    blendshapes.put(BlendShapeConfigs.EyeWideRight.name, normalizeBlendshape(BlendShapeConfigs.EyeWideRight, eye_open_ratio_right))

    val squint_left = distance2d(
        lm.getLandmark(LandmarkIndices.squintLeft[0]),
        lm.getLandmark(LandmarkIndices.squintLeft[1])
    )
    blendshapes.put(
        BlendShapeConfigs.EyeSquintLeft.name, 1 - normalizeBlendshape(BlendShapeConfigs.EyeSquintLeft, squint_left))

    val squint_right = distance2d(
        lm.getLandmark(LandmarkIndices.squintRight[0]),
        lm.getLandmark(LandmarkIndices.squintRight[1])
    )
    blendshapes.put(
        BlendShapeConfigs.EyeSquintRight.name, 1 - normalizeBlendshape(BlendShapeConfigs.EyeSquintRight, squint_right))

    val right_brow_lower = div(add(add(lm.getLandmark(LandmarkIndices.rightBrowLower[0]),
                    lm.getLandmark(LandmarkIndices.rightBrowLower[1])),
                    lm.getLandmark(LandmarkIndices.rightBrowLower[2])),3f)
    val right_brow_dist = distance2d(lm.getLandmark(LandmarkIndices.rightBrow[0]), right_brow_lower)

    val left_brow_lower = div(add(add(lm.getLandmark(LandmarkIndices.leftBrowLower[0]),
                    lm.getLandmark(LandmarkIndices.leftBrowLower[1])),
                    lm.getLandmark(LandmarkIndices.leftBrowLower[2])), 3f)

    val left_brow_dist = distance2d(lm.getLandmark(LandmarkIndices.leftBrow[0]), left_brow_lower)

    blendshapes.put(
        BlendShapeConfigs.BrowDownLeft.name, 1 - normalizeBlendshape(BlendShapeConfigs.BrowDownLeft, left_brow_dist))
    blendshapes.put(BlendShapeConfigs.BrowOuterUpLeft.name, normalizeBlendshape(
        BlendShapeConfigs.BrowOuterUpLeft, left_brow_dist))

    blendshapes.put(
        BlendShapeConfigs.BrowDownRight.name, 1 - normalizeBlendshape(BlendShapeConfigs.BrowDownRight, right_brow_dist))
    blendshapes.put(BlendShapeConfigs.BrowOuterUpRight.name, normalizeBlendshape(
        BlendShapeConfigs.BrowOuterUpRight, right_brow_dist))

    val inner_brow = lm.getLandmark(LandmarkIndices.innerBrow[0])
    val upper_nose = lm.getLandmark(LandmarkIndices.upperNose[0])
    val inner_brow_dist = distance2d(upper_nose, inner_brow)

    blendshapes.put(BlendShapeConfigs.BrowInnerUp.name, normalizeBlendshape(
        BlendShapeConfigs.BrowInnerUp, inner_brow_dist))

    val cheek_squint_left = distance2d(
        lm.getLandmark(LandmarkIndices.cheekSquintLeft[0]),
        lm.getLandmark(LandmarkIndices.cheekSquintLeft[1])
    )

    val cheek_squint_right = distance2d(
        lm.getLandmark(LandmarkIndices.cheekSquintRight[0]),
        lm.getLandmark(LandmarkIndices.cheekSquintRight[1])
    )


    val cheek_squint_left_blendshape = 1 - normalizeBlendshape(BlendShapeConfigs.CheekSquintLeft, cheek_squint_left)
    val cheek_squint_right_blendshape = 1 - normalizeBlendshape(BlendShapeConfigs.CheekSquintRight, cheek_squint_right)
    blendshapes.put(
        BlendShapeConfigs.CheekSquintLeft.name, cheek_squint_left_blendshape)
    blendshapes.put(
        BlendShapeConfigs.CheekSquintRight.name, cheek_squint_right_blendshape)

    // # just use the same values for cheeksquint for nose sneer, mediapipe deosn't seem to have a separate value for nose sneer
    blendshapes.put(
        BlendShapeConfigs.NoseSneerLeft.name, cheek_squint_left_blendshape)
    blendshapes.put(
        BlendShapeConfigs.NoseSneerRight.name, cheek_squint_right_blendshape)
}