import Foundation

extension WorkspacesModel {
    /// The build card on a project's page (`buildCard` of the first-impressions page, section 6),
    /// drawn from the server's template answer: who started the build and when, the bar by the
    /// server's step of its steps, "Project saved" and the groups of steps found by name, and, ended
    /// short, why in the server's words with Build Again; or, when the build asked did not start,
    /// what was said instead.
    public struct BuildCard: Equatable, Sendable {
        public enum Stage: Equatable, Sendable {
            case building, failed
            /// The build asked was refused, or got no answer and the template read shows none.
            case unstarted
        }

        /// A group's mark: done (a check), running (the spinner), failed (a cross), or to come (a hollow dot).
        public enum Mark: Equatable, Sendable {
            case done, running, failed, coming
        }

        public struct Group: Equatable, Sendable {
            public let name: String
            public let mark: Mark
        }

        /// The groups a person waits through, each by the name of its first step: a step the app does
        /// not know joins the group before it, so a new phase never breaks the card.
        public static let groupsByFirstStep = [
            ("container_launched", "Container created"), ("packages_installed", "Tools installed"), ("repos_cloned", "Repositories cloned"),
            ("services_provisioned", "Agents installed"), ("complete", "Ready for workspaces"),
        ]
        /// The line the create call stands for, done once the project is listed.
        public static let saved = "Project saved"

        public let stage: Stage
        public let title: String
        /// "Started by you at 14:02"; nil when no build started.
        public let started: String?
        /// When it started and, once it ended, when: its elapsed time runs between them.
        public let startedAt: Date?
        public let endedAt: Date?
        /// How far the bar is filled: the server's step of its steps.
        public let done: Double
        /// "step 10 of 20", or "stopped at step 13 of 20".
        public let at: String?
        /// The last read got no answer: the card stays as read before, saying so quietly.
        public let missed: String?
        public let groups: [Group]
        /// Why the build failed and what to do, a line each, in the server's words.
        public let reason: [String]
        /// Build Again is offered: the person's `whoami` names `build`.
        public let buildsAgain: Bool
        /// Build Again was pressed and is not answered yet.
        public let asking: Bool
        /// The build asked was refused or not answered: its sentence, then what to do.
        public let refusal: [String]
        /// The line at the card's foot.
        public let foot: String?

        /// What the quiet line says of a read that got no answer.
        public static let missedRead = "No answer just now; asking again"

        /// The card for `status`'s build of `project` as `person` reads it, or nil when it holds no
        /// build that runs or failed.
        init?(
            _ status: TemplateStatus, project: String, person: Person, missed: Bool, asking: Bool, refusal: [String], timeZone: TimeZone
        ) {
            guard let build = status.build, status.state == .known(.building) || status.state == .known(.failed) else { return nil }
            let failed = status.state == .known(.failed), step = min(max(build.step, 1), max(status.steps, 1))
            stage = failed ? .failed : .building
            title = failed ? "\(project)’s template did not finish" : "Creating \(project)"
            started = "Started by \(build.by == person.handle ? "you" : build.by) at \(Self.clock(build.startedAt, timeZone))"
            startedAt = build.startedAt
            endedAt = failed ? build.endedAt ?? build.startedAt : nil
            done = status.steps > 0 ? Double(step) / Double(status.steps) : 0
            at = (failed ? "stopped at " : "") + "step \(step) of \(status.steps)"
            self.missed = missed ? Self.missedRead : nil
            groups = Self.groups(status.stepNames, at: step, failed: failed)
            let words = (build.remedy ?? []).compactMap { part -> String? in if case let .words(text) = part { text } else { nil } }.joined()
            reason = failed ? [build.reason, Said.fix(build.remedy ?? [], otherwise: words.isEmpty ? nil : words)].compactMap { $0 } : []
            buildsAgain = failed && person.builds
            self.asking = asking
            self.refusal = refusal
            foot = switch (failed, person.builds) {
            case (false, _): "You can close this; the build keeps going on the runner."
            case (true, true): Self.picksUp(at: step, of: status.steps)
            case (true, false): Self.askABuilder
            }
        }

        /// The card for a build of `project` that did not start: `refusal` says why.
        init(unstarted project: String, person: Person, asking: Bool, refusal: [String]) {
            stage = .unstarted
            title = "\(project)’s template did not start"
            started = nil
            startedAt = nil
            endedAt = nil
            done = 0
            at = nil
            missed = nil
            groups = []
            reason = []
            buildsAgain = person.builds
            self.asking = asking
            self.refusal = refusal
            foot = person.builds ? nil : Self.askABuilder
        }

        /// Who may build a template, as a person without `build` is told.
        static let askABuilder = "Ask an admin to build it again."

        /// What Build Again keeps of a build stopped at `step` of `steps`: the server resumes at the
        /// step that did not finish, unless every step before the last had (a failure at the last
        /// step), when it runs them all again (`TemplateBuild.tracker`).
        static func picksUp(at step: Int, of steps: Int) -> String {
            switch step {
            case 1, steps: "Build Again runs every step again."
            case 2: "Build Again picks up at step 2; the step before it is kept."
            default: "Build Again picks up at step \(step); the \(step - 1) before it are kept."
            }
        }

        /// "Project saved", then the groups over `names` by their first step's name, each done, running
        /// (failed, when `failed`) or to come by the step `step` (from 1) the build is on.
        static func groups(_ names: [String], at step: Int, failed: Bool) -> [Group] {
            var spans: [(name: String, last: Int)] = []
            for (index, name) in names.enumerated() {
                if let group = groupsByFirstStep.first(where: { $0.0 == name }) {
                    spans.append((group.1, index + 1))
                } else if !spans.isEmpty {
                    spans[spans.count - 1].last = index + 1
                }
            }
            let marked = spans.indices.map { index -> Group in
                let first = index == 0 ? 1 : spans[index - 1].last + 1, last = spans[index].last
                let mark: Mark = last < step ? .done : first > step ? .coming : failed ? .failed : .running
                return Group(name: spans[index].name, mark: mark)
            }
            return [Group(name: saved, mark: .done)] + marked
        }

        /// The time `date` was at, as a person reads a clock where they are ("14:02").
        static func clock(_ date: Date, _ timeZone: TimeZone) -> String {
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = timeZone
            let time = calendar.dateComponents([.hour, .minute], from: date)
            return String(format: "%02d:%02d", time.hour ?? 0, time.minute ?? 0)
        }

        /// The time between `start` and `end` as the card's corner says it: "4:18", "1:04:18".
        public static func elapsed(from start: Date, to end: Date) -> String {
            let seconds = max(Int(end.timeIntervalSince(start)), 0), hours = seconds / 3600, minutes = seconds % 3600 / 60
            let rest = String(format: "%02d", seconds % 60)
            return hours > 0 ? "\(hours):" + String(format: "%02d", minutes) + ":\(rest)" : "\(minutes):\(rest)"
        }

        /// Everything it says but the time, a line each, in the order it is drawn, as the drive reads it.
        public var lines: [String] {
            let marks = groups.map { group in
                let mark = switch group.mark {
                case .done: "done"
                case .running: "running"
                case .failed: "failed"
                case .coming: "to come"
                }
                return "\(group.name) · \(mark)"
            }
            let bar = [at, missed].compactMap { $0 }.joined(separator: " · ")
            return ([title, started, bar].compactMap { $0 } + marks + reason + [foot].compactMap { $0 } + refusal).filter { !$0.isEmpty }
        }
    }
}
