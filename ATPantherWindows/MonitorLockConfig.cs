namespace ATPantherWindows;

internal static class MonitorLockConfig
{
    // Nach dem Anlegen der Appwrite Function ersetzen.
    public const string FunctionExecutionUrl =
        "https://cloud.appwrite.io/v1/functions/REPLACE_WITH_FUNCTION_ID/executions";
    public const string ProjectId = "REPLACE_WITH_APPWRITE_PROJECT_ID";
    public const string SharedSecret = "REPLACE_WITH_SHARED_LOCK_SECRET";

    public static bool IsConfigured =>
        !FunctionExecutionUrl.Contains("REPLACE_", StringComparison.Ordinal) &&
        !ProjectId.Contains("REPLACE_", StringComparison.Ordinal) &&
        !SharedSecret.Contains("REPLACE_", StringComparison.Ordinal);
}
