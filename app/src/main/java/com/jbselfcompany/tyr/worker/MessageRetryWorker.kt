package com.jbselfcompany.tyr.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based message retry worker for battery-efficient background retries.
 * Replaces continuous foreground retry loops with scheduled background jobs.
 *
 * Battery optimization: This worker respects system battery constraints and only
 * runs when network is available and battery is not low.
 */
class MessageRetryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "MessageRetryWorker"
        const val WORK_NAME_PREFIX = "message_retry_"
        const val INPUT_DESTINATION = "destination"
        const val INPUT_MESSAGE_ID = "message_id"
        const val INPUT_RETRY_COUNT = "retry_count"

        // Maximum retry attempts (matches sender.go)
        private const val MAX_RETRY_ATTEMPTS = 10

        /**
         * Schedule a retry attempt for a failed message
         * Battery optimization: Uses WorkManager constraints for efficient scheduling
         */
        fun scheduleRetry(
            context: Context,
            destination: String,
            messageId: Int,
            retryCount: Int
        ) {
            if (retryCount >= MAX_RETRY_ATTEMPTS) {
                Log.w(TAG, "Max retries reached for $destination - not scheduling")
                return
            }

            val backoffDelay = calculateBackoffDelay(retryCount)

            val inputData = Data.Builder()
                .putString(INPUT_DESTINATION, destination)
                .putInt(INPUT_MESSAGE_ID, messageId)
                .putInt(INPUT_RETRY_COUNT, retryCount)
                .build()

            // Adaptive constraints based on retry count
            // Early retries (< 3): Less restrictive for faster delivery
            // Later retries (>= 3): More restrictive to save battery
            val constraints = if (retryCount < 3) {
                // Urgent retries - any network, battery not low
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            } else {
                // Deferred retries - WiFi only, charging, device idle
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)  // WiFi only
                    .setRequiresBatteryNotLow(true)
                    .setRequiresCharging(true)  // Only when charging
                    .setRequiresDeviceIdle(true)  // Only when device idle
                    .build()
            }

            val retryWork = OneTimeWorkRequestBuilder<MessageRetryWorker>()
                .setInputData(inputData)
                .setInitialDelay(backoffDelay, TimeUnit.SECONDS)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag("message_retry")
                .addTag("destination_$destination")
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "$WORK_NAME_PREFIX$destination",
                    ExistingWorkPolicy.REPLACE,  // Replace existing retry for this destination
                    retryWork
                )

            val constraintType = if (retryCount < 3) "urgent" else "deferred"
            Log.d(TAG, "[Battery Optimization] Scheduled $constraintType retry for $destination in $backoffDelay seconds (attempt $retryCount)")
        }

        /**
         * Calculate exponential backoff delay
         * Matches the backoff strategy in sender.go
         */
        private fun calculateBackoffDelay(retryCount: Int): Long {
            val baseDelay = 5L
            val maxDelay = 300L
            val delay = (baseDelay * Math.pow(2.0, retryCount.toDouble() - 1)).toLong()
            return minOf(delay, maxDelay)
        }

        /**
         * Cancel retry for a specific destination
         */
        fun cancelRetry(context: Context, destination: String) {
            WorkManager.getInstance(context)
                .cancelUniqueWork("$WORK_NAME_PREFIX$destination")
            Log.d(TAG, "Cancelled retry for $destination")
        }

        /**
         * Cancel all pending retries
         */
        fun cancelAllRetries(context: Context) {
            WorkManager.getInstance(context)
                .cancelAllWorkByTag("message_retry")
            Log.d(TAG, "Cancelled all pending retries")
        }
    }

    override suspend fun doWork(): Result {
        val destination = inputData.getString(INPUT_DESTINATION) ?: return Result.failure()
        val messageId = inputData.getInt(INPUT_MESSAGE_ID, -1)
        val retryCount = inputData.getInt(INPUT_RETRY_COUNT, 0)

        if (messageId == -1) {
            Log.e(TAG, "Invalid message ID")
            return Result.failure()
        }

        Log.d(TAG, "[Battery Optimization] Executing retry for $destination (attempt $retryCount)")

        try {
            // TODO: Integrate with YggmailService to attempt send
            // This would call into the native layer via JNI to trigger a retry
            // For now, this is a placeholder

            // Simulated logic:
            // val success = attemptMessageSend(destination, messageId)
            val success = false  // Placeholder

            return if (success) {
                Log.i(TAG, "Message sent successfully to $destination")
                Result.success()
            } else {
                if (retryCount >= MAX_RETRY_ATTEMPTS - 1) {
                    Log.w(TAG, "Max retries reached for $destination")
                    // TODO: Notify user of permanent failure
                    Result.failure()
                } else {
                    // Schedule next retry
                    scheduleRetry(applicationContext, destination, messageId, retryCount + 1)
                    Result.success()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during retry", e)
            return Result.failure()
        }
    }

    /**
     * Attempt to send message via Yggmail service
     * TODO: Implement integration with native Yggmail layer
     */
    private suspend fun attemptMessageSend(destination: String, messageId: Int): Boolean {
        // This would integrate with YggmailService to trigger a send attempt
        // Implementation would require:
        // 1. Call into Go layer via JNI
        // 2. Trigger queue processing for specific destination
        // 3. Return result
        return false  // Placeholder
    }
}
