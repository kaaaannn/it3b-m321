# M321 — Chat-App (Klasse IT3b)

Lernprojekt zum Modul **M321 Verteilte Systeme / Microservices**. Wir bauen gemeinsam eine
Chat-Anwendung aus mehreren Services, die über eine Message Queue miteinander reden und mit
docker-compose gestartet werden.

## Für Lernende: so startest du

1. Dieses Repository **forken** (Button «Fork» oben rechts).
2. Deinen Fork klonen:
   ```bash
   git clone https://github.com/<dein-benutzername>/it3b-m321.git
   cd it3b-m321
   ```
3. Voraussetzungen installieren: **Java 21**, **Maven**, **Docker Desktop**, **Git**.
4. Die Planung lesen (siehe unten) — erst verstehen, dann programmieren.

Alle Aufgaben werden in **deinem Fork** gelöst. Das Original-Repository bleibt die Referenz.

## Was gebaut wird

| Baustein | Technologie | Aufgabe |
|---|---|---|
| chat-service | Spring Boot 3, Java 21 | REST-API, liefert Nachrichten live per SSE, prüft das Login-Token |
| batch-writer | Spring Boot 3, Java 21 | Einziger Schreiber in die Datenbank, speichert Nachrichten gebündelt. In `PLANUNG.md` heisst dieser Dienst noch `batch-service`; gebaut ist er unter dem Namen aus dem Auftrag der Bewertung |
| gateway | nginx | Einziger nach aussen offener Port, Reverse Proxy |
| keycloak | Keycloak | Login (OIDC) |
| rabbitmq | RabbitMQ | Message Queue zwischen den Services |
| postgres | PostgreSQL | Speichert den Chat-Verlauf |
| Web-UI | React | Browser-Client |
| Desktop-UI | JavaFX | Zweiter Client gegen dieselbe API |

Alles unterhalb des Gateways läuft in einem internen Docker-Netzwerk und ist von aussen nicht
erreichbar.

## Dokumente

- [`PLANUNG.md`](PLANUNG.md) — Stack, Architektur, Nachrichtenfluss, Datenmodell, offene Punkte.
  Das ist die Grundlage für alles Weitere.
- [`docs/design/2026-08-28-chat-app-architektur.html`](docs/design/2026-08-28-chat-app-architektur.html)
  — grafische Fassung der Architekturdiagramme, lokal im Browser öffnen (funktioniert ohne Internet).
- [`docs/plan/2026-09-04-chat-service-bootstrap.md`](docs/plan/2026-09-04-chat-service-bootstrap.md)
  — Schritt-für-Schritt-Plan für den ersten Service: Projekt anlegen, Datenbank und Broker
  anbinden, Nachrichten lesen und senden. Jeder Schritt mit Test.
- [`docs/spec-batch-writer.md`](docs/spec-batch-writer.md) — Spezifikation des `batch-writer`:
  Vertrag mit der Queue, Verhalten in jedem Fehlerfall, Datenmodell, Abnahmekriterien mit Befehl.
- [`docs/plan-batch-writer.md`](docs/plan-batch-writer.md) — Umsetzungsplan des `batch-writer` in
  elf Schritten, jeder mit Test und eigenem Commit.
- [`CLAUDE.md`](CLAUDE.md) — Codestil-Regeln für dieses Projekt. Gelten auch für dich.
- `docs/skizze-architektur.heic` — die Handskizze aus dem Unterricht, von der die Planung ausgeht.

## Codestil, kurz

Der Massstab ist: **kann eine lernende Person jede Zeile vorlesen und sagen, was sie tut?**

- Eine Anweisung pro Zeile, Zwischenresultate in benannte Variablen.
- `for`-Schleife statt Stream, `if` statt verschachteltem Ternary.
- Sprechende Namen in ganzen Wörtern.
- Über jeder Methode ein bis zwei Sätze: was sie tut und warum es sie gibt.
- Kommentare auf Deutsch, als Erklärung an eine Mitlernende.

Die vollständigen Regeln stehen in [`CLAUDE.md`](CLAUDE.md).

## Stand

| Baustein | Stand | Was läuft |
|---|---|---|
| Planung und Dokumente | fertig | `PLANUNG.md`, Architekturdiagramme, Codestil-Regeln |
| `chat-service` | läuft | `POST /messages` und `POST /api/messages` nehmen Nachrichten an und publizieren auf den Fanout-Exchange. `GET /messages` liest den Verlauf. Swagger UI unter `/swagger-ui.html` |
| `batch-writer` | läuft | Hört auf `chat.persist`, schreibt gebündelt in PostgreSQL, hält Dubletten und Datenbankausfälle aus. Legt die Tabelle `message` selbst an |
| `postgres` | läuft | Tabellen `room`, `room_member` aus `db/`, Tabelle `message` vom `batch-writer` |
| `rabbitmq` | läuft | Fanout-Exchange `chat.messages`, Queues `chat.persist` und `chat.dlq` |
| `gateway` (nginx) | offen | Noch nicht gebaut |
| `keycloak` | offen | Noch nicht gebaut, Absender steht vorerst im Request-Body |
| SSE und Raumverwaltung | offen | Noch nicht gebaut |
| Web-UI, Desktop-UI | offen | Noch nicht gebaut |

## So startest du den Stack

```bash
cp .env.example .env
docker compose up -d --build
```

Kein Dienst veröffentlicht einen Port auf dem Rechner. Gemessen und geprüft wird deshalb **von
innen**, zum Beispiel so:

```bash
docker compose exec postgres psql -U chat -d chat -c "SELECT count(*) FROM message;"
docker compose exec rabbitmq rabbitmqctl list_queues name messages consumers
docker compose exec chat-service curl -s -X POST http://localhost:8080/messages -H "Content-Type: application/json" -d '{"roomId":"11111111-1111-1111-1111-111111111111","sender":"lernende1","text":"Hallo"}'
```

Die Tests laufen ohne den Stack, weil sie ihre eigene Datenbank und ihren eigenen Broker starten:

```bash
mvn clean test
```

### Umgebungsvariablen

Alle Werte stehen in `.env.example`. Die echte `.env` wird nie eingecheckt.

| Variable | Wofür |
|---|---|
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Datenbank und Zugang dazu |
| `RABBITMQ_DEFAULT_USER`, `RABBITMQ_DEFAULT_PASS` | Zugang zum Broker |
| `BATCH_WRITER_BATCH_SIZE` | Wie viele Nachrichten höchstens in ein Paket gehen |
| `BATCH_WRITER_BATCH_TIMEOUT_MS` | Wie lange auf ein volles Paket gewartet wird |
| `BATCH_WRITER_RETRY_BACKOFF_MS` | Pause nach einem Datenbankfehler, bevor erneut versucht wird |
