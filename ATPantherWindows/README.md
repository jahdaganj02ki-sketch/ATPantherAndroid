# AT Panther für Windows

Native Windows-10-Version von AT Panther für den Toshiba Satellite C660.

## Zielsystem

- Windows 10 Pro 64 Bit
- x64 / Intel Core i3-2310M
- 8 GB DDR3-RAM
- Intel HD Graphics 3000
- Schlanke WinForms-Oberfläche ohne Electron oder Browser-Engine

## Funktionen

- ALDI-Talk-Login mit PKCE und PoW
- Verschlüsselte Speicherung der Zugangsdaten per Windows DPAPI
- Datenvolumen-Monitoring mit einstellbarer Schwelle und Intervall
- Automatische Buchung von 1 GB unterhalb der Schwelle
- Tray-Icon mit Starten, Stoppen, Anzeigen und Beenden
- Windows-10-Ballonbenachrichtigungen bei wichtigen Ereignissen
- Optionaler Start mit Windows
- Optionaler gemeinsamer Monitor-Lock mit der Android-Version
- Lokaler Verlauf, Bereinigung nach sieben Tagen und vollständiger TXT-Export
- Automatischer Wiederanmeldeversuch nach Sitzungsablauf

## Lokaler Build

Voraussetzung: .NET 8 SDK auf Windows.

```powershell
dotnet build ATPantherWindows.csproj -c Release -p:Platform=x64
dotnet publish ATPantherWindows.csproj -c Release -p:PublishProfile=win-x64
```

Die selbstständige Datei liegt danach unter:

```text
bin\Release\net8.0-windows10.0.17763.0\win-x64\publish\ATPantherWindows.exe
```

Die EXE benötigt keine separate .NET-Installation. Zugangsdaten werden pro Windows-Benutzer mit DPAPI verschlüsselt.

Für die geräteübergreifende Sperre siehe `LOCK-SETUP.md` und `../ATPantherLockFunction/README.md`. Der Schalter ist standardmäßig aktiv; ohne konfigurierte Sperre startet der Monitor sicherheitshalber nicht.

## GitHub Actions

Der Workflow `.github/workflows/windows.yml` baut bei Änderungen am Windows-Fork automatisch eine selbstständige `win-x64`-Version und stellt sie als Artifact `AT-Panther-Windows-x64` bereit.
