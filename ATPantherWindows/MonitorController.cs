namespace ATPantherWindows;

internal sealed class MonitorController : IAsyncDisposable
{
    private const int MaxConsecutiveLoginFailures = 5;
    private const int MinimumIntervalSec = 60;

    private readonly AldiTalkClient _client = new();
    private readonly LogStore _logStore;
    private CancellationTokenSource? _cancellation;
    private Task? _monitorTask;

    public MonitorController(LogStore logStore)
    {
        _logStore = logStore;
    }

    public bool IsRunning { get; private set; }
    public event Action<MonitorStatus>? StatusChanged;

    public async Task StartAsync(AppSettings settings)
    {
        await StopAsync();
        _cancellation = new CancellationTokenSource();
        IsRunning = true;
        var token = _cancellation.Token;
        _monitorTask = RunAsync(settings, token);
        await Task.Yield();
    }

    public async Task StopAsync()
    {
        if (_cancellation is null) return;
        _cancellation.Cancel();
        try
        {
            if (_monitorTask is not null) await _monitorTask;
        }
        catch (OperationCanceledException)
        {
            // Expected when the user stops the monitor.
        }
        finally
        {
            _cancellation.Dispose();
            _cancellation = null;
            _monitorTask = null;
            IsRunning = false;
        }
    }

    private async Task RunAsync(AppSettings settings, CancellationToken cancellationToken)
    {
        MonitorLockClient? lockClient = null;
        if (settings.SingleMonitorEnabled)
        {
            if (!MonitorLockConfig.IsConfigured)
            {
                Report("Gemeinsame Monitor-Sperre ist nicht eingerichtet", notify: true, isError: true);
                return;
            }
            lockClient = new MonitorLockClient();
            if (!await lockClient.AcquireAsync(settings.Phone, settings.DeviceId, cancellationToken))
            {
                Report("Monitor läuft bereits auf dem anderen Gerät", notify: true, isError: true);
                return;
            }
        }

        try
        {
            await RunUnlockedAsync(settings, cancellationToken, lockClient);
        }
        finally
        {
            if (lockClient is not null)
                await lockClient.ReleaseAsync(settings.Phone, settings.DeviceId, CancellationToken.None);
        }
    }

    private async Task RunUnlockedAsync(
        AppSettings settings,
        CancellationToken cancellationToken,
        MonitorLockClient? lockClient)
    {
        try
        {
            Report("Anmelden...", notify: true);
            var contractId = await LoginAndResolveContractAsync(settings, cancellationToken);
            if (contractId is null)
            {
                Report("Login fehlgeschlagen (siehe Log)", notify: true, isError: true);
                return;
            }

            _logStore.Add(new LogEntry { Message = "Login erfolgreich" });
            _logStore.Add(new LogEntry { Message = $"Vertrags-ID erkannt: {contractId}" });
            Report("Monitor aktiv", notify: true);

            var consecutiveLoginFailures = 0;
            while (!cancellationToken.IsCancellationRequested)
            {
                if (lockClient is not null &&
                    !await lockClient.HeartbeatAsync(settings.Phone, settings.DeviceId, cancellationToken))
                {
                    Report("Gemeinsame Monitor-Sperre verloren – Monitor gestoppt", notify: true, isError: true);
                    return;
                }

                var status = await _client.GetRemainingDataAsync(contractId, cancellationToken);
                if (status is null)
                {
                    Report("Datenvolumen nicht abrufbar – Re-Login...", notify: true, isError: true);
                    var newContractId = await LoginAndResolveContractAsync(settings, cancellationToken);
                    if (newContractId is not null)
                    {
                        consecutiveLoginFailures = 0;
                        contractId = newContractId;
                        Report("Re-Login erfolgreich", notify: true);
                        continue;
                    }

                    consecutiveLoginFailures++;
                    if (consecutiveLoginFailures >= MaxConsecutiveLoginFailures)
                    {
                        Report("Re-Login 5x fehlgeschlagen, Monitor gestoppt", notify: true, isError: true);
                        return;
                    }

                    Report($"Re-Login fehlgeschlagen ({consecutiveLoginFailures}/{MaxConsecutiveLoginFailures})",
                        notify: true, isError: true);
                    await DelayAsync(settings.IntervalSec, cancellationToken);
                    continue;
                }

                consecutiveLoginFailures = 0;
                var remainingMessage = $"Verbleibend: {status.RemainingMb:F1} MB";
                if (status.RemainingMb < settings.ThresholdMb)
                {
                    _logStore.Add(new LogEntry
                    {
                        RemainingMb = status.RemainingMb,
                        Message = $"{remainingMessage} – unter Schwelle ({settings.ThresholdMb:F0} MB)"
                    });
                    Report("Buche 1 GB...", status.RemainingMb, notify: true);
                    var booking = await _client.Book1GbAsync(status, cancellationToken);
                    var bookingMessage = booking.Success
                        ? "1 GB erfolgreich gebucht"
                        : $"Buchung fehlgeschlagen ({booking.StatusCode}): {Trim(booking.Message, 120)}";
                    _logStore.Add(new LogEntry
                    {
                        Type = "BOOKING",
                        RemainingMb = status.RemainingMb,
                        Message = bookingMessage
                    });
                    Report(bookingMessage, status.RemainingMb, notify: true, isError: !booking.Success);
                }
                else
                {
                    Report(remainingMessage, status.RemainingMb);
                }

                await DelayAsync(settings.IntervalSec, cancellationToken);
            }
        }
        catch (OperationCanceledException)
        {
            // Normal shutdown.
        }
        catch (Exception ex)
        {
            Report($"Monitor-Fehler: {Trim(ex.Message, 120)}", notify: true, isError: true);
        }
        finally
        {
            IsRunning = false;
        }
    }

    private async Task<string?> LoginAndResolveContractAsync(AppSettings settings, CancellationToken cancellationToken)
    {
        if (!await _client.LoginAsync(settings.Phone, settings.Password, cancellationToken)) return null;
        return await _client.ResolveContractIdAsync(settings.Phone, cancellationToken);
    }

    private static Task DelayAsync(int seconds, CancellationToken cancellationToken) =>
        Task.Delay(TimeSpan.FromSeconds(Math.Max(MinimumIntervalSec, seconds)), cancellationToken);

    private void Report(string text, double remainingMb = -1, bool notify = false, bool isError = false)
    {
        _logStore.Add(new LogEntry
        {
            Type = "CHECK",
            RemainingMb = remainingMb,
            Message = text
        });
        StatusChanged?.Invoke(new MonitorStatus(text, remainingMb, notify, isError));
    }

    private static string Trim(string? value, int maxLength) =>
        string.IsNullOrEmpty(value) ? "Unbekannter Fehler" :
        value.Length <= maxLength ? value : value[..maxLength];

    public async ValueTask DisposeAsync()
    {
        await StopAsync();
        _client.Dispose();
    }
}
