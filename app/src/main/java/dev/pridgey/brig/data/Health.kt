package dev.pridgey.brig.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class HealthToday(val steps: Long, val calories: Int, val activeMinutes: Int)

/** Today's steps and activity from Health Connect (Fitbit, Google Fit, Samsung Health and others write there). */
object HealthRepo {
    val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    )

    fun available(ctx: Context) = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

    suspend fun hasPermission(ctx: Context): Boolean = available(ctx) && runCatching {
        HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions()
            .contains(HealthPermission.getReadPermission(StepsRecord::class))
    }.getOrDefault(false)

    suspend fun today(ctx: Context): HealthToday? {
        if (!available(ctx)) return null
        return runCatching {
            val client = HealthConnectClient.getOrCreate(ctx)
            val granted = client.permissionController.getGrantedPermissions()
            val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
            val metrics = buildSet {
                if (HealthPermission.getReadPermission(StepsRecord::class) in granted) add(StepsRecord.COUNT_TOTAL)
                if (HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class) in granted) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
                if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted) add(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)
            }
            if (metrics.isEmpty()) return null
            val r = client.aggregate(AggregateRequest(metrics, TimeRangeFilter.between(start, Instant.now())))
            HealthToday(
                steps = r[StepsRecord.COUNT_TOTAL] ?: 0L,
                calories = r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories?.toInt() ?: 0,
                activeMinutes = r[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMinutes()?.toInt() ?: 0,
            )
        }.getOrNull()
    }
}
