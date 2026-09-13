import AVFAudio
import Foundation
import UIKit

private enum KashaAudioBridgeEvent {
    static let activateRecording = Notification.Name("KashaAudioSessionActivateRecording")
    static let activatePlayback = Notification.Name("KashaAudioSessionActivatePlayback")
    static let deactivate = Notification.Name("KashaAudioSessionDeactivate")
    static let openSystemSettings = Notification.Name("KashaOpenSystemSettings")
    static let systemSettingsOpenFailed = Notification.Name("KashaSystemSettingsOpenFailed")

    static let interruptionBegan = Notification.Name("KashaAudioSessionInterruptionBegan")
    static let interruptionEnded = Notification.Name("KashaAudioSessionInterruptionEnded")
    static let routeChanged = Notification.Name("KashaAudioRouteChanged")
    static let applicationDidBecomeActive = Notification.Name("KashaApplicationDidBecomeActive")
}

/// Тонкий нативный владелец AVAudioSession и системных переходов iOS.
/// Recorder/player и продуктовый state остаются в общем Kotlin-слое.
final class KashaAudioSessionCoordinator {
    static let shared = KashaAudioSessionCoordinator()

    private var observers: [NSObjectProtocol] = []

    private init() {
        let center = NotificationCenter.default
        observers = [
            center.addObserver(
                forName: KashaAudioBridgeEvent.activateRecording,
                object: nil,
                queue: nil
            ) { [weak self] _ in
                self?.activateRecording()
            },
            center.addObserver(
                forName: KashaAudioBridgeEvent.activatePlayback,
                object: nil,
                queue: nil
            ) { [weak self] _ in
                self?.activatePlayback()
            },
            center.addObserver(
                forName: KashaAudioBridgeEvent.deactivate,
                object: nil,
                queue: nil
            ) { [weak self] _ in
                self?.deactivate()
            },
            center.addObserver(
                forName: KashaAudioBridgeEvent.openSystemSettings,
                object: nil,
                queue: .main
            ) { _ in
                Self.openSystemSettings()
            },
            center.addObserver(
                forName: AVAudioSession.interruptionNotification,
                object: nil,
                queue: nil
            ) { [weak self] notification in
                self?.handleInterruption(notification)
            },
            center.addObserver(
                forName: AVAudioSession.routeChangeNotification,
                object: nil,
                queue: nil
            ) { [weak self] notification in
                self?.handleRouteChange(notification)
            },
            center.addObserver(
                forName: UIApplication.didBecomeActiveNotification,
                object: nil,
                queue: nil
            ) { [weak self] _ in
                self?.handleApplicationDidBecomeActive()
            },
        ]
    }

    deinit {
        observers.forEach { observer in
            NotificationCenter.default.removeObserver(observer)
        }
    }

    private static func openSystemSettings() {
        guard
            let url = URL(string: UIApplication.openSettingsURLString),
            UIApplication.shared.canOpenURL(url)
        else {
            NotificationCenter.default.post(name: KashaAudioBridgeEvent.systemSettingsOpenFailed, object: nil)
            return
        }
        UIApplication.shared.open(url, options: [:]) { opened in
            if !opened {
                NotificationCenter.default.post(name: KashaAudioBridgeEvent.systemSettingsOpenFailed, object: nil)
            }
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

    private func handleInterruption(_ notification: Notification) {
        guard
            let userInfo = notification.userInfo,
            let typeValue = unsignedValue(userInfo[AVAudioSessionInterruptionTypeKey]),
            let type = AVAudioSession.InterruptionType(rawValue: typeValue)
        else {
            return
        }

        switch type {
        case .began:
            let wasSuspended = (userInfo[AVAudioSessionInterruptionWasSuspendedKey] as? NSNumber)?.boolValue ?? false
            postBridge(
                name: KashaAudioBridgeEvent.interruptionBegan,
                userInfo: ["wasSuspended": wasSuspended]
            )

        case .ended:
            let optionsValue = unsignedValue(userInfo[AVAudioSessionInterruptionOptionKey]) ?? 0
            let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
            let route = AVAudioSession.sharedInstance().currentRoute
            postBridge(
                name: KashaAudioBridgeEvent.interruptionEnded,
                userInfo: [
                    "canResume": options.contains(.shouldResume),
                    "inputAvailable": !route.inputs.isEmpty,
                    "currentHasExternalInput": route.inputs.contains(where: isExternalInput),
                ]
            )

        @unknown default:
            return
        }
    }

    private func handleRouteChange(_ notification: Notification) {
        guard
            let userInfo = notification.userInfo,
            let reasonValue = unsignedValue(userInfo[AVAudioSessionRouteChangeReasonKey]),
            let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue)
        else {
            return
        }

        let session = AVAudioSession.sharedInstance()
        let previousRoute = userInfo[AVAudioSessionRouteChangePreviousRouteKey] as? AVAudioSessionRouteDescription
        let previousHadExternalInput = previousRoute?.inputs.contains(where: isExternalInput) ?? false
        let currentHasExternalInput = session.currentRoute.inputs.contains(where: isExternalInput)

        postBridge(
            name: KashaAudioBridgeEvent.routeChanged,
            userInfo: [
                "reason": normalizedRouteReason(reason),
                "inputAvailable": !session.currentRoute.inputs.isEmpty,
                "previousHadExternalInput": previousHadExternalInput,
                "currentHasExternalInput": currentHasExternalInput,
            ]
        )
    }

    private func handleApplicationDidBecomeActive() {
        let route = AVAudioSession.sharedInstance().currentRoute
        postBridge(
            name: KashaAudioBridgeEvent.applicationDidBecomeActive,
            userInfo: [
                "inputAvailable": !route.inputs.isEmpty,
                "currentHasExternalInput": route.inputs.contains(where: isExternalInput),
            ]
        )
    }

    private func postBridge(name: Notification.Name, userInfo: [AnyHashable: Any]) {
        let deliver = {
            NotificationCenter.default.post(name: name, object: nil, userInfo: userInfo)
        }
        if Thread.isMainThread {
            deliver()
        } else {
            DispatchQueue.main.async(execute: deliver)
        }
    }

    private func unsignedValue(_ value: Any?) -> UInt? {
        if let number = value as? NSNumber {
            return number.uintValue
        }
        return value as? UInt
    }

    private func isExternalInput(_ port: AVAudioSessionPortDescription) -> Bool {
        port.portType != .builtInMic
    }

    private func normalizedRouteReason(_ reason: AVAudioSession.RouteChangeReason) -> String {
        switch reason {
        case .newDeviceAvailable:
            return "newDeviceAvailable"
        case .oldDeviceUnavailable:
            return "oldDeviceUnavailable"
        case .categoryChange:
            return "categoryChange"
        case .override:
            return "override"
        case .wakeFromSleep:
            return "wakeFromSleep"
        case .noSuitableRouteForCategory:
            return "noSuitableRouteForCategory"
        case .routeConfigurationChange:
            return "routeConfigurationChange"
        case .unknown:
            return "unknown"
        @unknown default:
            return "unknown"
        }
    }
}
