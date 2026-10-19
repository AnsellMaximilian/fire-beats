package com.example.firelab

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

data class ReceiverUiState(
    val room: String? = null,
    val phoneUrl: String? = null,
    val status: String = "Creating pairing session…",
    val error: String? = null,
    val signalingConnected: Boolean = false,
    val signalingRetryAttempt: Int = 0,
    val poseChannelOpen: Boolean = false,
    val poseFrame: PoseFrame? = null,
    val poseFps: Float = 0f,
    val poseInferenceMs: Float = 0f,
    val poseDelegate: String = "--",
    val avatarDataUrl: String? = null,
    val selectedTrack: BeatTrack? = null,
    val remoteTrackReceived: Boolean = false,
    val firstFrameRendered: Boolean = false
)

data class PoseLandmark(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float
)

data class PoseFrame(
    val timestamp: Long,
    val width: Int,
    val height: Int,
    val mirrored: Boolean,
    val landmarks: List<PoseLandmark>
)

data class BeatTrack(
    val id: String,
    val name: String,
    val bpm: Float,
    val duration: Float,
    val beats: List<Float>,
    val audioUrl: String
)

class ReceiverController(
    context: Context,
    serverUrl: String
) {
    private val appContext = context.applicationContext
    private val serverUrl = serverUrl.trimEnd('/')
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val httpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val eglBase = EglBase.create()
    private val peerFactory: PeerConnectionFactory
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

    private val mutableState = MutableStateFlow(ReceiverUiState())
    val state: StateFlow<ReceiverUiState> = mutableState.asStateFlow()

    private var room: String? = null
    private var webSocket: WebSocket? = null
    private var peerConnection: PeerConnection? = null
    private var poseDataChannel: DataChannel? = null
    private var renderer: SurfaceViewRenderer? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var localCandidateCount = 0
    private var remoteCandidateCount = 0
    private var poseFrameCount = 0
    private var lastPoseFrameNanos = 0L
    private var smoothedPoseFps = 0f
    @Volatile
    private var closed = false
    private val signalingLock = Any()
    private var signalingGeneration = 0L
    private var reconnectAttempt = 0
    private var reconnectScheduled = false

    init {
        Log.i(TAG, "Receiver starting; server=$serverUrl")
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(appContext)
                .createInitializationOptions()
        )
        peerFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            )
            .setVideoDecoderFactory(
                DefaultVideoDecoderFactory(eglBase.eglBaseContext)
            )
            .createPeerConnectionFactory()
        Log.i(TAG, "WebRTC peer factory initialized")

        requestSession()
    }

    fun attachRenderer(view: SurfaceViewRenderer) {
        if (closed) {
            Log.w(TAG, "Ignoring renderer attachment after receiver closed")
            return
        }
        Log.i(TAG, "Initializing native video renderer")
        renderer = view
        view.init(eglBase.eglBaseContext, object : org.webrtc.RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {
                Log.i(TAG, "First remote video frame rendered")
                updateState {
                    copy(status = "Camera connected", firstFrameRendered = true, error = null)
                }
            }

            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                Log.i(TAG, "Remote frame resolution=${width}x$height rotation=$rotation")
            }
        })
        remoteVideoTrack?.let { track ->
            Log.i(TAG, "Attaching existing remote track ${track.id()} to renderer")
            track.addSink(view)
        }
    }

    fun detachRenderer(view: SurfaceViewRenderer) {
        if (renderer !== view) return
        Log.i(TAG, "Detaching native video renderer")
        remoteVideoTrack?.removeSink(view)
        renderer = null
        view.release()
    }

    private fun requestSession() {
        executor.execute {
            try {
                Log.i(TAG, "Requesting pairing session from $serverUrl/api/session")
                val connection = URL("$serverUrl/api/session").openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.requestMethod = "GET"
                val responseCode = connection.responseCode
                Log.i(TAG, "Pairing session HTTP response=$responseCode")
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                if (responseCode !in 200..299) {
                    error("Session request failed with HTTP $responseCode")
                }
                val session = JSONObject(body)
                room = session.getString("room")
                Log.i(TAG, "Pairing session created; room=$room phoneUrl=${session.getString("phoneUrl")}")
                updateState {
                    copy(
                        room = room,
                        phoneUrl = session.getString("phoneUrl"),
                        status = "Waiting for your phone",
                        error = null
                    )
                }
                connectSignaling()
            } catch (error: Exception) {
                fail("Could not create a pairing session", error)
                if (!closed) {
                    Log.i(TAG, "Retrying pairing session request in 2 seconds")
                    executor.schedule(::requestSession, 2, TimeUnit.SECONDS)
                }
            }
        }
    }

    private fun connectSignaling() {
        if (closed) return
        val signalUrl = when {
            serverUrl.startsWith("https://") -> serverUrl.replaceFirst("https://", "wss://")
            serverUrl.startsWith("http://") -> serverUrl.replaceFirst("http://", "ws://")
            else -> error("Server URL must start with http:// or https://")
        } + "/signal"

        val generation = synchronized(signalingLock) {
            if (closed) return
            reconnectScheduled = false
            signalingGeneration += 1
            signalingGeneration
        }
        Log.i(TAG, "Opening signaling WebSocket $signalUrl generation=$generation")
        val request = Request.Builder().url(signalUrl).build()
        val socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrentSignalingConnection(generation)) {
                    webSocket.cancel()
                    return
                }
                synchronized(signalingLock) {
                    reconnectAttempt = 0
                    reconnectScheduled = false
                }
                Log.i(TAG, "Signaling connected; HTTP ${response.code}")
                val join = JSONObject()
                    .put("type", "join")
                    .put("room", room)
                    .put("role", "tv")
                Log.i(TAG, "Sending TV join; room=$room sent=${webSocket.send(join.toString())}")
                updateState {
                    copy(
                        status = if (poseChannelOpen) "Pose live" else "Signaling connected — waiting for phone",
                        error = null,
                        signalingConnected = true,
                        signalingRetryAttempt = 0
                    )
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrentSignalingConnection(generation)) return
                try {
                    handleMessage(JSONObject(text))
                } catch (error: Exception) {
                    Log.e(TAG, "Invalid signaling message: ${text.take(160)}", error)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrentSignalingConnection(generation)) return
                Log.e(TAG, "Signaling failure; HTTP ${response?.code ?: "none"}", t)
                handleSignalingDisconnect(generation, t.message ?: "Connection reset")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrentSignalingConnection(generation)) return
                Log.i(TAG, "Signaling closed; code=$code reason=$reason")
                handleSignalingDisconnect(
                    generation,
                    reason.takeIf { it.isNotBlank() } ?: "Connection closed ($code)",
                )
            }
        })
        synchronized(signalingLock) {
            if (closed || generation != signalingGeneration || reconnectScheduled) {
                socket.cancel()
            } else {
                webSocket = socket
            }
        }
    }

    private fun isCurrentSignalingConnection(generation: Long): Boolean =
        synchronized(signalingLock) { !closed && generation == signalingGeneration }

    private fun handleSignalingDisconnect(generation: Long, reason: String) {
        synchronized(signalingLock) {
            if (closed || generation != signalingGeneration) return
            webSocket = null
        }
        scheduleSignalingReconnect(reason)
    }

    private fun scheduleSignalingReconnect(reason: String) {
        val attempt: Int
        val delaySeconds: Long
        val generation: Long
        synchronized(signalingLock) {
            if (closed || reconnectScheduled) return
            reconnectScheduled = true
            reconnectAttempt += 1
            attempt = reconnectAttempt
            delaySeconds = minOf(30L, 1L shl minOf(attempt - 1, 5))
            generation = signalingGeneration
        }
        Log.i(
            TAG,
            "Signaling disconnected: $reason; retry #$attempt in ${delaySeconds}s",
        )
        updateState {
            copy(
                status = "Connection lost — reconnecting in ${delaySeconds}s",
                error = "The server connection was interrupted. Retrying automatically.",
                signalingConnected = false,
                signalingRetryAttempt = attempt,
                firstFrameRendered = false
            )
        }
        executor.schedule({
            val shouldReconnect = synchronized(signalingLock) {
                if (closed || generation != signalingGeneration) {
                    false
                } else {
                    reconnectScheduled = false
                    true
                }
            }
            if (shouldReconnect) connectSignaling()
        }, delaySeconds, TimeUnit.SECONDS)
    }

    fun retrySignalingNow() {
        if (closed) return
        executor.execute {
            val previousSocket = synchronized(signalingLock) {
                signalingGeneration += 1
                reconnectScheduled = false
                reconnectAttempt = 0
                webSocket.also { webSocket = null }
            }
            Log.i(TAG, "Manual signaling retry requested")
            previousSocket?.cancel()
            releasePeerConnection()
            updateState {
                copy(
                    status = "Connecting to server…",
                    error = null,
                    signalingConnected = false,
                    signalingRetryAttempt = 0,
                    poseChannelOpen = false,
                    poseFrame = null
                )
            }
            connectSignaling()
        }
    }

    private fun handleMessage(message: JSONObject) {
        val type = message.optString("type")
        Log.d(TAG, "Signaling message received; type=$type")
        when (type) {
            "joined" -> {
                val peers = message.optJSONArray("peers")
                Log.i(
                    TAG,
                    "Joined signaling room=${message.optString("room")}; peers=$peers",
                )
                val phonePresent = peers?.let { values ->
                    (0 until values.length()).any { values.optString(it) == "phone" }
                } == true
                if (phonePresent && !mutableState.value.poseChannelOpen) {
                    updateState {
                        copy(status = "Phone connected — waiting for pose", error = null)
                    }
                }
            }
            "peer-joined" -> if (message.optString("role") == "phone") {
                Log.i(TAG, "Phone joined signaling room")
                updateState { copy(status = "Phone connected — waiting for camera", error = null) }
            }
            "peer-left" -> if (message.optString("role") == "phone") {
                Log.i(TAG, "Phone left signaling room")
                releasePeerConnection()
                updateState {
                    copy(
                        status = "Waiting for your phone",
                        poseChannelOpen = false,
                        poseFrame = null,
                        remoteTrackReceived = false,
                        firstFrameRendered = false,
                        error = null
                    )
                }
            }
            "signal" -> handleSignal(message.getJSONObject("data"))
            "track-selected" -> handleTrackSelected(message.getJSONObject("track"))
            "avatar-selected" -> {
                val dataUrl = message.optString("dataUrl").takeIf { it.isNotBlank() }
                Log.i(TAG, "Player avatar received; bytes=${dataUrl?.length ?: 0}")
                updateState { copy(avatarDataUrl = dataUrl, error = null) }
            }
            "error" -> {
                Log.e(TAG, "Signaling server error: ${message.optString("message")}")
                updateState {
                    copy(error = message.optString("message"), status = "Pairing error")
                }
            }
            else -> Log.w(TAG, "Ignoring unknown signaling message type=$type")
        }
    }

    private fun handleTrackSelected(value: JSONObject) {
        val beatsValue = value.getJSONArray("beats")
        val beats = buildList(beatsValue.length()) {
            for (index in 0 until beatsValue.length()) {
                add(beatsValue.optDouble(index).toFloat())
            }
        }
        val track = BeatTrack(
            id = value.getString("id"),
            name = value.optString("name", "Beat track"),
            bpm = value.optDouble("bpm", 0.0).toFloat(),
            duration = value.optDouble("duration", 0.0).toFloat(),
            beats = beats,
            audioUrl = "$serverUrl/api/tracks/${value.getString("id")}/audio"
        )
        Log.i(TAG, "Beat track selected; name=${track.name} bpm=${track.bpm} beats=${track.beats.size}")
        updateState { copy(selectedTrack = track, error = null) }
    }

    private fun handleSignal(data: JSONObject) {
        when {
            data.has("description") -> {
                val description = data.getJSONObject("description")
                if (description.optString("type") == "offer") {
                    val sdp = description.getString("sdp")
                    Log.i(TAG, "Received WebRTC offer; sdpLength=${sdp.length}")
                    acceptOffer(sdp)
                } else {
                    Log.w(TAG, "Ignoring WebRTC description type=${description.optString("type")}")
                }
            }
            data.has("candidate") -> {
                val value = data.getJSONObject("candidate")
                val candidate = IceCandidate(
                    value.optString("sdpMid").takeUnless { it == "null" },
                    value.optInt("sdpMLineIndex", 0),
                    value.getString("candidate")
                )
                remoteCandidateCount += 1
                val peer = peerConnection
                if (peer?.remoteDescription != null && peer.localDescription != null) {
                    Log.d(
                        TAG,
                        "Adding remote ICE candidate #$remoteCandidateCount; mid=${candidate.sdpMid} line=${candidate.sdpMLineIndex}",
                    )
                    Log.d(TAG, "Remote ICE candidate accepted=${peer.addIceCandidate(candidate)}")
                } else {
                    synchronized(pendingRemoteCandidates) {
                        pendingRemoteCandidates += candidate
                    }
                    Log.d(
                        TAG,
                        "Queued remote ICE candidate #$remoteCandidateCount; peerReady=${peer != null} queued=${pendingRemoteCandidates.size}",
                    )
                }
            }
            else -> Log.w(TAG, "Ignoring signal without description or candidate")
        }
    }

    private fun acceptOffer(sdp: String) {
        Log.i(TAG, "Creating PeerConnection for incoming offer")
        releasePeerConnection()
        localCandidateCount = 0
        remoteCandidateCount = 0
        val configuration = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )
        )
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        peerConnection = peerFactory.createPeerConnection(configuration, peerObserver)
        val peer = peerConnection ?: run {
            Log.e(TAG, "PeerConnection factory returned null")
            updateState {
                copy(status = "WebRTC initialization failed", error = "Could not create PeerConnection")
            }
            return
        }

        peer.setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() {
                Log.i(TAG, "Remote offer accepted; creating answer")
                peer.createAnswer(object : SdpAdapter() {
                    override fun onCreateSuccess(description: SessionDescription) {
                        Log.i(TAG, "WebRTC answer created; sdpLength=${description.description.length}")
                        peer.setLocalDescription(object : SdpAdapter() {
                            override fun onSetSuccess() {
                                Log.i(TAG, "Local answer applied; sending answer to phone")
                                sendDescription(description)
                                flushRemoteCandidates(peer)
                            }

                            override fun onSetFailure(message: String) =
                                failSdp("Could not set local answer", message)
                        }, description)
                    }

                    override fun onCreateFailure(message: String) =
                        failSdp("Could not create answer", message)
                }, MediaConstraints())
            }

            override fun onSetFailure(message: String) =
                failSdp("Could not accept phone offer", message)
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun flushRemoteCandidates(peer: PeerConnection) {
        val candidates = synchronized(pendingRemoteCandidates) {
            pendingRemoteCandidates.toList().also { pendingRemoteCandidates.clear() }
        }
        Log.i(TAG, "Flushing ${candidates.size} queued remote ICE candidates")
        candidates.forEach { candidate ->
            Log.d(TAG, "Queued remote ICE candidate accepted=${peer.addIceCandidate(candidate)}")
        }
    }

    private fun sendDescription(description: SessionDescription) {
        val data = JSONObject().put(
            "description",
            JSONObject()
                .put("type", description.type.canonicalForm())
                .put("sdp", description.description)
        )
        Log.d(TAG, "Sending WebRTC ${description.type.canonicalForm()} description")
        sendSignal(data)
    }

    private fun sendSignal(data: JSONObject) {
        val payload = JSONObject()
            .put("type", "signal")
            .put("data", data)
            .toString()
        val socket = webSocket
        if (socket == null) {
            Log.e(TAG, "Cannot send signal because WebSocket is null")
            return
        }
        Log.d(TAG, "Signal sent=${socket.send(payload)}")
    }

    private val peerObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) {
            Log.i(TAG, "WebRTC signaling state=$state")
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.i(TAG, "ICE connection state: $state")
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED ->
                    updateState { copy(status = "Receiving camera…", error = null) }
                PeerConnection.IceConnectionState.FAILED ->
                    updateState {
                        copy(status = "WebRTC connection failed", error = "ICE negotiation failed")
                    }
                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            Log.i(TAG, "ICE receiving=$receiving")
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            Log.i(TAG, "ICE gathering state=$state")
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            localCandidateCount += 1
            Log.d(
                TAG,
                "Local ICE candidate #$localCandidateCount; mid=${candidate.sdpMid} line=${candidate.sdpMLineIndex}",
            )
            val payload = JSONObject()
                .put("candidate", candidate.sdp)
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex)
            sendSignal(JSONObject().put("candidate", payload))
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) {
            Log.i(TAG, "Remote media stream added; videoTracks=${stream.videoTracks.size}")
            stream.videoTracks.firstOrNull()?.let(::attachRemoteTrack)
        }
        override fun onRemoveStream(stream: MediaStream) {
            Log.i(TAG, "Remote media stream removed")
        }
        override fun onDataChannel(channel: DataChannel) {
            Log.i(TAG, "Remote data channel received; label=${channel.label()} state=${channel.state()}")
            attachPoseDataChannel(channel)
        }
        override fun onRenegotiationNeeded() {
            Log.i(TAG, "WebRTC renegotiation requested")
        }

        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
            val track = receiver.track()
            Log.i(TAG, "Remote track added; kind=${track?.kind()} id=${track?.id()}")
            (track as? VideoTrack)?.let(::attachRemoteTrack)
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            Log.i(TAG, "Remote transceiver track received; kind=${track?.kind()} id=${track?.id()}")
            (track as? VideoTrack)?.let(::attachRemoteTrack)
        }
    }

    private fun attachRemoteTrack(track: VideoTrack) {
        if (remoteVideoTrack === track) {
            Log.d(TAG, "Remote video track ${track.id()} is already attached")
            return
        }
        Log.i(TAG, "Attaching remote video track; id=${track.id()} enabled=${track.enabled()}")
        renderer?.let { currentRenderer -> remoteVideoTrack?.removeSink(currentRenderer) }
        remoteVideoTrack = track
        track.setEnabled(true)
        updateState {
            copy(
                status = "Receiving camera…",
                remoteTrackReceived = true,
                error = null
            )
        }
        renderer?.let(track::addSink) ?: Log.i(TAG, "Renderer not created yet; track will attach when UI switches")
    }

    private fun attachPoseDataChannel(channel: DataChannel) {
        poseDataChannel?.unregisterObserver()
        poseDataChannel = channel
        poseFrameCount = 0
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                val channelState = channel.state()
                Log.i(TAG, "Pose data channel state=$channelState")
                when (channelState) {
                    DataChannel.State.OPEN -> updateState {
                        copy(
                            status = "Pose channel connected — waiting for landmarks",
                            poseChannelOpen = true,
                            error = null
                        )
                    }
                    DataChannel.State.CLOSING,
                    DataChannel.State.CLOSED -> updateState {
                        copy(
                            status = "Pose channel closed",
                            poseChannelOpen = false,
                            poseFrame = null,
                            poseFps = 0f,
                            poseInferenceMs = 0f,
                            poseDelegate = "--"
                        )
                    }
                    else -> Unit
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) {
                    Log.w(TAG, "Ignoring unexpected binary pose message")
                    return
                }
                try {
                    val data = buffer.data
                    val bytes = ByteArray(data.remaining())
                    data.get(bytes)
                    handlePoseMessage(JSONObject(String(bytes, StandardCharsets.UTF_8)))
                } catch (error: Exception) {
                    Log.e(TAG, "Could not parse pose data channel message", error)
                }
            }
        })
    }

    private fun handlePoseMessage(message: JSONObject) {
        if (message.optString("type") != "pose") {
            Log.w(TAG, "Ignoring data channel message type=${message.optString("type")}")
            return
        }

        val values = message.getJSONArray("landmarks")
        val landmarks = buildList(values.length()) {
            for (index in 0 until values.length()) {
                val point = values.getJSONArray(index)
                add(
                    PoseLandmark(
                        x = point.optDouble(0).toFloat(),
                        y = point.optDouble(1).toFloat(),
                        z = point.optDouble(2).toFloat(),
                        visibility = point.optDouble(3).toFloat()
                    )
                )
            }
        }
        val receivedAtNanos = System.nanoTime()
        if (lastPoseFrameNanos != 0L) {
            val elapsedNanos = receivedAtNanos - lastPoseFrameNanos
            if (elapsedNanos > 0L) {
                val instantaneousFps = (1_000_000_000.0 / elapsedNanos)
                    .toFloat()
                    .coerceAtMost(120f)
                smoothedPoseFps = if (smoothedPoseFps == 0f) {
                    instantaneousFps
                } else {
                    smoothedPoseFps * 0.85f + instantaneousFps * 0.15f
                }
            }
        }
        lastPoseFrameNanos = receivedAtNanos
        poseFrameCount += 1
        if (poseFrameCount == 1 || poseFrameCount % 50 == 0) {
            Log.i(TAG, "Pose frame #$poseFrameCount received; landmarks=${landmarks.size}")
        }
        updateState {
            copy(
                status = if (landmarks.isEmpty()) "Pose connected — step into frame" else "Pose live",
                poseChannelOpen = true,
                poseFps = smoothedPoseFps,
                poseInferenceMs = message.optDouble("inferenceMs", 0.0).toFloat(),
                poseDelegate = message.optString("delegate", "--"),
                poseFrame = PoseFrame(
                    timestamp = message.optLong("timestamp"),
                    width = message.optInt("width", 640).coerceAtLeast(1),
                    height = message.optInt("height", 360).coerceAtLeast(1),
                    mirrored = message.optBoolean("mirrored", true),
                    landmarks = landmarks
                ),
                error = null
            )
        }
    }

    private fun releasePeerConnection() {
        if (peerConnection != null || remoteVideoTrack != null) {
            Log.i(TAG, "Releasing PeerConnection and remote track")
        }
        renderer?.let { currentRenderer -> remoteVideoTrack?.removeSink(currentRenderer) }
        remoteVideoTrack = null
        poseDataChannel?.unregisterObserver()
        poseDataChannel?.close()
        poseDataChannel?.dispose()
        poseDataChannel = null
        lastPoseFrameNanos = 0L
        smoothedPoseFps = 0f
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        synchronized(pendingRemoteCandidates) { pendingRemoteCandidates.clear() }
    }

    private fun failSdp(prefix: String, message: String) {
        Log.e(TAG, "$prefix: $message")
        updateState { copy(status = prefix, error = message) }
    }

    private fun fail(prefix: String, error: Throwable) {
        Log.e(TAG, prefix, error)
        updateState {
            copy(status = prefix, error = error.message ?: error.javaClass.simpleName)
        }
    }

    private fun updateState(transform: ReceiverUiState.() -> ReceiverUiState) {
        mutableState.value = mutableState.value.transform()
    }

    fun close() {
        if (closed) return
        Log.i(TAG, "Closing receiver")
        closed = true
        synchronized(signalingLock) {
            signalingGeneration += 1
            reconnectScheduled = false
        }
        webSocket?.close(1000, "TV receiver closed")
        releasePeerConnection()
        val currentRenderer = renderer
        renderer = null
        currentRenderer?.release()
        peerFactory.dispose()
        eglBase.release()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        executor.shutdownNow()
    }

    private companion object {
        const val TAG = "FireTV"
    }
}

private open class SdpAdapter : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(message: String) = Unit
    override fun onSetFailure(message: String) = Unit
}
