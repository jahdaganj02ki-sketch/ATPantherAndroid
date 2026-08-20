using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace ATPantherWindows;

internal static class AuthConfig
{
    public const string Portal = "https://www.alditalk-kundenportal.de";
    public const string Auth = "https://login.alditalk-kundenbetreuung.de";
    public const string AuthHost = "login.alditalk-kundenbetreuung.de";
    public const string ClientId = "U-621-Varnish";
    public const string RedirectUri = Portal + "/logged-in-home-page/";
    public const string AuthEndpoint = Auth + "/signin/json/realms/alditalk/authenticate?authIndexType=service&authIndexValue=Login";
    public const string UserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
}

internal sealed class AldiTalkClient : IDisposable
{
    private HttpClient _http;
    private CookieContainer _cookies;

    public AldiTalkClient()
    {
        _cookies = new CookieContainer();
        _http = CreateHttpClient(_cookies);
    }

    public async Task<bool> LoginAsync(string phone, string password, CancellationToken cancellationToken)
    {
        ResetSession();
        try
        {
            using var step1Content = new ByteArrayContent(Array.Empty<byte>());
            step1Content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
            using var step1 = CreateRequest(HttpMethod.Post, AuthConfig.AuthEndpoint, step1Content);
            step1.Headers.TryAddWithoutValidation("Accept-Language", "de-DE,de;q=0.9");
            step1.Headers.TryAddWithoutValidation("Accept", "application/json");

            using var step1Response = await _http.SendAsync(
                step1, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
            var step1Body = await step1Response.Content.ReadAsStringAsync(cancellationToken);
            if (!step1Response.IsSuccessStatusCode)
            {
                return false;
            }

            var data = JsonNode.Parse(step1Body)?.AsObject()
                ?? throw new InvalidOperationException("Ungültige Login-Antwort");
            var callbacks = data["callbacks"]?.AsArray()
                ?? throw new InvalidOperationException("Login-Callbacks fehlen");
            var powMessage = ExtractPowMessage(callbacks);
            var workMatch = Regex.Match(powMessage, @"var work = ""([^""]+)""");
            var difficultyMatch = Regex.Match(powMessage, @"var difficulty = ([0-9]+)");
            if (!workMatch.Success || !difficultyMatch.Success)
            {
                throw new InvalidOperationException("PoW-Parameter nicht gefunden");
            }

            var nonce = SolvePow(workMatch.Groups[1].Value, int.Parse(difficultyMatch.Groups[1].Value), cancellationToken);
            FillCredentials(callbacks, nonce, phone, password);

            using var step2Content = new StringContent(data.ToJsonString(), Encoding.UTF8, "application/json");
            using var step2 = CreateRequest(HttpMethod.Post, AuthConfig.AuthEndpoint, step2Content);
            step2.Headers.TryAddWithoutValidation("Accept", "application/json");
            using var step2Response = await _http.SendAsync(
                step2, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
            if (!step2Response.IsSuccessStatusCode)
            {
                return false;
            }

            var tokenRoot = JsonNode.Parse(await step2Response.Content.ReadAsStringAsync(cancellationToken))?.AsObject();
            var tokenId = tokenRoot?["tokenId"]?.GetValue<string>();
            if (string.IsNullOrWhiteSpace(tokenId))
            {
                return false;
            }

            _cookies.Add(new Uri(AuthConfig.AuthEndpoint), new Cookie(
                "iPlanetDirectoryPro", tokenId, "/", AuthConfig.AuthHost));

            var pkce = CreatePkce();
            var state = Guid.NewGuid().ToString("N");
            var oauthNonce = Guid.NewGuid().ToString("N");
            var authUrl = BuildUrl(AuthConfig.Auth + "/signin/oauth2/authorize", new Dictionary<string, string>
            {
                ["client_id"] = AuthConfig.ClientId,
                ["response_type"] = "code",
                ["scope"] = "openid",
                ["redirect_uri"] = AuthConfig.RedirectUri,
                ["code_challenge"] = pkce.Challenge,
                ["code_challenge_method"] = "S256",
                ["nonce"] = oauthNonce,
                ["state"] = state,
                ["ui_locales"] = "de",
                ["acr_values"] = "password",
                ["prompt"] = "none",
                ["realm"] = "/alditalk"
            });

            using var authorize = CreateRequest(HttpMethod.Get, authUrl);
            using var authorizeResponse = await _http.SendAsync(
                authorize, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
            var location = GetLocation(authorizeResponse);
            if (string.IsNullOrWhiteSpace(location))
            {
                return false;
            }

            var nextUrl = location;
            var baseUrl = authUrl;
            for (var hop = 0; hop < 8 && !string.IsNullOrWhiteSpace(nextUrl); hop++)
            {
                var resolved = ResolveUrl(nextUrl, baseUrl);
                using var hopRequest = CreateRequest(HttpMethod.Get, resolved);
                using var hopResponse = await _http.SendAsync(
                    hopRequest, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
                var nextLocation = GetLocation(hopResponse);
                if ((int)hopResponse.StatusCode is >= 301 and <= 308)
                {
                    if (string.IsNullOrWhiteSpace(nextLocation)) return false;
                    nextUrl = nextLocation;
                    baseUrl = resolved;
                }
                else
                {
                    break;
                }
            }

            return true;
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch
        {
            return false;
        }
    }

    public async Task<string?> ResolveContractIdAsync(string msisdn, CancellationToken cancellationToken)
    {
        var url = AuthConfig.Portal + "/scs/bff/scs-207-customer-master-data-bff/customer-master-data/v1/navigation-list";
        using var request = CreateBffRequest(HttpMethod.Get, url);
        using var response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        if (!response.IsSuccessStatusCode) return null;

        using var document = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancellationToken));
        if (!document.RootElement.TryGetProperty("userDetails", out var details) ||
            !details.TryGetProperty("subscriptions", out var subscriptions) ||
            subscriptions.ValueKind != JsonValueKind.Array || subscriptions.GetArrayLength() == 0)
        {
            return null;
        }

        string? fallback = null;
        foreach (var subscription in subscriptions.EnumerateArray())
        {
            var contractId = GetString(subscription, "contractId");
            fallback ??= contractId;
            if (GetString(subscription, "msisdn") == msisdn && !string.IsNullOrWhiteSpace(contractId))
            {
                return contractId;
            }
        }
        return fallback;
    }

    public async Task<DataStatus?> GetRemainingDataAsync(string contractId, CancellationToken cancellationToken)
    {
        var url = AuthConfig.Portal + "/scs/bff/scs-209-selfcare-dashboard-bff/selfcare-dashboard/v1/offers?contractId="
            + Uri.EscapeDataString(contractId);
        using var request = CreateBffRequest(HttpMethod.Get, url);
        using var response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        if (!response.IsSuccessStatusCode) return null;

        using var document = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancellationToken));
        if (!document.RootElement.TryGetProperty("subscribedOffers", out var offers) ||
            offers.ValueKind != JsonValueKind.Array || offers.GetArrayLength() == 0)
        {
            return null;
        }

        var offer = offers[0];
        if (!offer.TryGetProperty("pack", out var pack) || pack.ValueKind != JsonValueKind.Array)
        {
            return null;
        }

        long remainingKb = 0;
        foreach (var item in pack.EnumerateArray())
        {
            if (GetString(item, "balanceAttributeReference") == "dataGrantAmount")
            {
                remainingKb = GetLong(item, "allocated") - GetLong(item, "used");
            }
        }

        return new DataStatus(
            remainingKb / 1024.0,
            GetString(offer, "offerId") ?? string.Empty,
            GetString(offer, "subscriptionId") ?? string.Empty,
            GetString(offer, "resourceId") ?? string.Empty,
            GetString(offer, "onDemandAmountValueUid") ?? string.Empty,
            GetString(offer, "refillThresholdValueUid") ?? string.Empty);
    }

    public async Task<BookingResult> Book1GbAsync(DataStatus status, CancellationToken cancellationToken)
    {
        try
        {
            var body = new JsonObject
            {
                ["offerId"] = status.OfferId,
                ["subscriptionId"] = status.SubscriptionId,
                ["updateOfferResourceID"] = status.ResourceId,
                ["amount"] = status.OnDemandAmount,
                ["refillThresholdValue"] = status.RefillThreshold
            };
            var url = AuthConfig.Portal + "/scs/bff/scs-209-selfcare-dashboard-bff/selfcare-dashboard/v1/offer/updateUnlimited";
            using var content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json");
            using var request = CreateBffRequest(HttpMethod.Post, url, content);
            using var response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
            var responseBody = await response.Content.ReadAsStringAsync(cancellationToken);
            using var document = JsonDocument.Parse(string.IsNullOrWhiteSpace(responseBody) ? "{}" : responseBody);
            var isUpdated = document.RootElement.TryGetProperty("isUpdated", out var updated) &&
                            updated.ValueKind == JsonValueKind.True;
            return new BookingResult(response.IsSuccessStatusCode && isUpdated, isUpdated,
                (int)response.StatusCode, responseBody);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex)
        {
            return new BookingResult(false, false, -1, ex.Message);
        }
    }

    private HttpClient CreateHttpClient(CookieContainer cookies)
    {
        var handler = new HttpClientHandler
        {
            CookieContainer = cookies,
            UseCookies = true,
            AllowAutoRedirect = false,
            AutomaticDecompression = DecompressionMethods.All,
            MaxConnectionsPerServer = 2
        };
        return new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(30) };
    }

