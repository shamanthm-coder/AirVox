import Foundation
import Shared

/// Reads the song already playing on the iPhone, the same card Android fills from the current player.
/// Airvox does not sign in to that music app.
final class AirvoxPlayback: NSObject, IosSystemPlayback {
    private var listener: IosNowPlayingListener?
    private var timer: Timer?
    private var watching = false

    func start(listener: IosNowPlayingListener) {
        self.listener = listener
        registerNotifications?(DispatchQueue.main)
        watchForChanges()
        fetch()
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.fetch()
        }
    }

    func refresh() {
        fetch()
    }

    func play() {
        send(0)
        shownPlaying = true
        emit()
    }

    func pause() {
        send(1)
        shownPlaying = false
        emit()
    }

    func next() {
        send(4)
    }

    func previous() {
        send(5)
    }

    private func send(_ command: UInt32) {
        guard let sendCommand else { return }
        _ = sendCommand(command, [:])
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
            self?.fetch()
        }
    }

    private func watchForChanges() {
        if watching { return }
        watching = true
        let center = CFNotificationCenterGetDarwinNotifyCenter()
        let observer = Unmanaged.passUnretained(self).toOpaque()
        let callback: CFNotificationCallback = { _, raw, _, _, _ in
            guard let raw else { return }
            let playback = Unmanaged<AirvoxPlayback>.fromOpaque(raw).takeUnretainedValue()
            playback.fetch()
        }
        CFNotificationCenterAddObserver(
            center,
            observer,
            callback,
            "kMRMediaRemoteNowPlayingInfoDidChangeNotification" as CFString,
            nil,
            .deliverImmediately
        )
    }

    private var shownTitle = ""
    private var shownArtist = ""
    private var shownAppName = ""
    private var shownPlaying = false

    private func fetch() {
        guard let getNowPlayingInfo else {
            publish(title: "", artist: "", appName: "", playing: false)
            return
        }
        getNowPlayingInfo(DispatchQueue.main) { [weak self] info in
            let parsed = Self.track(info)
            DispatchQueue.main.async {
                guard let self else { return }
                self.shownTitle = parsed.title
                self.shownArtist = parsed.artist
                self.shownPlaying = parsed.playing
                if !parsed.appName.isEmpty {
                    self.shownAppName = parsed.appName
                } else if parsed.title.isEmpty {
                    self.shownAppName = ""
                }
                self.emit()
                self.refreshPlaying()
                self.refreshAppName()
            }
        }
    }

    /// Title and artist are published on their own. The app-name lookup must not hold them back.
    private func refreshAppName() {
        guard let getAppName else { return }
        let title = shownTitle
        getAppName(0, DispatchQueue.main) { [weak self] name in
            let text = name.map { $0 as String } ?? ""
            DispatchQueue.main.async {
                guard let self, self.shownTitle == title, !text.isEmpty else { return }
                self.shownAppName = text
                self.emit()
            }
        }
    }

    private func refreshPlaying() {
        guard let getIsPlaying else { return }
        let title = shownTitle
        getIsPlaying(DispatchQueue.main) { [weak self] playing in
            let isPlaying = playing.boolValue
            DispatchQueue.main.async {
                guard let self, self.shownTitle == title else { return }
                self.shownPlaying = isPlaying
                self.emit()
            }
        }
    }

    private func publish(title: String, artist: String, appName: String, playing: Bool) {
        shownTitle = title
        shownArtist = artist
        shownAppName = appName
        shownPlaying = playing
        emit()
    }

    private func emit() {
        listener?.onNowPlaying(
            title: shownTitle,
            artist: shownArtist,
            appName: shownAppName,
            playing: shownPlaying
        )
    }

    private static func track(_ info: CFDictionary?) -> (title: String, artist: String, appName: String, playing: Bool) {
        guard let info else { return ("", "", "", false) }
        let dict = info as NSDictionary
        let title = text(dict, MediaKey.title)
        let artist = text(dict, MediaKey.artist)
        let album = text(dict, MediaKey.album)
        let playing = number(dict, MediaKey.rate) > 0.01
        var credit = artist
        if !album.isEmpty, album != artist {
            credit = credit.isEmpty ? album : "\(credit) · \(album)"
        }
        return (title, credit, appName(dict), playing)
    }

    /// A full Swift-dictionary cast drops the song when artwork or other private values are present.
    private static func text(_ dict: NSDictionary, _ key: String, depth: Int = 0) -> String {
        if let direct = string(dict.object(forKey: key)), !direct.isEmpty { return direct }
        if depth >= 3 { return "" }
        for value in dict.allValues {
            guard let child = value as? NSDictionary else { continue }
            let nested = text(child, key, depth: depth + 1)
            if !nested.isEmpty { return nested }
        }
        return ""
    }

    private static func string(_ value: Any?) -> String? {
        if let text = value as? String { return text }
        if let text = value as? NSString { return text as String }
        return nil
    }

    private static func number(_ dict: NSDictionary, _ key: String) -> Double {
        let value = dict.object(forKey: key)
        if let number = value as? NSNumber { return number.doubleValue }
        return 0
    }

    /// Display name is packed in the now-playing client blob. Bundle ids are not a song credit.
    private static func appName(_ dict: NSDictionary) -> String {
        let raw = dict.object(forKey: MediaKey.client)
        let data = (raw as? Data) ?? (raw as? NSData).map { $0 as Data }
        guard let data else { return "" }
        var best = ""
        for candidate in readableStrings(in: data) {
            let text = candidate.trimmingCharacters(in: .whitespacesAndNewlines)
            guard text.count >= 2, text.count <= 40 else { continue }
            guard !text.contains("."), !text.contains("/"), !text.contains("_") else { continue }
            let letters = text.unicodeScalars.filter { CharacterSet.letters.contains($0) }
            guard letters.count >= 2 else { continue }
            if text.count > best.count { best = text }
        }
        return best
    }

    private static func readableStrings(in data: Data) -> [String] {
        var found: [String] = []
        var bytes: [UInt8] = []
        func flush() {
            if bytes.count >= 2, let text = String(bytes: bytes, encoding: .utf8) {
                found.append(text)
            }
            bytes.removeAll(keepingCapacity: true)
        }
        for byte in data {
            if byte >= 32, byte < 127 {
                bytes.append(byte)
            } else {
                flush()
            }
        }
        flush()
        return found
    }
}

