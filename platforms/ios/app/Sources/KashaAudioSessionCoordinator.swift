import AVFAudio
import Foundation

/// Тонкий нативный владелец AVAudioSession.
/// Recorder/player и продуктовый state остаются в общем Kotlin-слое.
final class KashaAudioSessionCoordinator {
    static let shared = KashaAudioSessionCoordinator()

    private var observers: [NSObjectProtocol] = []

    private init() {
        let center = NotificationCenter.default
        observers = [
            center.addObserver(
                forName: Notification.Name("KashaAudioSessionActivateRecording"),
                object: nil,
                queue: nil
            ) { [weak self] _ in
                self?.activateRecording()
            },
            center.addObserver(
                forName: Notification.Name("KashaAudioSessionActivatePlayback"),
                object: nil,
                queue: nil
            ) { [weak self] _ in
                self?.activatePlayback()
            },
            center.addObserver(
                forName: Notification.Name("KashaAudioSessionDeactivate"),
                object: nil,
                queue: nil
            ) { [weak self] _ in
                self?.deactivate()
            },
        ]
    }

    deinit {
        observers.forEach { observer in
            NotificationCenter.default.removeObserver(observer)
        }
    }

    private func activateRecording() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(
                .playAndRecord,
                mode: .default,
                options: [.allowBluetooth, .allowBluetoothA2DP, .defaultToSpeaker, .allowAirPlay]
            )
            try session.setActive(true)
        } catch {
            // Фактический recorder.start() ниже по стеку всё равно подтвердит или отклонит старт.
        }
    }

    private func activatePlayback() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .spokenAudio)
            try session.setActive(true)
        } catch {
            // Фактический AVAudioPlayer.play() подтвердит или отклонит playback.
        }
    }

    private func deactivate() {
        do {
            try AVAudioSession.sharedInstance().setActive(
                false,
                options: [.notifyOthersOnDeactivation]
            )
        } catch {
            // Best effort: деактивация не должна уничтожать уже сохранённое аудио.
        }
    }
}
