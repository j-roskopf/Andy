package app.andy.desktop.service.automations

import app.andy.model.Automation
import app.andy.model.AutomationIntervalUnit
import app.andy.model.AutomationSchedule
import app.andy.model.MinAutomationIntervalMillis
import app.andy.model.resolveAutomationTimeZoneId
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

fun automationZoneId(id: String): ZoneId {
    val resolved = resolveAutomationTimeZoneId(id, fallback = ZoneId.systemDefault().id)
    return runCatching { ZoneId.of(resolved) }.getOrElse { ZoneId.systemDefault() }
}

fun nextAutomationOccurrence(
    automation: Automation,
    fromExclusiveMillis: Long,
    lastFiredAtMillis: Long?,
): Long? = nextScheduleOccurrence(
    schedule = automation.schedule,
    timeZone = automation.timeZone,
    runHour = automation.runHour,
    runMinute = automation.runMinute,
    fromExclusiveMillis = fromExclusiveMillis,
    lastFiredAtMillis = lastFiredAtMillis,
)

fun catchUpOccurrence(
    scheduledMillis: Long?,
    nowMillis: Long,
): Long? {
    if (scheduledMillis == null) return null
    return if (scheduledMillis <= nowMillis) nowMillis else scheduledMillis
}

/** No armed, scheduled work: the scheduler should wait for a state change instead of polling. */
internal const val NoScheduledWakeDelayMillis = Long.MAX_VALUE

/**
 * Safety poll while something is armed, so a wall-clock jump (sleep/hibernate) is noticed
 * without waking every few seconds. Unused when [NoScheduledWakeDelayMillis] applies.
 */
internal const val MaxArmedSchedulerPollMillis = 60_000L

internal fun dueScheduledAutomations(
    automations: List<Automation>,
    nowMillis: Long,
    inFlightIds: Set<String>,
): List<Automation> = automations.filter { automation ->
    val nextRunAt = automation.nextRunAtMillis
    !automation.paused &&
        automation.id !in inFlightIds &&
        automation.schedule !is AutomationSchedule.Manual &&
        nextRunAt != null &&
        nextRunAt <= nowMillis
}

internal fun nextSchedulerWakeDelayMillis(
    automations: List<Automation>,
    nowMillis: Long,
    inFlightIds: Set<String>,
): Long {
    var soonest: Long? = null
    for (automation in automations) {
        if (automation.paused) continue
        if (automation.schedule is AutomationSchedule.Manual) continue
        if (automation.id in inFlightIds) continue
        val next = automation.nextRunAtMillis ?: continue
        val delay = (next - nowMillis).coerceAtLeast(0L)
        soonest = minOf(soonest ?: delay, delay)
    }
    return soonest ?: NoScheduledWakeDelayMillis
}

fun nextScheduleOccurrence(
    schedule: AutomationSchedule,
    timeZone: String,
    runHour: Int,
    runMinute: Int,
    fromExclusiveMillis: Long,
    lastFiredAtMillis: Long?,
): Long? {
    val zone = automationZoneId(timeZone)
    val from = Instant.ofEpochMilli(fromExclusiveMillis).atZone(zone)
    val hour = runHour.coerceIn(0, 23)
    val minute = runMinute.coerceIn(0, 59)
    val raw = when (schedule) {
        AutomationSchedule.Manual -> null
        is AutomationSchedule.Once ->
            if (lastFiredAtMillis != null) {
                null
            } else if (schedule.atMillis > 0L) {
                schedule.atMillis
            } else {
                nextMatchingDay(from, hour, minute) { true }
            }
        AutomationSchedule.Hourly -> nextHourly(from, minute)
        AutomationSchedule.Daily -> nextMatchingDay(from, hour, minute) { true }
        AutomationSchedule.Weekdays -> nextMatchingDay(from, hour, minute) { !it.dayOfWeek.isWeekend() }
        is AutomationSchedule.Weekly -> {
            val wanted = isoDayOfWeek(schedule.dayOfWeek)
            nextMatchingDay(from, hour, minute) { it.dayOfWeek == wanted }
        }
        is AutomationSchedule.Interval -> nextInterval(schedule, fromExclusiveMillis)
        is AutomationSchedule.Cron -> nextCron(schedule.expression, from)
    } ?: return null
    return enforceMinInterval(raw, lastFiredAtMillis, schedule)
}

private fun DayOfWeek.isWeekend(): Boolean =
    this == DayOfWeek.SATURDAY || this == DayOfWeek.SUNDAY

private fun isoDayOfWeek(iso: Int): DayOfWeek = when (iso.coerceIn(1, 7)) {
    1 -> DayOfWeek.MONDAY
    2 -> DayOfWeek.TUESDAY
    3 -> DayOfWeek.WEDNESDAY
    4 -> DayOfWeek.THURSDAY
    5 -> DayOfWeek.FRIDAY
    6 -> DayOfWeek.SATURDAY
    else -> DayOfWeek.SUNDAY
}

