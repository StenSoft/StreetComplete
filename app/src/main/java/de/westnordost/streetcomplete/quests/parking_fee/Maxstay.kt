package de.westnordost.streetcomplete.quests.parking_fee

import de.westnordost.streetcomplete.data.meta.updateWithCheckDate
import de.westnordost.streetcomplete.data.osm.osmquests.Tags
import de.westnordost.streetcomplete.ktx.toShortString
import de.westnordost.streetcomplete.osm.opening_hours.parser.OpeningHoursRuleList
import de.westnordost.streetcomplete.quests.parking_fee.Maxstay.Unit.*

sealed interface Maxstay {
    enum class Unit { MINUTES, HOURS }
}

object NoMaxstay : Maxstay
data class MaxstayDuration(val value: Double, val unit: Maxstay.Unit) : Maxstay
data class MaxstayAtHours(val duration: MaxstayDuration, val hours: OpeningHoursRuleList) : Maxstay
data class MaxstayExceptAtHours(val duration: MaxstayDuration, val hours: OpeningHoursRuleList) : Maxstay

fun MaxstayDuration.toOsmValue(): String =
    value.toShortString() + " " + when (unit) {
        MINUTES -> if (value != 1.0) "minutes" else "minute"
        HOURS -> if (value != 1.0) "hours" else "hour"
    }

fun Maxstay.applyTo(tags: Tags) {
    when (this) {
        is MaxstayExceptAtHours -> {
            tags.updateWithCheckDate("maxstay", duration.toOsmValue())
            tags["maxstay:conditional"] = "no @ ($hours)"
        }
        is MaxstayAtHours -> {
            tags.updateWithCheckDate("maxstay", "no")
            tags["maxstay:conditional"] = "${duration.toOsmValue()} @ ($hours)"
        }
        is MaxstayDuration -> {
            tags.updateWithCheckDate("maxstay", toOsmValue())
            tags.remove("maxstay:conditional")
        }
        NoMaxstay -> {
            tags.updateWithCheckDate("maxstay", "no")
            tags.remove("maxstay:conditional")
        }
    }
}
