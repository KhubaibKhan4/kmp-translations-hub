// translationshub-apple: on-device translation with Apple's Translation framework, for KMP Translations Hub.
//
//   translationshub-apple status --from en --to de
//     → {"status":"installed"|"supported"|"unsupported"}
//   translationshub-apple translate --from en --to de   (stdin: {"texts":["Hello", …]})
//     → {"texts":["Hallo", …]}   or   {"error":"…","code":"notInstalled"|"unsupported"|"failed"}
//
// Nothing leaves the Mac. Languages must be downloaded first (System Settings → General → Language & Region →
// Translation Languages): without a UI, the framework can't ask to download them.

import Foundation
import Translation

struct Input: Decodable { let texts: [String] }

func emit(_ object: [String: Any]) {
    let data = try! JSONSerialization.data(withJSONObject: object)
    FileHandle.standardOutput.write(data)
    FileHandle.standardOutput.write("\n".data(using: .utf8)!)
}

func argument(_ name: String) -> String? {
    let args = CommandLine.arguments
    guard let i = args.firstIndex(of: name), i + 1 < args.count else { return nil }
    return args[i + 1]
}

@main
struct Main {
    static func main() async {
        let command = CommandLine.arguments.dropFirst().first ?? ""
        guard let from = argument("--from"), let to = argument("--to") else {
            emit(["error": "Usage: translationshub-apple status|translate --from <tag> --to <tag>", "code": "failed"])
            exit(2)
        }
        let source = Locale.Language(identifier: from)
        let target = Locale.Language(identifier: to)
        let status = await LanguageAvailability().status(from: source, to: target)

        switch command {
        case "status":
            switch status {
            case .installed: emit(["status": "installed"])
            case .supported: emit(["status": "supported"])
            default: emit(["status": "unsupported"])
            }
        case "translate":
            guard status != .unsupported else {
                emit(["error": "Apple can't translate \(from) to \(to)", "code": "unsupported"])
                exit(1)
            }
            guard status == .installed else {
                emit(["error": "Download \(from) and \(to) in System Settings → General → Language & Region → Translation Languages", "code": "notInstalled"])
                exit(1)
            }
            let data = FileHandle.standardInput.readDataToEndOfFile()
            guard let input = try? JSONDecoder().decode(Input.self, from: data) else {
                emit(["error": "Expected {\"texts\": [...]} on stdin", "code": "failed"])
                exit(2)
            }
            do {
                let session = TranslationSession(installedSource: source, target: target)
                let requests = input.texts.enumerated().map { TranslationSession.Request(sourceText: $1, clientIdentifier: String($0)) }
                var results = input.texts
                for response in try await session.translations(from: requests) {
                    if let id = response.clientIdentifier, let index = Int(id) { results[index] = response.targetText }
                }
                emit(["texts": results])
            } catch {
                emit(["error": error.localizedDescription, "code": "failed"])
                exit(1)
            }
        default:
            emit(["error": "Unknown command \(command)", "code": "failed"])
            exit(2)
        }
    }
}
