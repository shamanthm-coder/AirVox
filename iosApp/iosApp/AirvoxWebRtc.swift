import AVFoundation
import Foundation
import Shared
import WebRTC

/// WebRTC voice for one channel. Signaling stays in Kotlin so the offer, answer,
/// and ICE messages match the Android app. This class only carries the audio.
final class AirvoxWebRtc: NSObject, IosVoiceCall {
    private var listener: IosVoiceListener?
    private var factory: RTCPeerConnectionFactory?
    private var audioSource: RTCAudioSource?
    private var links: [String: Link] = [:]
    private var internet = false
    private var micEnabled = false
    private var epoch = 0
    private var routeWatching = false
    private var applyingRoute = false

    func attach(listener: IosVoiceListener) {
        self.listener = listener
    }

    func prepare(internet: Bool) {
        onMain {
            self.internet = internet
            _ = self.ensureFactory()
            self.configureAudio()
        }
    }

    func stop() {
        onMain {
            self.epoch += 1
            self.micEnabled = false
            let existing = Array(self.links.values)
            self.links.removeAll()
            for link in existing {
                link.connection.delegate = nil
                link.connection.close()
            }
            NotificationCenter.default.removeObserver(self)
            self.routeWatching = false
            self.deactivateAudio()
        }
    }