private fun nextHourly(from: ZonedDateTime, minute: Int): Long {
    var candidate = from.truncatedTo(ChronoUnit.HOURS).withMinute(minute).withSecond(0).withNano(0)
    if (!candidate.isAfter(from)) candidate = candidate.plusHours(1)
    return candidate.toInstant().toEpochMilli()
}

private fun nextMatchingDay(
    from: ZonedDateTime,
    hour: Int,
    minute: Int,
    predicate: (ZonedDateTime) -> Boolean,
): Long {
    var day = from.toLocalDate()
    repeat(400) {
        var candidate = day.atTime(hour, minute).atZone(from.zone)
        if (predicate(candidate) && candidate.isAfter(from)) {
            return candidate.toInstant().toEpochMilli()
        }
        day = day.plusDays(1)
    }
    error("no matching day in 400 days")
}

private fun nextInterval(schedule: AutomationSchedule.Interval, fromExclusiveMillis: Long): Long {
    val step = intervalStepMillis(schedule).coerceAtLeast(MinAutomationIntervalMillis)
    var next = schedule.startAtMillis
    if (next <= fromExclusiveMillis) {
        val skipped = ((fromExclusiveMillis - next) / step) + 1
        next += skipped * step
    }
    return next
}

fun intervalStepMillis(schedule: AutomationSchedule.Interval): Long {
    val n = schedule.every.coerceAtLeast(1).toLong()
    return when (schedule.unit) {
        AutomationIntervalUnit.Minutes -> n * 60_000L
        AutomationIntervalUnit.Hours -> n * 3_600_000L
        AutomationIntervalUnit.Days -> n * 86_400_000L
    }
}

private fun enforceMinInterval(
    candidate: Long,
    lastFiredAtMillis: Long?,
    schedule: AutomationSchedule,
): Long {
    val needsFloor = schedule is AutomationSchedule.Interval || schedule is AutomationSchedule.Cron
    if (!needsFloor || lastFiredAtMillis == null) return candidate
    val minNext = lastFiredAtMillis + MinAutomationIntervalMillis
    return if (candidate < minNext) minNext else candidate
}

internal fun nextCron(expression: String, from: ZonedDateTime): Long? {
    val cron = CronExpression.parse(expression) ?: return null
    var cursor = from.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
    repeat(366 * 24 * 60) {
        if (cron.matches(cursor)) return cursor.toInstant().toEpochMilli()
        cursor = cursor.plusMinutes(1)
    }
    return null
}

internal class CronExpression(
    private val minutes: CronField,
    private val hours: CronField,
    private val daysOfMonth: CronField,
    private val months: CronField,
    private val daysOfWeek: CronField,
) {
    fun matches(time: ZonedDateTime): Boolean {
        val cronDow = if (time.dayOfWeek == DayOfWeek.SUNDAY) 0 else time.dayOfWeek.value
        return minutes.matches(time.minute) &&
            hours.matches(time.hour) &&
            daysOfMonth.matches(time.dayOfMonth) &&
            months.matches(time.monthValue) &&
            (daysOfWeek.matches(cronDow) || (daysOfWeek.matches(7) && cronDow == 0))
    }

    companion object {
        fun parse(expression: String): CronExpression? {
            val parts = expression.trim().split(Regex("\\s+"))
            if (parts.size != 5) return null
            return runCatching {
                CronExpression(
                    minutes = CronField.parse(parts[0], 0, 59),
                    hours = CronField.parse(parts[1], 0, 23),
                    daysOfMonth = CronField.parse(parts[2], 1, 31),
                    months = CronField.parse(parts[3], 1, 12),
                    daysOfWeek = CronField.parse(parts[4], 0, 7),
                )
            }.getOrNull()
        }
    }
}

internal class CronField(private val allowed: Set<Int>) {
    fun matches(value: Int): Boolean = value in allowed

    companion object {
        fun parse(token: String, min: Int, max: Int): CronField {
            val allowed = linkedSetOf<Int>()
            token.split(',').forEach { piece ->
                val stepSplit = piece.split('/')
                val rangePart = stepSplit[0]
                val step = stepSplit.getOrNull(1)?.toIntOrNull() ?: 1
                require(step >= 1)
                val (start, end) = when {
                    rangePart == "*" -> min to max
                    "-" in rangePart -> {
                        val bounds = rangePart.split('-')
                        require(bounds.size == 2)
                        bounds[0].toInt() to bounds[1].toInt()
                    }
                    else -> rangePart.toInt() to rangePart.toInt()
                }
                var value = start
                while (value <= end) {
                    if (value in min..max) allowed += value
                    value += step
                }
            }
            require(allowed.isNotEmpty())
            return CronField(allowed)
        }
    }
}
