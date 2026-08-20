namespace ATPantherWindows;

public sealed class AppSettings
{
    public string Phone { get; set; } = string.Empty;
    public string Password { get; set; } = string.Empty;
    public string AppwriteApiKey { get; set; } = string.Empty;
    public float ThresholdMb { get; set; } = 850f;
    public int IntervalSec { get; set; } = 60;
    public bool MonitorEnabled { get; set; }
    public string MonitorPlatform { get; set; } = "windows";
    public string DeviceId { get; set; } = string.Empty;
    public bool StartWithWindows { get; set; }
}

public sealed class LogEntry
{
    public DateTime Timestamp { get; set; } = DateTime.Now;
    public string Type { get; set; } = "CHECK";
    public double RemainingMb { get; set; } = -1;
    public string Message { get; set; } = string.Empty;
}

public sealed record DataStatus(
    double RemainingMb,
    string OfferId,
    string SubscriptionId,
    string ResourceId,
    string OnDemandAmount,
    string RefillThreshold);

public sealed record BookingResult(
    bool Success,
    bool IsUpdated,
    int StatusCode,
    string Message);

public sealed record MonitorStatus(
    string Text,
    double RemainingMb = -1,
    bool Notify = false,
    bool IsError = false);
