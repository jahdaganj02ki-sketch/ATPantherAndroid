using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace ATPantherWindows;

internal sealed class MonitorLockClient
{
    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(15) };

    public async Task<bool> AcquireAsync(string phone, string deviceId, CancellationToken cancellationToken) =>
        await SendAsync("acquire", phone, deviceId, cancellationToken);

    public async Task<bool> HeartbeatAsync(string phone, string deviceId, CancellationToken cancellationToken) =>
        await SendAsync("heartbeat", phone, deviceId, cancellationToken);

    public async Task ReleaseAsync(string phone, string deviceId, CancellationToken cancellationToken)
    {
        try
        {
            await SendAsync("release", phone, deviceId, cancellationToken);
        }
        catch
        {
            // The TTL still releases the lease after a crash or network outage.
        }
    }

    private async Task<bool> SendAsync(string operation, string phone, string deviceId,
        CancellationToken cancellationToken)
    {
        if (!MonitorLockConfig.IsConfigured) return false;
        var payload = new
        {
            @async = false,
            body = JsonSerializer.Serialize(new
            {
                operation,
                phoneHash = HashPhone(phone),
                deviceId,
                secret = MonitorLockConfig.SharedSecret
            }),
            method = "POST",
            path = "/"
        };
        using var request = new HttpRequestMessage(HttpMethod.Post, MonitorLockConfig.FunctionExecutionUrl)
        {
            Content = JsonContent.Create(payload)
        };
        request.Headers.TryAddWithoutValidation("X-Appwrite-Project", MonitorLockConfig.ProjectId);
        using var response = await _http.SendAsync(request, cancellationToken);
        if (!response.IsSuccessStatusCode) return false;
        using var outer = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancellationToken));
        var body = outer.RootElement.TryGetProperty("responseBody", out var responseBody)
            ? responseBody.GetString()
            : outer.RootElement.GetRawText();
        if (string.IsNullOrWhiteSpace(body)) return false;
        using var result = JsonDocument.Parse(body);
        return result.RootElement.TryGetProperty("granted", out var granted) && granted.ValueKind == JsonValueKind.True;
    }

    private static string HashPhone(string phone)
    {
        var normalized = phone.Trim();
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(normalized))).ToLowerInvariant();
    }
}
