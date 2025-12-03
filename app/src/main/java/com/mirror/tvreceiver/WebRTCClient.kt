package com.mirror.tvreceiver

import android.content.Context
import org.webrtc.*
import java.net.URI
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject

class WebRTCClient(
    private val context: Context,
    private val renderer: SurfaceViewRenderer,
    private val signalingUrl: "ws://192.168.0.41:8080",
    private val debug: (String) -> Unit,
    private val tvId: String = "tv-1",      // required by your server
    private val tvName: String = "AndroidTV" // optional name
) {

    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var eglBase: EglBase = EglBase.create()

    private var ws: WebSocketClient? = null

    fun init() {
        debug("WebRTC init…")

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext, true, true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        renderer.init(eglBase.eglBaseContext, null)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.setEnableHardwareScaler(true)

        connectToSignaling()
    }

    private fun connectToSignaling() {
        debug("Connecting WebSocket: $signalingUrl")

        ws = object : WebSocketClient(URI(signalingUrl)) {

            override fun onOpen(handshakedata: ServerHandshake?) {
                debug("WS connected")

                // REQUIRED by your signaling server
                val identify = JSONObject()
                identify.put("type", "identify")
                identify.put("from", "tv")

                val payload = JSONObject()
                payload.put("tvId", tvId)
                payload.put("name", tvName)

                identify.put("payload", payload)

                send(identify.toString())
                debug("Sent identify → tvId=$tvId")
            }

            override fun onMessage(message: String?) {
                if (message == null) return
                debug("WS received: $message")

                val json = JSONObject(message)
                when (json.optString("type")) {
                    "ping" -> handlePing(json)
                    "offer" -> handleOffer(json.getString("sdp"))
                    "candidate" -> handleRemoteIce(json)
                    "identified" -> debug("Server acknowledged TV identify.")
                    "peer-status" -> debug("Peer status: ${json.optString("status")}")
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                debug("WS closed: $reason")
            }

            override fun onError(ex: Exception?) {
                debug("WS error: ${ex?.message}")
            }
        }

        ws?.connect()
    }

    private fun handlePing(json: JSONObject) {
        // respond for heartbeat
        val pong = JSONObject()
        pong.put("type", "pong")
        ws?.send(pong.toString())
    }

    private fun handleOffer(sdp: String) {
        debug("Received OFFER")

        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = peerConnectionFactory.createPeerConnection(
            config,
            object : PeerConnection.Observer {

                override fun onTrack(transceiver: RtpTransceiver?) {
                    val track = transceiver?.receiver?.track()
                    if (track is VideoTrack) {
                        debug("Remote video track received")
                        track.addSink(renderer)
                    }
                }

                override fun onIceCandidate(candidate: IceCandidate?) {
                    if (candidate != null) {
                        val msg = JSONObject()
                        msg.put("type", "candidate")
                        msg.put("sdpMid", candidate.sdpMid)
                        msg.put("sdpMLineIndex", candidate.sdpMLineIndex)
                        msg.put("candidate", candidate.sdp)
                        ws?.send(msg.toString())
                    }
                }

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                    debug("ICE state = $newState")
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    debug("PC state = $newState")
                }

                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(dc: DataChannel?) {}
                override fun onStandardizedIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
                override fun onRenegotiationNeeded() {}
            }
        )

        val offerDesc = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                debug("Remote offer set. Creating answer…")
                createAnswer()
            }
        }, offerDesc)
    }

    private fun createAnswer() {
        peerConnection?.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return

                peerConnection?.setLocalDescription(object : SdpObserverAdapter() {}, desc)

                val msg = JSONObject()
                msg.put("type", "answer")
                msg.put("sdp", desc.description)

                ws?.send(msg.toString())
                debug("Answer sent")
            }
        }, MediaConstraints())
    }

    private fun handleRemoteIce(obj: JSONObject) {
        val candidate = IceCandidate(
            obj.getString("sdpMid"),
            obj.getInt("sdpMLineIndex"),
            obj.getString("candidate")
        )
        peerConnection?.addIceCandidate(candidate)
    }

    fun release() {
        debug("Releasing WebRTC")
        renderer.release()
        peerConnection?.close()
        ws?.close()
        eglBase.release()
    }
}

abstract class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}
