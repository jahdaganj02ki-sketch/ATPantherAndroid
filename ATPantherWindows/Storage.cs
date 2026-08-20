using System.Runtime.InteropServices;
using System.Security;
using System.Text;
using System.Text.Json;
using Microsoft.Win32;

namespace ATPantherWindows;

internal static class AppPaths
{
    public static string DataDirectory => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "ATPantherWindows");

    public static string SettingsFile => Path.Combine(DataDirectory, "settings.dat");
    public static string LogFile => Path.Combine(DataDirectory, "logs.json");
}

internal static class Dpapi
{
    [StructLayout(LayoutKind.Sequential)]
    private struct DataBlob
    {
        public int Size;
        public IntPtr Data;
    }

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CryptProtectData(
        ref DataBlob dataIn,
        string? description,
        IntPtr optionalEntropy,
        IntPtr reserved,
        IntPtr prompt,
        int flags,
        ref DataBlob dataOut);

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CryptUnprotectData(
        ref DataBlob dataIn,
        IntPtr description,
        IntPtr optionalEntropy,
        IntPtr reserved,
        IntPtr prompt,
        int flags,
        ref DataBlob dataOut);

    [DllImport("kernel32.dll")]
    private static extern IntPtr LocalFree(IntPtr handle);

    public static byte[] Protect(byte[] data)
    {
        return Transform(data, protect: true);
    }

    public static byte[] Unprotect(byte[] data)
    {
        return Transform(data, protect: false);
    }

    private static byte[] Transform(byte[] data, bool protect)
    {
        var input = new DataBlob { Size = data.Length, Data = Marshal.AllocHGlobal(data.Length) };
        var output = new DataBlob();
        try
        {
            Marshal.Copy(data, 0, input.Data, data.Length);
            var success = protect
                ? CryptProtectData(ref input, "AT Panther settings", IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 0, ref output)
                : CryptUnprotectData(ref input, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 0, ref output);
            if (!success)
            {
                throw new SecurityException($"Windows DPAPI failed: {Marshal.GetLastWin32Error()}");
            }

            var result = new byte[output.Size];
            Marshal.Copy(output.Data, result, 0, output.Size);
            return result;
        }
        finally
        {
            if (input.Data != IntPtr.Zero) Marshal.FreeHGlobal(input.Data);
            if (output.Data != IntPtr.Zero) LocalFree(output.Data);
        }
    }
}

internal sealed class SettingsStore
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };

    public AppSettings Load()
    {
        try
        {
            if (!File.Exists(AppPaths.SettingsFile)) return new AppSettings();
            var encrypted = File.ReadAllBytes(AppPaths.SettingsFile);
            var json = Encoding.UTF8.GetString(Dpapi.Unprotect(encrypted));
            return JsonSerializer.Deserialize<AppSettings>(json) ?? new AppSettings();
        }
        catch
        {
            return new AppSettings();
        }
    }

    public void Save(AppSettings settings)
    {
        Directory.CreateDirectory(AppPaths.DataDirectory);
        var json = JsonSerializer.Serialize(settings, JsonOptions);
        var encrypted = Dpapi.Protect(Encoding.UTF8.GetBytes(json));
        var temporary = AppPaths.SettingsFile + ".tmp";
        File.WriteAllBytes(temporary, encrypted);
        File.Move(temporary, AppPaths.SettingsFile, true);
    }
}

internal sealed class LogStore
{
    private readonly object _sync = new();
    private List<LogEntry> _entries = new();

    public LogStore()
    {
        Load();
    }

    public IReadOnlyList<LogEntry> Recent(int maxEntries = 300)
    {
        lock (_sync)
        {
            return _entries
                .OrderByDescending(x => x.Timestamp)
                .Take(maxEntries)
                .ToList();
        }
    }

    public IReadOnlyList<LogEntry> AllChronological()
    {
        lock (_sync)
        {
            return _entries.OrderBy(x => x.Timestamp).ToList();
        }
    }

    public void Add(LogEntry entry)
    {
        lock (_sync)
        {
            _entries.Add(entry);
            var cutoff = DateTime.Now.AddDays(-7);
            _entries = _entries.Where(x => x.Timestamp >= cutoff).ToList();
            SaveLocked();
        }
    }

    public void Export(string path)
    {
        var entries = AllChronological();
        using var writer = new StreamWriter(path, false, new UTF8Encoding(false));
        writer.WriteLine("AT Panther – Windows Log-Export");
        writer.WriteLine($"Erstellt am: {DateTime.Now:dd.MM.yyyy HH:mm:ss}");
        writer.WriteLine($"Anzahl Einträge: {entries.Count}");
        writer.WriteLine(new string('─', 48));
        foreach (var entry in entries)
        {
            var remaining = entry.RemainingMb >= 0 ? $"  [{entry.RemainingMb:F1} MB]" : string.Empty;
            writer.WriteLine($"{entry.Timestamp:dd.MM.yyyy HH:mm:ss}  {entry.Type}  {entry.Message}{remaining}");
        }
    }

    private void Load()
    {
        try
        {
            if (!File.Exists(AppPaths.LogFile)) return;
            var json = File.ReadAllText(AppPaths.LogFile);
            _entries = JsonSerializer.Deserialize<List<LogEntry>>(json) ?? new List<LogEntry>();
            _entries = _entries.Where(x => x.Timestamp >= DateTime.Now.AddDays(-7)).ToList();
        }
        catch
        {
            _entries = new List<LogEntry>();
        }
    }

    private void SaveLocked()
    {
        Directory.CreateDirectory(AppPaths.DataDirectory);
        var temporary = AppPaths.LogFile + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(_entries));
        File.Move(temporary, AppPaths.LogFile, true);
    }
}

internal static class StartupManager
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "AT Panther";

    public static void SetEnabled(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey, true);
        if (key is null) return;
        if (enabled)
        {
            var executable = Environment.ProcessPath ?? Application.ExecutablePath;
            key.SetValue(ValueName, $"\"{executable}\" --minimized");
        }
        else
        {
            key.DeleteValue(ValueName, false);
        }
    }
}