private let mediaRemote = dlopen(
    "/System/Library/PrivateFrameworks/MediaRemote.framework/MediaRemote",
    RTLD_NOW
)

private enum MediaKey {
    static let title = "kMRMediaRemoteNowPlayingInfoTitle"
    static let artist = "kMRMediaRemoteNowPlayingInfoArtist"
    static let album = "kMRMediaRemoteNowPlayingInfoAlbum"
    static let rate = "kMRMediaRemoteNowPlayingInfoPlaybackRate"
    static let client = "kMRMediaRemoteNowPlayingInfoClientPropertiesData"
}

private typealias NowPlayingBlock = @convention(block) (CFDictionary?) -> Void
private typealias AppNameBlock = @convention(block) (CFString?) -> Void
private typealias IsPlayingBlock = @convention(block) (ObjCBool) -> Void

private typealias GetNowPlayingInfo = @convention(c) (
    DispatchQueue,
    @escaping NowPlayingBlock
) -> Void

private typealias GetAppName = @convention(c) (
    Int32,
    DispatchQueue,
    @escaping AppNameBlock
) -> Void

private typealias GetIsPlaying = @convention(c) (
    DispatchQueue,
    @escaping IsPlayingBlock
) -> Void

private typealias RegisterNotifications = @convention(c) (DispatchQueue) -> Void
private typealias SendCommand = @convention(c) (UInt32, NSDictionary?) -> Bool

private func symbol<T>(_ name: String, as type: T.Type) -> T? {
    guard let mediaRemote, let raw = dlsym(mediaRemote, name) else { return nil }
    return unsafeBitCast(raw, to: type)
}

private let getNowPlayingInfo: GetNowPlayingInfo? = symbol("MRMediaRemoteGetNowPlayingInfo", as: GetNowPlayingInfo.self)
private let getAppName: GetAppName? = symbol(
    "MRMediaRemoteGetNowPlayingApplicationDisplayName",
    as: GetAppName.self
)
private let getIsPlaying: GetIsPlaying? = symbol(
    "MRMediaRemoteGetNowPlayingApplicationIsPlaying",
    as: GetIsPlaying.self
)
private let registerNotifications: RegisterNotifications? = symbol(
    "MRMediaRemoteRegisterForNowPlayingNotifications",
    as: RegisterNotifications.self
)
private let sendCommand: SendCommand? = symbol("MRMediaRemoteSendCommand", as: SendCommand.self)
