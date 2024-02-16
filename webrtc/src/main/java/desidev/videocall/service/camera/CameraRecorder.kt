package desidev.videocall.service.camera

import desidev.videocall.service.CameraFace

interface CameraRecorder {

    fun startRecording()
    fun stopRecording()
}