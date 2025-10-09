package io.mayu.birdpilot

import kotlin.ranges.ClosedFloatingPointRange

data class FinderThresholds(
    val minAreaRange: ClosedFloatingPointRange<Float>,
    val activeRatioMax: Float,
    val stabilityRequired: Int
)

enum class FinderProfile(
    val preferenceValue: String,
    val label: String,
    private val thresholds: FinderThresholds
) {
    OUTDOOR(
        preferenceValue = "OUTDOOR",
        label = "Outdoor",
        thresholds = FinderThresholds(
            minAreaRange = 0.008f..0.15f,
            activeRatioMax = 0.12f,
            stabilityRequired = 2
        )
    ),
    INDOOR_DEBUG(
        preferenceValue = "INDOOR_DEBUG",
        label = "Indoor Debug",
        thresholds = FinderThresholds(
            minAreaRange = 0.008f..0.15f,
            activeRatioMax = 0.25f,
            stabilityRequired = 1
        )
    ),
    WINDY(
        preferenceValue = "WINDY",
        label = "Windy",
        thresholds = FinderThresholds(
            minAreaRange = 0.010f..0.20f,
            activeRatioMax = 0.10f,
            stabilityRequired = 3
        )
    );

    fun thresholds(): FinderThresholds = thresholds

    companion object {
        fun fromPreference(value: String?): FinderProfile {
            return values().firstOrNull { it.preferenceValue == value } ?: OUTDOOR
        }
    }
}