    private void ResetSession()
    {
        _http.Dispose();
        _cookies = new CookieContainer();
        _http = CreateHttpClient(_cookies);
    }

    private HttpRequestMessage CreateRequest(HttpMethod method, string url, HttpContent? content = null)
    {
        var request = new HttpRequestMessage(method, url) { Content = content };
        request.Headers.TryAddWithoutValidation("User-Agent", AuthConfig.UserAgent);
        return request;
    }

    private HttpRequestMessage CreateBffRequest(HttpMethod method, string url, HttpContent? content = null)
    {
        var request = CreateRequest(method, url, content);
        request.Headers.TryAddWithoutValidation("Accept", "application/json, text/plain, */*");
        request.Headers.TryAddWithoutValidation("Referer", AuthConfig.Portal + "/portal/auth/uebersicht/");
        request.Headers.TryAddWithoutValidation("X-CORRELATION-ID", "C_" + Guid.NewGuid().ToString());
        request.Headers.TryAddWithoutValidation("X-TRANSACTION-ID", "T_" + Guid.NewGuid().ToString());
        return request;
    }

    private static string ExtractPowMessage(JsonArray callbacks)
    {
        foreach (var node in callbacks)
        {
            if (node is not JsonObject callback || callback["type"]?.GetValue<string>() != "TextOutputCallback") continue;
            if (callback["output"] is not JsonArray outputs) continue;
            foreach (var output in outputs)
            {
                if (output is JsonObject item && item["name"]?.GetValue<string>() == "message")
                {
                    return item["value"]?.GetValue<string>() ?? string.Empty;
                }
            }
        }
        return string.Empty;
    }

