package com.alditalk.panther.lock

object MonitorLockConfig {
    // Nach dem Anlegen der Appwrite Function ersetzen.
    const val FUNCTION_EXECUTION_URL =
        "https://cloud.appwrite.io/v1/functions/REPLACE_WITH_FUNCTION_ID/executions"
    const val PROJECT_ID = "REPLACE_WITH_APPWRITE_PROJECT_ID"
    const val SHARED_SECRET = "REPLACE_WITH_SHARED_LOCK_SECRET"

    val isConfigured: Boolean
        get() = !FUNCTION_EXECUTION_URL.contains("REPLACE_") &&
            !PROJECT_ID.contains("REPLACE_") &&
            !SHARED_SECRET.contains("REPLACE_")
}
