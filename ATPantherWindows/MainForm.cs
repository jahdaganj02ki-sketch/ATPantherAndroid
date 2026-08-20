using System.Drawing;

namespace ATPantherWindows;

public sealed class MainForm : Form
{
    private readonly bool _startMinimized;
    private readonly SettingsStore _settingsStore = new();
    private readonly LogStore _logStore = new();
    private readonly MonitorController _monitor;

    private readonly TextBox _phoneBox = new();
    private readonly TextBox _passwordBox = new();
    private readonly NumericUpDown _thresholdBox = new();
    private readonly NumericUpDown _intervalBox = new();
    private readonly CheckBox _singleMonitor = new();
    private readonly CheckBox _startWithWindows = new();
    private readonly Label _statusLabel = new();
    private readonly Button _startButton = new();
    private readonly Button _stopButton = new();
    private readonly ListBox _logList = new();
    private readonly NotifyIcon _trayIcon = new();
    private readonly ContextMenuStrip _trayMenu = new();
    private bool _exitRequested;
    private string _deviceId = string.Empty;

    public MainForm(bool startMinimized)
    {
        _startMinimized = startMinimized;
        _monitor = new MonitorController(_logStore);
        _monitor.StatusChanged += Monitor_StatusChanged;

        ConfigureForm();
        BuildUi();
        BuildTrayMenu();
        LoadSettings();
        RefreshLogList();
        UpdateMonitorButtons();

        Resize += MainForm_Resize;
        FormClosing += MainForm_FormClosing;
        Shown += MainForm_Shown;
    }

    private void ConfigureForm()
    {
        Text = "AT Panther";
        Icon = SystemIcons.Application;
        StartPosition = FormStartPosition.CenterScreen;
        MinimumSize = new Size(660, 620);
        Size = new Size(780, 780);
        Font = new Font("Segoe UI", 9F);
        BackColor = Color.FromArgb(24, 24, 24);
        ForeColor = Color.White;
    }

    private void BuildUi()
    {
        var root = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            AutoScroll = true,
            Padding = new Padding(14),
            BackColor = BackColor
        };
        Controls.Add(root);

        var title = new Label
        {
            Text = "AT Panther für Windows",
            Font = new Font("Segoe UI", 18F, FontStyle.Bold),
            ForeColor = Color.White,
            AutoSize = true,
            Margin = new Padding(4, 0, 0, 12)
        };
        root.Controls.Add(title);

