import SwiftUI
import FlintCore
import FamilyControls

/// App-owned focus sessions, laid out as M12's comparison card and week strip.
/// Screen Time report content remains in its own extension.
struct FocusHistorySection: View {
    @ObservedObject var history: FocusHistoryModel
    var range: InsightsRange = .week
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var recoveryCelebrated = false
    @State private var recoveryFailure: String?

    private var allDays: [FlintHistoryDay] { history.summary?.days ?? [] }
    private var days: [FlintHistoryDay] {
        range == .week ? allDays : Array(allDays.suffix(1))
    }

    private var hasValues: Bool {
        history.summary.map { $0.availability == .value || $0.availability == .degraded } ?? false
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            FlintCard(radius: 24, padding: EdgeInsets(top: 18, leading: 16, bottom: 16, trailing: 16)) {
                VStack(alignment: .leading, spacing: 18) {
                    comparison
                    if hasValues {
                        Rectangle().fill(FlintBrand.bone(0.08)).frame(height: 1)
                        bars
                        Text("Measured on this phone. Nothing is uploaded.")
                            .font(FlintFont.text(12))
                            .foregroundStyle(FlintBrand.stone)
                            .frame(maxWidth: .infinity, alignment: .center)
                            .multilineTextAlignment(.center)
                    }
                    if let summary = history.summary, summary.availability == .degraded {
                        Text(FocusHistoryText.degraded).flintSecondary(13)
                    }
                    if history.isPreview { Text("DEBUG · Seeded preview events").flintSecondary(12) }
                }
            }
            .accessibilityIdentifier("history.section")

            if StreakPresentationPolicy.showsCard(for: history.summary), let summary = history.summary {
                streakCard(summary)
                recoveryCard(summary)
            }

            weekSection
        }
    }

    private func streakCard(_ summary: FlintHistorySummary) -> some View {
        let completedToday = (summary.days.last?.completed ?? 0) > 0
        let mood = StreakEmberMood.forStreak(summary.streak, completedToday: completedToday)
        return FlintCard(radius: 24, padding: EdgeInsets(top: 18, leading: 16, bottom: 16, trailing: 16)) {
            VStack(spacing: 6) {
                FlintStreakEmber(mood: mood, size: StreakFlamePlacement.insights)
                Text("\(summary.streak)")
                    .font(FlintFont.display(56, relativeTo: .largeTitle))
                    .foregroundStyle(FlintBrand.bone)
                Text("DAY STREAK")
                    .font(FlintFont.text(12, weight: .semibold))
                    .tracking(1.2)
                    .foregroundStyle(FlintBrand.stone)
            }
            .frame(maxWidth: .infinity)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(StreakPresentationPolicy.badgeLabel(for: summary))
        }
        .accessibilityIdentifier("history.streak")
    }

    // MARK: Recovery

    /// Conditional free recovery: one missed day, one 30-day allowance, grant-bound progress.
    /// The card binds the freeze (so the day's selection cannot be re-pointed later) and asks the
    /// durable committer once duration is met and enforcement is live. Success copy appears only
    /// after the store reports a durable append.
    @ViewBuilder
    private func recoveryCard(_ summary: FlintHistorySummary) -> some View {
        let events = history.snapshotEvents
        let calendar = Calendar.current
        let now = Date()
        let eligibility = StreakRecoveryPolicy.evaluate(
            sessions: summary.sessions,
            events: events,
            now: now,
            calendar: calendar,
            historyStart: history.historyStart,
            // Degraded or unavailable history cannot prove the allowance is unused.
            readable: summary.availability == .value,
            recoveryFloorAt: history.recoveryFloorAt,
            allowanceTrusted: history.allowanceTrusted
        )
        let guardStore = RecoveryChallengeGuardStore()
        // Authorization is a live fact, not a stored one: screens can be revoked mid-challenge.
        let authorizationApproved: Bool = {
            #if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
            return AuthorizationCenter.shared.authorizationStatus == .approved
            #else
            return true
            #endif
        }()
        let protection: StreakRecoveryProtection.BoundProfile? = {
            // A preview must never bind (or read) the real App Group freeze, and an unverifiable
            // allowance must not freeze a selection for a challenge the commit will refuse.
            guard !history.isPreview, history.allowanceTrusted,
                  case .offer(let challenge) = eligibility,
                  let selection = FlintGroupStore()?.loadSelection() else { return guardStore.current() }
            return guardStore.ensureFrozen(
                fingerprint: StreakRecoveryProtection.fingerprint(of: selection),
                applicationCount: StreakRecoveryProtection.applicationCount(of: selection),
                allowListMode: false,
                recoveryDay: challenge.recoveryDay,
                missedDay: challenge.missedDay,
                frozenAtMs: FlintHistoryEvent.milliseconds(now)
            ) ?? guardStore.current()
        }()
        let enforcementIntact = guardStore.isEnforcementIntact() && authorizationApproved
        let ui = StreakRecoveryPresentation.ui(
            summary: summary,
            events: events,
            now: now,
            calendar: calendar,
            historyStart: history.historyStart,
            recoveryFloorAt: history.recoveryFloorAt,
            allowanceTrusted: history.allowanceTrusted,
            protection: protection,
            enforcementIntact: enforcementIntact
        )

        if recoveryCelebrated {
            FlintCard(radius: 24, padding: EdgeInsets(top: 18, leading: 16, bottom: 16, trailing: 16)) {
                VStack(alignment: .leading, spacing: 8) {
                    Text(StreakRecoveryPresentation.title)
                        .font(FlintFont.text(17, weight: .semibold))
                        .foregroundStyle(FlintBrand.bone)
                    Text(StreakRecoveryPresentation.success)
                        .font(FlintFont.text(14))
                        .foregroundStyle(FlintBrand.ember)
                }
            }
            .accessibilityIdentifier("history.streak.recovery.success")
        } else {
            switch ui {
            case .hidden:
                EmptyView()
            case .unavailable(let message):
                FlintCard(radius: 24, padding: EdgeInsets(top: 18, leading: 16, bottom: 16, trailing: 16)) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(StreakRecoveryPresentation.title)
                            .font(FlintFont.text(17, weight: .semibold))
                            .foregroundStyle(FlintBrand.bone)
                        Text(message)
                            .font(FlintFont.text(14))
                            .foregroundStyle(FlintBrand.stone)
                    }
                }
                .accessibilityIdentifier("history.streak.recovery")
            case .offer(let offer):
                FlintCard(radius: 24, padding: EdgeInsets(top: 18, leading: 16, bottom: 16, trailing: 16)) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(StreakRecoveryPresentation.title)
                            .font(FlintFont.text(17, weight: .semibold))
                            .foregroundStyle(FlintBrand.bone)
                        Text(StreakRecoveryPresentation.required(offer.requiredMinutes))
                            .font(FlintFont.text(15))
                            .foregroundStyle(FlintBrand.mist)
                        Text("Missed \(offer.missedLabel)")
                            .font(FlintFont.text(13))
                            .foregroundStyle(FlintBrand.stone)
                        Text(offer.protectedAppsLabel)
                            .font(FlintFont.text(13))
                            .foregroundStyle(FlintBrand.stone)
                            .accessibilityIdentifier("history.streak.recovery.apps")
                        if enforcementIntact {
                            Text(StreakRecoveryPresentation.protectionLocked)
                                .font(FlintFont.text(12))
                                .foregroundStyle(FlintBrand.stone)
                        }
                        Text(recoveryFailure ?? offer.statusLine)
                            .font(FlintFont.text(14, weight: .semibold))
                            .foregroundStyle(FlintBrand.ember)
                        Text(offer.expiresLabel)
                            .font(FlintFont.text(12))
                            .foregroundStyle(FlintBrand.stone)
                        Text(StreakRecoveryPresentation.tokenFree)
                            .font(FlintFont.text(12))
                            .foregroundStyle(FlintBrand.stone)
                        Text(StreakRecoveryPresentation.footnote)
                            .font(FlintFont.text(12))
                            .foregroundStyle(FlintBrand.stone)
                    }
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(offer.accessibilityLabel)
                .accessibilityIdentifier("history.streak.recovery")
                .task(id: offer.satisfied) {
                    guard offer.satisfied, !recoveryCelebrated, !history.isPreview else { return }
                    recoveryFailure = nil
                    let result = StreakRecoveryCommitter.tryCommit(
                        store: FlintHistoryStore.shared,
                        calendar: calendar,
                        activeSessionID: nil,
                        protection: protection,
                        protectionActive: enforcementIntact
                    )
                    switch result {
                    case .success:
                        recoveryCelebrated = true
                        if let recoveryDay = protection?.recoveryDay {
                            guardStore.clearIfDay(recoveryDay, calendar: calendar)
                        }
                        history.refresh()
                    case .rejected, .persistFailed:
                        // Never leave "confirming" on screen after a refusal: silence would read
                        // as a successful recovery that simply never appears.
                        recoveryFailure = "Recovery couldn’t be confirmed. Nothing changed — your streak stays as it is."
                    }
                }
                .task(id: authorizationApproved) {
                    // Losing Screen Time authorization during a bound challenge voids the day:
                    // reconnecting is not proof the gap was ever enforced.
                    guard !authorizationApproved, !history.isPreview, protection != nil else { return }
                    guardStore.markEnforcementBroken()
                    recoveryFailure = StreakRecoveryPresentation.protectionDegraded
                }
            }
        }
    }

    // MARK: Sentence

    @ViewBuilder private var comparison: some View {
        if let summary = history.summary {
            if summary.availability == .unavailable {
                Text("Focus history unavailable")
                    .font(FlintFont.text(17, weight: .semibold))
                    .foregroundStyle(FlintBrand.mist)
                    .fixedSize(horizontal: false, vertical: true)
            } else if summary.availability == .empty {
                Text("No focus sessions yet. Start one from Today and it will show up here.")
                    .font(FlintFont.text(17, weight: .semibold))
                    .foregroundStyle(FlintBrand.mist)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                Text(sentence)
                    .font(FlintFont.text(17, weight: .semibold))
                    .foregroundStyle(FlintBrand.bone)
                    .lineSpacing(3)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityLabel(spokenSentence)
            }
        } else {
            Text("Loading focus history…")
                .font(FlintFont.text(17, weight: .semibold))
                .foregroundStyle(FlintBrand.mist)
        }
    }

    private var sentence: AttributedString {
        var text = AttributedString("You focused ")
        var amount = AttributedString(todayText)
        amount.foregroundColor = FlintBrand.emberText
        text += amount
        if let diff = difference {
            text += AttributedString(" today, \(FocusHistoryText.duration(abs(diff))) ")
            text += AttributedString(diff >= 0 ? "more" : "less")
            text += AttributedString(" than your 7-day average.")
        } else {
            text += AttributedString(" today.")
        }
        return text
    }

    private var spokenSentence: String {
        var line = "You focused \(todayText) today"
        if let diff = difference {
            line += ", \(FocusHistoryText.duration(abs(diff))) \(diff >= 0 ? "more" : "less") than your 7-day average"
        }
        return line + "."
    }

    private var todayText: String {
        FocusHistoryText.duration(days.last?.focusedSeconds)
    }

    /// Mean of the days before today that have data; nil when there is nothing to compare.
    private var average: TimeInterval? {
        let prior = allDays.dropLast().compactMap(\.focusedSeconds)
        guard !prior.isEmpty else { return nil }
        return prior.reduce(0, +) / Double(prior.count)
    }

    private var difference: TimeInterval? {
        guard let average, let today = days.last?.focusedSeconds else { return nil }
        return today - average
    }

    // MARK: Bars

    private var bars: some View {
        let today = days.last?.focusedSeconds
        let averageValue = average
        let peak = max(today ?? 0, averageValue ?? 0, 60)
        return HStack(alignment: .bottom, spacing: 16) {
            barColumn(value: today, peak: peak, label: "Focused today", ember: true)
            if let averageValue {
                barColumn(value: averageValue, peak: peak, label: "Your 7-day average", ember: false)
            }
            if range == .today, averageValue == nil {
                Text("Focus a few days to see your average.")
                    .flintSecondary(13)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.top, 6)
        .frame(maxWidth: .infinity)
    }

    private func barColumn(value: TimeInterval?, peak: TimeInterval, label: String, ember: Bool) -> some View {
        let fraction = max(0.02, min(1, (value ?? 0) / peak))
        return VStack(spacing: 10) {
            VStack {
                Spacer(minLength: 0)
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(ember
                        ? LinearGradient(stops: [
                            .init(color: FlintBrand.emberTop, location: 0),
                            .init(color: FlintBrand.ember, location: 0.55),
                            .init(color: FlintBrand.ember.opacity(0.25), location: 1),
                        ], startPoint: .top, endPoint: .bottom)
                        : LinearGradient(stops: [
                            .init(color: FlintBrand.bone.opacity(0.22), location: 0),
                            .init(color: FlintBrand.bone.opacity(0.10), location: 0.6),
                            .init(color: FlintBrand.bone.opacity(0.03), location: 1),
                        ], startPoint: .top, endPoint: .bottom))
                    .frame(width: 64, height: max(8, 120 * fraction))
            }
            .frame(height: 140, alignment: .bottom)
            VStack(spacing: 2) {
                Text(FocusHistoryText.duration(value))
                    .font(FlintFont.display(24, relativeTo: .title2))
                    .monospacedDigit()
                    .foregroundStyle(ember ? FlintBrand.bone : FlintBrand.mist)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Text(label)
                    .font(FlintFont.text(12))
                    .foregroundStyle(FlintBrand.mist)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label), \(FocusHistoryText.duration(value))")
    }

    // MARK: Week

    private var weekSection: some View {
        let week = Array(allDays.suffix(7))
        let peak = max(1, week.compactMap(\.focusedSeconds).max() ?? 1)
        return VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text("Focus this week").flintSection()
                Spacer()
                Text("focused time").font(FlintFont.text(12)).foregroundStyle(FlintBrand.mist)
            }
            .padding(.horizontal, 4)
            HStack(alignment: .bottom, spacing: 8) {
                ForEach(week) { day in
                    let isToday = Calendar.current.isDateInToday(day.start)
                    let height = day.focusedSeconds.map { max(4, 40 * min(1, ($0 / peak))) }
                    if let height {
                        RoundedRectangle(cornerRadius: 4, style: .continuous)
                            .fill(isToday ? FlintBrand.ember : FlintBrand.bone(0.18))
                            .frame(height: height)
                            .frame(maxWidth: .infinity)
                            .accessibilityLabel("\(day.start.formatted(date: .abbreviated, time: .omitted)), \(FocusHistoryText.duration(day.focusedSeconds))")
                    } else {
                        RoundedRectangle(cornerRadius: 2, style: .continuous)
                            .strokeBorder(FlintBrand.bone(0.2), style: StrokeStyle(lineWidth: 1, dash: [2, 2]))
                            .frame(height: 4)
                            .frame(maxWidth: .infinity)
                            .accessibilityLabel("\(day.start.formatted(date: .abbreviated, time: .omitted)), no data")
                    }
                }
            }
            .frame(height: 40, alignment: .bottom)
            HStack(spacing: 8) {
                ForEach(week) { day in
                    let isToday = Calendar.current.isDateInToday(day.start)
                    Text(day.start.formatted(.dateTime.weekday(.narrow)))
                        .font(FlintFont.text(11, weight: isToday ? .bold : .regular))
                        .foregroundStyle(isToday ? FlintBrand.bone : FlintBrand.stone)
                        .frame(maxWidth: .infinity)
                }
            }
        }
    }
}