    private static void FillCredentials(JsonArray callbacks, int nonce, string phone, string password)
    {
        foreach (var node in callbacks)
        {
            if (node is not JsonObject callback || callback["input"] is not JsonArray inputs) continue;
            foreach (var inputNode in inputs)
            {
                if (inputNode is not JsonObject input) continue;
                switch (input["name"]?.GetValue<string>())
                {
                    case "IDToken1": input["value"] = nonce.ToString(); break;
                    case "IDToken3": input["value"] = phone; break;
                    case "IDToken4": input["value"] = password; break;
                    case "IDToken5": input["value"] = "2"; break;
                }
            }
        }
    }

    private static int SolvePow(string workUuid, int difficulty, CancellationToken cancellationToken)
    {
        difficulty = Math.Clamp(difficulty, 1, 40);
        for (var nonce = 0; nonce <= 10_000_000; nonce++)
        {
            if ((nonce & 4095) == 0) cancellationToken.ThrowIfCancellationRequested();
            var hash = SHA1.HashData(Encoding.UTF8.GetBytes(workUuid + nonce));
            var valid = true;
            for (var index = 0; index < difficulty; index++)
            {
                var nibble = (hash[index / 2] >> (index % 2 == 0 ? 4 : 0)) & 0x0F;
                if (nibble != 0) { valid = false; break; }
            }
            if (valid) return nonce;
        }
        throw new InvalidOperationException("PoW nicht gelöst");
    }

    private static (string Verifier, string Challenge) CreatePkce()
    {
        var verifier = Base64Url(RandomNumberGenerator.GetBytes(32));
        var challenge = Base64Url(SHA256.HashData(Encoding.UTF8.GetBytes(verifier)));
        return (verifier, challenge);
    }

    private static string Base64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static string BuildUrl(string baseUrl, IReadOnlyDictionary<string, string> parameters)
    {
        return baseUrl + "?" + string.Join("&", parameters.Select(x =>
            Uri.EscapeDataString(x.Key) + "=" + Uri.EscapeDataString(x.Value)));
    }

    private static string? GetLocation(HttpResponseMessage response)
    {
        return response.Headers.Location?.ToString() ??
               (response.Headers.TryGetValues("Location", out var values) ? values.FirstOrDefault() : null);
    }

    private static string ResolveUrl(string location, string baseUrl)
    {
        if (Uri.TryCreate(location, UriKind.Absolute, out var absolute) &&
            (absolute.Scheme == Uri.UriSchemeHttps || absolute.Scheme == Uri.UriSchemeHttp))
        {
            return absolute.ToString();
        }
        if (location.StartsWith("//", StringComparison.Ordinal))
        {
            var baseUri = new Uri(baseUrl);
            return $"{baseUri.Scheme}://{baseUri.Host}/{location[2..]}";
        }
        return new Uri(new Uri(baseUrl), location).ToString();
    }

    private static string? GetString(JsonElement element, string property)
    {
        if (!element.TryGetProperty(property, out var value)) return null;
        return value.ValueKind == JsonValueKind.String ? value.GetString() : value.GetRawText();
    }

    private static long GetLong(JsonElement element, string property)
    {
        if (!element.TryGetProperty(property, out var value)) return 0;
        if (value.ValueKind == JsonValueKind.Number && value.TryGetInt64(out var number)) return number;
        return long.TryParse(value.GetString(), out var parsed) ? parsed : 0;
    }

    public void Dispose()
    {
        _http.Dispose();
    }
}