        root.Controls.Add(CreateCredentialsGroup());
        root.Controls.Add(CreateSettingsGroup());
        root.Controls.Add(CreateMonitorGroup());
        root.Controls.Add(CreateMaintenanceGroup());
        root.Controls.Add(CreateLogGroup());
    }

    private GroupBox CreateCredentialsGroup()
    {
        var group = CreateGroup("Login-Daten", 720, 112);
        var table = CreateTable(2);
        table.RowStyles.Add(new RowStyle(SizeType.Absolute, 38));
        table.RowStyles.Add(new RowStyle(SizeType.Absolute, 38));

        _phoneBox.Dock = DockStyle.Fill;
        _phoneBox.PlaceholderText = "Rufnummer, z. B. 491637805298";
        _passwordBox.Dock = DockStyle.Fill;
        _passwordBox.UseSystemPasswordChar = true;
        _passwordBox.PlaceholderText = "Passwort";
        table.Controls.Add(CreateLabel("Rufnummer:"), 0, 0);
        table.Controls.Add(_phoneBox, 1, 0);
        table.Controls.Add(CreateLabel("Passwort:"), 0, 1);
        table.Controls.Add(_passwordBox, 1, 1);
        group.Controls.Add(table);
        return group;
    }

    private GroupBox CreateSettingsGroup()
    {
        var group = CreateGroup("Einstellungen", 720, 188);
        var table = CreateTable(4);
        table.RowStyles.Add(new RowStyle(SizeType.Absolute, 38));
        table.RowStyles.Add(new RowStyle(SizeType.Absolute, 38));
        table.RowStyles.Add(new RowStyle(SizeType.Absolute, 38));
        table.RowStyles.Add(new RowStyle(SizeType.Absolute, 38));

        ConfigureNumeric(_thresholdBox, 0, 999999, 850, 0);
        ConfigureNumeric(_intervalBox, 60, 86400, 60, 0);
        _singleMonitor.Text = "Nur einen Monitor gleichzeitig zulassen";
        _singleMonitor.Checked = true;
        _singleMonitor.AutoSize = true;
        _startWithWindows.Text = "Mit Windows starten und im Tray beginnen";
        _startWithWindows.AutoSize = true;

        table.Controls.Add(CreateLabel("Schwelle (MB):"), 0, 0);
        table.Controls.Add(_thresholdBox, 1, 0);
        table.Controls.Add(CreateLabel("Intervall (Sek.):"), 0, 1);
        table.Controls.Add(_intervalBox, 1, 1);
        table.Controls.Add(_singleMonitor, 1, 2);
        table.Controls.Add(_startWithWindows, 1, 3);
        group.Controls.Add(table);
        return group;
    }

    private GroupBox CreateMonitorGroup()
    {
        var group = CreateGroup("Monitor", 720, 145);
        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 3,
            Padding = new Padding(8),
            BackColor = Color.FromArgb(32, 32, 32)
        };
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 32));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 40));

        _statusLabel.Text = "Gestoppt";
        _statusLabel.AutoSize = true;
        _statusLabel.ForeColor = Color.LightGray;
        layout.Controls.Add(_statusLabel, 0, 0);

        var buttons = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.LeftToRight,
            WrapContents = false
        };
        _startButton.Text = "Monitor starten";
        _startButton.Width = 150;
        _startButton.Click += async (_, _) => await StartMonitorAsync();
        _stopButton.Text = "Monitor stoppen";
        _stopButton.Width = 150;
        _stopButton.Click += async (_, _) => await StopMonitorAsync();
        buttons.Controls.Add(_startButton);
        buttons.Controls.Add(_stopButton);
        layout.Controls.Add(buttons, 0, 1);

        var hint = new Label
        {
            Text = "Das Tray-Icon bleibt aktiv, wenn dieses Fenster minimiert oder geschlossen wird.",
            AutoSize = true,
            ForeColor = Color.Gray
        };
        layout.Controls.Add(hint, 0, 2);
        group.Controls.Add(layout);
        return group;
    }

    private GroupBox CreateMaintenanceGroup()
    {
        var group = CreateGroup("Wartung", 720, 78);
        var panel = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            Padding = new Padding(8),
            WrapContents = false
        };
        var save = new Button { Text = "Speichern", Width = 120 };
        save.Click += (_, _) => SaveSettings(false);
        var export = new Button { Text = "Log exportieren", Width = 140 };
        export.Click += (_, _) => ExportLog();
        panel.Controls.Add(save);
        panel.Controls.Add(export);
        group.Controls.Add(panel);
        return group;
    }

    private GroupBox CreateLogGroup()
    {
        var group = CreateGroup("Verlauf (letzte 300 Einträge)", 720, 245);
        _logList.Dock = DockStyle.Fill;
        _logList.BackColor = Color.FromArgb(15, 15, 15);
        _logList.ForeColor = Color.White;
        _logList.Font = new Font(FontFamily.GenericMonospace, 9F);
        _logList.HorizontalScrollbar = true;
        group.Controls.Add(_logList);
        return group;
    }

    private void BuildTrayMenu()
    {
        _trayIcon.Icon = SystemIcons.Application;
        _trayIcon.Text = "AT Panther";
        _trayIcon.Visible = true;
        _trayIcon.ContextMenuStrip = _trayMenu;
        _trayIcon.DoubleClick += (_, _) => ShowMainWindow();
        _trayMenu.Items.Add("AT Panther anzeigen", null, (_, _) => ShowMainWindow());
        _trayMenu.Items.Add("Monitor starten", null, async (_, _) => await StartMonitorAsync());
        _trayMenu.Items.Add("Monitor stoppen", null, async (_, _) => await StopMonitorAsync());
        _trayMenu.Items.Add(new ToolStripSeparator());
        _trayMenu.Items.Add("Beenden", null, async (_, _) => await ExitApplicationAsync());
    }

    private void LoadSettings()
    {
        var settings = _settingsStore.Load();
        _phoneBox.Text = settings.Phone;
        _passwordBox.Text = settings.Password;
        _thresholdBox.Value = Clamp(settings.ThresholdMb, _thresholdBox.Minimum, _thresholdBox.Maximum);
        _intervalBox.Value = Clamp(settings.IntervalSec, _intervalBox.Minimum, _intervalBox.Maximum);
        _singleMonitor.Checked = settings.SingleMonitorEnabled;
        _startWithWindows.Checked = settings.StartWithWindows;
        _deviceId = settings.DeviceId;
    }

    private async void MainForm_Shown(object? sender, EventArgs e)
    {
        if (_startMinimized)
        {
            WindowState = FormWindowState.Minimized;
            Hide();
        }

        var settings = _settingsStore.Load();
        if (settings.MonitorEnabled && !string.IsNullOrWhiteSpace(settings.Phone) &&
            !string.IsNullOrWhiteSpace(settings.Password))
        {
            await StartMonitorAsync();
        }
    }

    private async Task StartMonitorAsync()
    {
        if (string.IsNullOrWhiteSpace(_phoneBox.Text) || string.IsNullOrWhiteSpace(_passwordBox.Text))
        {
            ShowMainWindow();
            MessageBox.Show(this, "Bitte Rufnummer und Passwort eingeben.", "AT Panther",
                MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        var settings = ReadSettings();
        settings.MonitorEnabled = true;
        SaveSettingsObject(settings);
        await _monitor.StartAsync(settings);
        UpdateMonitorButtons();
    }

    private async Task StopMonitorAsync()
    {
        await _monitor.StopAsync();
        var settings = ReadSettings();
        settings.MonitorEnabled = false;
        SaveSettingsObject(settings);
        SetStatus(new MonitorStatus("Gestoppt"));
        UpdateMonitorButtons();
    }

    private AppSettings ReadSettings()
    {
        return new AppSettings
        {
            Phone = _phoneBox.Text.Trim(),
            Password = _passwordBox.Text,
            ThresholdMb = (float)_thresholdBox.Value,
            IntervalSec = (int)_intervalBox.Value,
            MonitorEnabled = _monitor.IsRunning,
            SingleMonitorEnabled = _singleMonitor.Checked,
            DeviceId = _deviceId,
            StartWithWindows = _startWithWindows.Checked
        };
    }

    private void SaveSettings(bool monitorEnabled)
    {
        var settings = ReadSettings();
        settings.MonitorEnabled = monitorEnabled || _monitor.IsRunning;
        SaveSettingsObject(settings);
        MessageBox.Show(this, "Einstellungen gespeichert.", "AT Panther",
            MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    private void SaveSettingsObject(AppSettings settings)
    {
        try
        {
            _settingsStore.Save(settings);
            StartupManager.SetEnabled(settings.StartWithWindows);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"Einstellungen konnten nicht gespeichert werden:\n{ex.Message}",
                "AT Panther", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void ExportLog()
    {
        using var dialog = new SaveFileDialog
        {
            Filter = "Textdatei (*.txt)|*.txt|Alle Dateien (*.*)|*.*",
            FileName = $"at_panther_log_{DateTime.Now:yyyyMMdd_HHmmss}.txt",
            Title = "AT Panther Log exportieren"
        };
        if (dialog.ShowDialog(this) != DialogResult.OK) return;
        try
        {
            _logStore.Export(dialog.FileName);
            MessageBox.Show(this, "Log erfolgreich exportiert.", "AT Panther",
                MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"Export fehlgeschlagen:\n{ex.Message}", "AT Panther",
                MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void Monitor_StatusChanged(MonitorStatus status)
    {
        if (IsDisposed) return;
        if (InvokeRequired)
        {
            BeginInvoke(() => SetStatus(status));
        }
        else
        {
            SetStatus(status);
        }
    }

    private void SetStatus(MonitorStatus status)
    {
        _statusLabel.Text = status.RemainingMb >= 0
            ? $"{status.Text}  ({status.RemainingMb:F1} MB)"
            : status.Text;
        _statusLabel.ForeColor = status.IsError ? Color.OrangeRed : Color.LightGray;
        RefreshLogList();
        UpdateMonitorButtons();
        if (status.Notify)
        {
            _trayIcon.ShowBalloonTip(3500, "AT Panther", status.Text,
                status.IsError ? ToolTipIcon.Warning : ToolTipIcon.Info);
        }
    }

    private void RefreshLogList()
    {
        _logList.BeginUpdate();
        try
        {
            _logList.Items.Clear();
            foreach (var entry in _logStore.Recent())
            {
                var remaining = entry.RemainingMb >= 0 ? $" [{entry.RemainingMb:F1} MB]" : string.Empty;
                _logList.Items.Add($"{entry.Timestamp:dd.MM HH:mm:ss}  {entry.Type,-7} {entry.Message}{remaining}");
            }
        }
        finally
        {
            _logList.EndUpdate();
        }
    }

    private void UpdateMonitorButtons()
    {
        _startButton.Enabled = !_monitor.IsRunning;
        _stopButton.Enabled = _monitor.IsRunning;
    }

    private void MainForm_Resize(object? sender, EventArgs e)
    {
        if (WindowState != FormWindowState.Minimized) return;
        Hide();
        _trayIcon.ShowBalloonTip(2000, "AT Panther", "Läuft im Infobereich weiter.", ToolTipIcon.Info);
    }

    private void MainForm_FormClosing(object? sender, FormClosingEventArgs e)
    {
        if (_exitRequested || e.CloseReason != CloseReason.UserClosing) return;
        e.Cancel = true;
        Hide();
        _trayIcon.ShowBalloonTip(2000, "AT Panther", "Läuft im Infobereich weiter.", ToolTipIcon.Info);
    }

    private async Task ExitApplicationAsync()
    {
        _exitRequested = true;
        await _monitor.DisposeAsync();
        _trayIcon.Visible = false;
        _trayMenu.Dispose();
        _trayIcon.Dispose();
        Close();
    }

    private void ShowMainWindow()
    {
        Show();
        WindowState = FormWindowState.Normal;
        Activate();
    }

    private static GroupBox CreateGroup(string title, int width, int height) => new()
    {
        Text = title,
        Width = width,
        Height = height,
        ForeColor = Color.White,
        BackColor = Color.FromArgb(32, 32, 32),
        Padding = new Padding(8),
        Margin = new Padding(0, 0, 0, 10)
    };

    private static TableLayoutPanel CreateTable(int rows)
    {
        var table = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            RowCount = rows,
            Padding = new Padding(8),
            BackColor = Color.FromArgb(32, 32, 32)
        };
        table.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 135));
        table.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        return table;
    }

    private static Label CreateLabel(string text) => new()
    {
        Text = text,
        AutoSize = true,
        Anchor = AnchorStyles.Left,
        ForeColor = Color.LightGray,
        Margin = new Padding(0, 8, 0, 0)
    };

    private static void ConfigureNumeric(NumericUpDown control, decimal minimum, decimal maximum,
        decimal value, int decimals)
    {
        control.Minimum = minimum;
        control.Maximum = maximum;
        control.Value = value;
        control.DecimalPlaces = decimals;
        control.Dock = DockStyle.Left;
        control.Width = 140;
    }

    private static decimal Clamp(float value, decimal minimum, decimal maximum) =>
        Math.Min(maximum, Math.Max(minimum, (decimal)value));

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _monitor.DisposeAsync().AsTask().GetAwaiter().GetResult();
            _trayMenu.Dispose();
            _trayIcon.Visible = false;
            _trayIcon.Dispose();
        }
        base.Dispose(disposing);
    }
}
