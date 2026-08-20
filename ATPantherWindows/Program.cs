using System.Threading;

namespace ATPantherWindows;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        using var mutex = new Mutex(true, "ATPantherWindows.SingleInstance", out var createdNew);
        if (!createdNew)
        {
            return;
        }

        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm(args.Contains("--minimized", StringComparer.OrdinalIgnoreCase)));
    }
}
