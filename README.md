# AT Panther – ALDI Talk Auto-Refill

Android Foreground-Service, der das ALDI Talk Datenvolumen überwacht und automatisch 1 GB nachbucht, wenn der Restbestand unter die eingestellte Schwelle fällt.

## Features
- 🔐 Verschlüsselte Speicherung der Login-Daten (EncryptedSharedPreferences)
- 📡 Hintergrundüberwachung via Foreground Service (alle 60 Sekunden)
- ⚡ Automatische 1-GB-Nachbuchung unterhalb der Schwelle
- 📋 Verlauf/Log aller Prüfungen und Buchungen
- ⚙️ Einstellbare Schwelle (Standard: 250 MB) und Intervall

## Build
APK wird automatisch über GitHub Actions gebaut.
Repo: `jahdaganj00ki-eng/ATPantherAndroid`

## Gemeinsamer Monitor-Lock

Android und Windows verwenden über eine kostenlose Appwrite-Function eine gemeinsame Plattform-Auswahl. Mit „Android“ oder „Windows“ wird festgelegt, welche Version den Monitor ausführen darf. Die Lease wird per Heartbeat erneuert; die jeweils nicht ausgewählte Version startet nicht bzw. stoppt sicher.

Einrichtung: `ATPantherLockFunction/README.md` und `ATPantherWindows/LOCK-SETUP.md`.