    func openPeer(peerId: String) {
        onMain {
            self.closePeerOnMain(peerId)
            guard let factory = self.factory ?? self.ensureFactory() else {
                self.fail("Could not open the voice channel.")
                return
            }
            let source = self.audioSource ?? factory.audioSource(with: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil))
            self.audioSource = source
            let constraints = RTCMediaConstraints(
                mandatoryConstraints: nil,
                optionalConstraints: ["DtlsSrtpKeyAgreement": kRTCMediaConstraintsValueTrue]
            )
            let box = PeerBox(owner: self, peerId: peerId)
            guard let connection = factory.peerConnection(with: self.rtcConfig(), constraints: constraints, delegate: box) else {
                self.fail("Could not open the voice channel.")
                return
            }
            let track = factory.audioTrack(with: source, trackId: "airvox-voice-\(peerId)")
            track.isEnabled = self.micEnabled
            connection.add(track, streamIds: ["airvox-voice"])
            self.links[peerId] = Link(epoch: self.epoch, connection: connection, localTrack: track, box: box)
        }
    }

    func closePeer(peerId: String) {
        onMain { self.closePeerOnMain(peerId) }
    }

    func createOffer(peerId: String) {
        onMain {
            guard let link = self.links[peerId] else { return }
            let ticket = link.epoch
            link.connection.offer(for: self.offerConstraints) { sdp, error in
                guard ticket == self.epoch, let sdp else {
                    if ticket == self.epoch {
                        self.fail(error?.localizedDescription ?? "Could not start the voice call.")
                    }
                    return
                }
                link.connection.setLocalDescription(sdp) { error in
                    guard ticket == self.epoch else { return }
                    if let error {
                        self.fail(error.localizedDescription)
                        return
                    }
                    self.emit { $0.onLocalOffer(peerId: peerId, sdp: sdp.sdp) }
                }
            }
        }
    }

    func acceptOffer(peerId: String, sdp: String) {
        onMain {
            guard let link = self.links[peerId], !sdp.isEmpty else { return }
            let ticket = link.epoch
            let offer = RTCSessionDescription(type: .offer, sdp: sdp)
            link.connection.setRemoteDescription(offer) { error in
                guard ticket == self.epoch else { return }
                if let error {
                    self.fail(error.localizedDescription)
                    return
                }
                link.remoteDescriptionSet = true
                self.flushIce(link)
                self.captureRemoteAudio(link)
                link.connection.answer(for: self.offerConstraints) { answer, error in
                    guard ticket == self.epoch, let answer else {
                        if ticket == self.epoch {
                            self.fail(error?.localizedDescription ?? "Could not answer the voice call.")
                        }
                        return
                    }
                    link.connection.setLocalDescription(answer) { error in
                        guard ticket == self.epoch else { return }
                        if let error {
                            self.fail(error.localizedDescription)
                            return
                        }
                        self.emit { $0.onLocalAnswer(peerId: peerId, sdp: answer.sdp) }
                    }
                }
            }
        }
    }

    func acceptAnswer(peerId: String, sdp: String) {
        onMain {
            guard let link = self.links[peerId], !sdp.isEmpty else { return }
            let ticket = link.epoch
            let answer = RTCSessionDescription(type: .answer, sdp: sdp)
            link.connection.setRemoteDescription(answer) { error in
                guard ticket == self.epoch else { return }
                if let error {
                    self.fail(error.localizedDescription)
                    return
                }
                link.remoteDescriptionSet = true
                self.flushIce(link)
                self.captureRemoteAudio(link)
            }
        }
    }

    func addIce(peerId: String, sdpMid: String, sdpMLineIndex: Int32, candidate: String) {
        onMain {
            guard let link = self.links[peerId], !candidate.isEmpty else { return }
            let ice = RTCIceCandidate(
                sdp: candidate,
                sdpMLineIndex: sdpMLineIndex,
                sdpMid: sdpMid.isEmpty ? nil : sdpMid
            )
            if link.remoteDescriptionSet {
                link.connection.add(ice) { _ in }
            } else {
                link.pending.append(ice)
            }
        }
    }

    func setMicEnabled(enabled: Bool) {
        onMain {
            self.micEnabled = enabled
            if enabled { self.applyOutputRoute() }
            for link in self.links.values {
                link.localTrack.isEnabled = enabled
            }
        }
    }

    func setRemoteTalking(peerId: String, talking: Bool) {
        onMain {
            guard let link = self.links[peerId] else { return }
            link.remoteTalking = talking
            link.remoteTrack?.isEnabled = talking
            if talking { self.applyOutputRoute() }
        }
    }

    fileprivate func didGenerateIce(_ candidate: RTCIceCandidate, peerId: String) {
        guard links[peerId]?.epoch == epoch else { return }
        emit {
            $0.onIceCandidate(
                peerId: peerId,
                sdpMid: candidate.sdpMid ?? "",
                sdpMLineIndex: candidate.sdpMLineIndex,
                candidate: candidate.sdp
            )
        }
    }

    fileprivate func didChangeIce(_ state: RTCIceConnectionState, peerId: String) {
        guard links[peerId]?.epoch == epoch else { return }
        switch state {
        case .connected, .completed:
            applyOutputRoute()
            emit { $0.onConnected(peerId: peerId) }
        case .failed:
            emit { $0.onUnreachable(peerId: peerId) }
        default:
            break
        }
    }

    fileprivate func didChangeConnection(_ state: RTCPeerConnectionState, peerId: String) {
        guard links[peerId]?.epoch == epoch else { return }
        switch state {
        case .connected:
            applyOutputRoute()
            emit { $0.onConnected(peerId: peerId) }
        case .failed:
            emit { $0.onDropped(peerId: peerId) }
        default:
            break
        }
    }

    fileprivate func didAddRemoteAudio(_ track: RTCAudioTrack, peerId: String) {
        guard let link = links[peerId], link.epoch == epoch else { return }
        link.remoteTrack = track
        track.isEnabled = link.remoteTalking
    }

    private func closePeerOnMain(_ peerId: String) {
        guard let link = links.removeValue(forKey: peerId) else { return }
        link.connection.delegate = nil
        link.connection.close()
    }

    private func ensureFactory() -> RTCPeerConnectionFactory? {
        if let factory { return factory }
        RTCInitializeSSL()
        let created = RTCPeerConnectionFactory(
            encoderFactory: RTCDefaultVideoEncoderFactory(),
            decoderFactory: RTCDefaultVideoDecoderFactory()
        )
        factory = created
        return created
    }

    private func rtcConfig() -> RTCConfiguration {
        let config = RTCConfiguration()
        config.iceServers = iceServers()
        config.sdpSemantics = .unifiedPlan
        config.continualGatheringPolicy = .gatherContinually
        if internet {
            config.bundlePolicy = .maxBundle
            config.rtcpMuxPolicy = .require
        }
        return config
    }

    private func iceServers() -> [RTCIceServer] {
        let stun = { (host: String) in RTCIceServer(urlStrings: ["stun:\(host):19302"]) }
        if !internet {
            // Hotspot and Bluetooth stay on the local Wi-Fi. A STUN server needs
            // internet and would leave the call waiting when there is none.
            return []
        }
        return [
            stun("stun.l.google.com"),
            stun("stun1.l.google.com"),
            stun("stun2.l.google.com"),
            RTCIceServer(
                urlStrings: [
                    "turn:global.relay.metered.ca:80",
                    "turn:global.relay.metered.ca:443",
                    "turns:global.relay.metered.ca:443?transport=tcp",
                ],
                username: "openrelayproject",
                credential: "openrelayproject"
            ),
        ]
    }

    private var offerConstraints: RTCMediaConstraints {
        RTCMediaConstraints(
            mandatoryConstraints: [kRTCMediaConstraintsOfferToReceiveAudio: kRTCMediaConstraintsValueTrue],
            optionalConstraints: nil
        )
    }

    private func flushIce(_ link: Link) {
        let waiting = link.pending
        link.pending.removeAll()
        for ice in waiting {
            link.connection.add(ice) { _ in }
        }
    }

    private func captureRemoteAudio(_ link: Link) {
        let track = link.connection.transceivers
            .compactMap { $0.receiver.track as? RTCAudioTrack }
            .first
        if let track {
            link.remoteTrack = track
            track.isEnabled = link.remoteTalking
        }
    }

    private func configureAudio() {
        let session = RTCAudioSession.sharedInstance()
        session.useManualAudio = true
        session.isAudioEnabled = true
        watchRoute()
        applyOutputRoute()
    }

    /// Bluetooth headset or speaker when one is connected. Otherwise the loudspeaker,
    /// the same one music uses, not the earpiece a phone call uses.
    private func applyOutputRoute() {
        if applyingRoute { return }
        applyingRoute = true
        defer { applyingRoute = false }
        let options = routeOptions()
        let preferred = RTCAudioSessionConfiguration.webRTC()
        preferred.category = AVAudioSession.Category.playAndRecord.rawValue
        preferred.categoryOptions = options
        preferred.mode = AVAudioSession.Mode.default.rawValue
        RTCAudioSessionConfiguration.setWebRTC(preferred)

        let session = RTCAudioSession.sharedInstance()
        session.lockForConfiguration()
        defer { session.unlockForConfiguration() }
        do {
            try session.setCategory(.playAndRecord, mode: .default, options: options)
            if let headset = bluetoothInput() {
                try session.setPreferredInput(headset)
                try session.overrideOutputAudioPort(.none)
            } else if externalOutput() {
                try session.overrideOutputAudioPort(.none)
            } else {
                try AVAudioSession.sharedInstance().setPreferredInput(nil)
                try session.overrideOutputAudioPort(.speaker)
            }
            try session.setActive(true)
            session.isAudioEnabled = true
        } catch {
            // A later route change applies the speaker or the headset again.
        }
    }

    private func routeOptions() -> AVAudioSession.CategoryOptions {
        var options: AVAudioSession.CategoryOptions = [.allowBluetoothHFP, .allowBluetoothA2DP]
        if !externalOutput() && bluetoothInput() == nil {
            options.insert(.defaultToSpeaker)
        }
        return options
    }

    private func bluetoothInput() -> AVAudioSessionPortDescription? {
        let inputs = AVAudioSession.sharedInstance().availableInputs ?? []
        return inputs.first { port in
            port.portType == .bluetoothHFP || port.portType == .bluetoothLE
        }
    }

    private func externalOutput() -> Bool {
        AVAudioSession.sharedInstance().currentRoute.outputs.contains { port in
            switch port.portType {
            case .bluetoothA2DP, .bluetoothHFP, .bluetoothLE, .headphones, .airPlay, .carAudio:
                return true
            default:
                return false
            }
        }
    }

    private func watchRoute() {
        if routeWatching { return }
        routeWatching = true
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(audioRouteChanged(_:)),
            name: AVAudioSession.routeChangeNotification,
            object: AVAudioSession.sharedInstance()
        )
    }

    @objc private func audioRouteChanged(_ notification: Notification) {
        let raw = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
        let reason = raw.flatMap { AVAudioSession.RouteChangeReason(rawValue: $0) }
        if reason == .override || reason == .categoryChange { return }
        DispatchQueue.main.async { self.applyOutputRoute() }
    }

    private func deactivateAudio() {
        let session = RTCAudioSession.sharedInstance()
        session.lockForConfiguration()
        defer { session.unlockForConfiguration() }
        try? session.overrideOutputAudioPort(.none)
        try? session.setActive(false)
    }

    private func fail(_ message: String) {
        emit { $0.onError(message: message) }
    }

    private func emit(_ block: @escaping (IosVoiceListener) -> Void) {
        let ticket = epoch
        let fire = {
            guard ticket == self.epoch, let listener = self.listener else { return }
            block(listener)
        }
        if Thread.isMainThread {
            fire()
        } else {
            DispatchQueue.main.async(execute: fire)
        }
    }

    private func onMain(_ block: @escaping () -> Void) {
        if Thread.isMainThread {
            block()
        } else {
            DispatchQueue.main.sync(execute: block)
        }
    }

    private final class Link {
        let epoch: Int
        let connection: RTCPeerConnection
        let localTrack: RTCAudioTrack
        let box: PeerBox
        var remoteTrack: RTCAudioTrack?
        var remoteDescriptionSet = false
        var remoteTalking = false
        var pending: [RTCIceCandidate] = []

        init(epoch: Int, connection: RTCPeerConnection, localTrack: RTCAudioTrack, box: PeerBox) {
            self.epoch = epoch
            self.connection = connection
            self.localTrack = localTrack
            self.box = box
        }
    }
}

private final class PeerBox: NSObject, RTCPeerConnectionDelegate {
    weak var owner: AirvoxWebRtc?
    let peerId: String

    init(owner: AirvoxWebRtc, peerId: String) {
        self.owner = owner
        self.peerId = peerId
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {
        if let track = stream.audioTracks.first {
            owner?.didAddRemoteAudio(track, peerId: peerId)
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}

    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        owner?.didChangeIce(newState, peerId: peerId)
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        owner?.didGenerateIce(candidate, peerId: peerId)
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        owner?.didChangeConnection(newState, peerId: peerId)
    }
}
