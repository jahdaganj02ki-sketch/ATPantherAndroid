# Gemeinsame Monitor-Sperre

Diese Appwrite Function stellt eine gemeinsame Lease-Sperre für die Android- und Windows-Version bereit.

## Appwrite Free

Der kostenlose Appwrite-Plan reicht für zwei Geräte aus. Die Function verwendet eine Datenbank und wenige Lease-Aufrufe pro Minute.

## Function anlegen

1. Appwrite-Projekt erstellen.
2. Eine Function mit Runtime **Node.js 22** anlegen.
3. Den Inhalt dieses Ordners als Function-Code hochladen.
4. `npm install` ausführen bzw. die automatische Dependency-Installation aktivieren.
5. Die Function für anonyme/öffentliche Ausführung freigeben, da die Apps nur die Execution-API aufrufen.
6. Function-ID und Projekt-ID notieren.

## Datenbank

Eine Datenbank und Collection anlegen. Die Collection benötigt diese Attribute:

| Attribut | Typ | Pflicht | Standard |
|---|---|---:|---:|
| `deviceId` | String, mindestens 1 Zeichen | ja | – |
| `expiresAt` | Integer | ja | 0 |
| `updatedAt` | Integer | ja | 0 |
| `activePlatform` | String (`android` oder `windows`) | ja | `android` |

Die Function verwendet den SHA-256-Hash der Rufnummer als Dokument-ID. Die Dokument-ID besteht aus 64 Kleinbuchstaben/Ziffern.

## Function-Umgebungsvariablen

```text
APPWRITE_API_KEY=Server-Key mit Datenbankrechten
LOCK_DATABASE_ID=...
LOCK_COLLECTION_ID=...
LOCK_SHARED_SECRET=Ein langer zufälliger gemeinsamer Schlüssel
```

`APPWRITE_ENDPOINT`, `APPWRITE_PROJECT_ID` und `APPWRITE_FUNCTION_API_ENDPOINT` werden von Appwrite normalerweise automatisch bereitgestellt. Falls nicht, zusätzlich setzen:

```text
APPWRITE_ENDPOINT=https://cloud.appwrite.io/v1
APPWRITE_PROJECT_ID=...
```

## Client-Konfiguration

Dieselben drei öffentlichen Konfigurationswerte müssen in beide Clients eingetragen werden:

- Function-Execution-URL: `https://cloud.appwrite.io/v1/functions/<FUNCTION_ID>/executions`
- Project-ID
- `LOCK_SHARED_SECRET`

Die Apps senden niemals den Appwrite-API-Key. Der API-Key bleibt ausschließlich als Function-Secret auf Appwrite.

## Verhalten

- `select`: Aktive Plattform auf `android` oder `windows` setzen.
- `acquire`: Lease für 120 Sekunden auf der ausgewählten Plattform reservieren.
- `heartbeat`: Lease regelmäßig erneuern; eine andere Plattform wird abgewiesen.
- `release`: Lease beim normalen Stop freigeben.
- Bei ausbleibendem Heartbeat verfällt die Lease automatisch.
- Bei Plattformwechsel oder Lock-Verlust stoppt der nicht ausgewählte Monitor sicher.
