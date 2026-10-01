# Umsetzungsplan: batch-writer

Grundlage: `docs/spec-batch-writer.md`. Vorbild für die Form: der Bootstrap-Plan des
`chat-service` unter `docs/plan/2026-09-04-chat-service-bootstrap.md`.

**Regel für jeden Schritt:** Test zuerst schreiben, fehlschlagen lassen, dann umsetzen, dann
grün. Ein Schritt ist ein Commit mit einem Thema, Nachricht auf Deutsch.

**Reihenfolge in einem Satz:** Erst das Fundament, auf dem überhaupt gebaut und gemessen werden
kann, dann der Dienst von innen nach aussen, also Lesen vor Schreiben vor Zuhören vor Fehlerfall,
und zuletzt die Verpackung in Docker. Jeder Schritt ist für sich prüfbar, und kein Schritt braucht
einen späteren.

---

## Aufgabe 1: Eltern-POM und Umgebungsdatei

**Warum zuerst:** Szenario S1 verlangt `mvn clean test` im Wurzelverzeichnis. Ohne Eltern-POM gibt
es dort nichts zu bauen, und jeder weitere Schritt wäre nicht messbar. S2 verlangt einen frischen
Klon, der nur mit `.env.example` hochfährt, also muss die Vorlage vor dem ersten Dienst stehen.

**Dateien:** `pom.xml` (neu, Eltern mit Modulliste), `.env.example` (neu), `.gitignore` (ergänzt um
`.env`)

**Test:** `mvn clean test` im Wurzelverzeichnis läuft durch und führt die Tests des `chat-service`
aus. Vorher: Fehler, weil kein Projekt gefunden wird.

**Commit:** `build: Eltern-POM und .env.example, Zugangsdaten aus der Umgebung`

---

## Aufgabe 2: Die Tabelle `message` wechselt den Besitzer

**Warum hier:** Der `batch-writer` legt die Tabelle später beim Start selbst an (Spezifikation 4.2).
Solange das Init-Skript sie ebenfalls anlegt und die Demo-Daten Zeilen hineinschreiben, gibt es zwei
Quellen für dasselbe Schema. Das muss geklärt sein, bevor der Dienst existiert, sonst scheitert der
erste Start des Stacks am Init-Skript.

**Dateien:** `db/01-schema.sql` (nur noch `room` und `room_member`), `db/02-demo-data.sql` (nur noch
Raum und Mitglieder, keine Nachrichten)

**Test:** Volume löschen, Stack hochfahren, `\dt` zeigt `room` und `room_member`. `mvn clean test`
bleibt grün, weil die Tests des `chat-service` die Tabelle nicht brauchen.

**Commit:** `refactor(db): Tabelle message gehoert dem batch-writer, Init-Skript nur noch Raeume`

---

## Aufgabe 3: Modul `batch-writer` anlegen

**Warum hier:** Ab jetzt braucht jeder Test ein Modul, in dem er liegen kann. Der Dienst tut in
diesem Schritt noch nichts, aber er startet, und das ist die Grundlage für alles Weitere.

**Dateien:** `batch-writer/pom.xml`, `BatchWriterApplication.java`, `application.yml`,
`BatchWriterApplicationTest.java`, Modul im Eltern-POM eingetragen

**Test:** Der Kontext startet. Datenbank und Broker kommen aus Testcontainers, nicht aus dem
laufenden Stack. Vorher: Fehler, weil keine Startklasse existiert.

**Commit:** `feat(batch-writer): Modul anlegen, Dienst ohne Weboberflaeche startet`

---

## Aufgabe 4: Schema beim Start anlegen

**Warum hier:** Ohne Tabelle kann kein Schreibtest laufen. Und der Dienst muss aus jedem Zustand
des Volumes hochfahren, auch aus einem alten.

**Dateien:** `MessageSchema.java` (idempotentes `CREATE TABLE IF NOT EXISTS` und Index)

**Test:** Nach dem Start existieren Tabelle und Index. Der Test läuft zweimal hintereinander ohne
Fehler, das beweist die Wiederholbarkeit.

**Commit:** `feat(batch-writer): Tabelle message und Index beim Start anlegen`

---

## Aufgabe 5: Den Vertrag lesen

**Warum hier:** Lesen kommt vor Schreiben. Dieser Schritt entscheidet Szenario S5, denn hier wird
festgelegt, dass der Dienst ohne den Header `__TypeId__` auskommt.

**Dateien:** `IncomingMessage.java` (Datensatz), `MessagePayloadReader.java` (JSON zu Datensatz,
mit Prüfung der Pflichtfelder)

**Test:** Gültiges JSON wird gelesen. Kaputtes JSON wird abgelehnt. Fehlendes Pflichtfeld wird
abgelehnt. JSON ohne `__TypeId__` wird gelesen, das ist der Kern von S5.

**Commit:** `feat(batch-writer): Nachrichten aus JSON lesen, ohne Typ-Header des Absenders`

---

## Aufgabe 6: Ein Paket schreiben

**Warum hier:** Jetzt ist beides da, Tabelle und gelesener Datensatz. Dieser Schritt entscheidet
S4 (Bündelung) und die Dublettenfreiheit aus S5 und S6.

**Dateien:** `MessageWriter.java` (`batchUpdate` mit `ON CONFLICT (id) DO NOTHING`, eine
Transaktion pro Paket)

**Test:** Ein Paket mit mehreren Nachrichten erzeugt genauso viele Zeilen. **Dublettentest:**
dieselbe Nachricht zweimal geschrieben erzeugt genau eine Zeile. Ein Paket, in dem dieselbe
Nachricht zweimal vorkommt, erzeugt ebenfalls eine Zeile.

**Commit:** `feat(batch-writer): Paket mit einer Anweisung schreiben, Dubletten werden verworfen`

---

## Aufgabe 7: Zuhören und bündeln

**Warum hier:** Erst wenn Schreiben beweisbar funktioniert, lohnt es sich, eine Queue davorzuhängen.
Andernfalls sucht man einen Fehler an zwei Stellen gleichzeitig.

**Dateien:** `RabbitConfiguration.java` (Exchange, Queue `chat.persist`, Queue `chat.dlq`, Bindung,
Container-Factory mit Paketgrösse, Zeitlimit und Vorabruf), `MessageBatchListener.java`

**Test:** Mit echter Queue aus Testcontainers: tausend Nachrichten publizieren, alle stehen danach
in der Tabelle, die Queue ist leer. Zweiter Test: ein unvolles Paket wird nach dem Zeitlimit
geschrieben.

**Commit:** `feat(batch-writer): Queue chat.persist hoeren und in Paketen schreiben`

---

## Aufgabe 8: Die Fehlerfälle

**Warum hier:** Der Normalfall muss stehen, bevor man ihn absichtlich kaputt macht. Dieser Schritt
entscheidet S7 und den DLQ-Teil von S5.

**Dateien:** `MessageBatchListener.java` (ergänzt um Pause und erneuten Versuch bei
Verbindungsfehlern, Einzelweg bei Datenfehlern), `DeadLetterPublisher.java`

**Test:** **Ausfalltest:** Datenbank anhalten, Nachrichten senden, Datenbank starten, alles landet
ohne Neustart des Dienstes in der Tabelle. Zweiter Test: eine dauerhaft kaputte Nachricht landet in
`chat.dlq`, die gesunden Nachrichten desselben Pakets stehen in der Tabelle.

**Commit:** `feat(batch-writer): Datenbankausfall aussitzen, kaputte Nachricht in chat.dlq`

---

## Aufgabe 9: Beide Dienste in Docker, kein Port nach aussen

**Warum hier:** Jetzt tut der Dienst, was er soll. Erst jetzt lohnt sich die Verpackung, denn ein
Fehler im Code wäre im Container schwerer zu finden als auf dem Host.

**Dateien:** `chat-service/Dockerfile`, `batch-writer/Dockerfile`, `docker-compose.yml` (beide
Dienste ergänzt, `ports` durch `expose` ersetzt, Werte aus `.env`)

**Test:** `docker compose up -d --build` auf einem frischen Volume. Alle Dienste laufen, kein
Dienst zeigt eine Zuordnung auf den Host. Von innen gemessen: Nachrichten gehen durch und landen in
der Tabelle.

**Commit:** `feat(infra): chat-service und batch-writer im Compose, kein Port nach aussen`

---

## Aufgabe 10: Endpunkt auch unter `/messages`

**Warum hier:** Eine kleine, klar abgegrenzte Änderung am `chat-service`, die nichts am
`batch-writer` berührt. Sie steht am Ende, damit sie nicht mit dem neuen Dienst in einem Commit
landet.

**Dateien:** `MessageController.java` (zweiter Pfad), `MessageControllerTest.java` (Test je Pfad)

**Test:** `POST /messages` und `POST /api/messages` antworten beide mit `202`.

**Commit:** `feat(chat-service): Nachrichten auch unter /messages annehmen`

---

## Aufgabe 11: Abgabe fertigstellen

**Warum zuletzt:** Erst messen, wenn alles steht. Der Tag zeigt auf den Stand, der bewertet wird.

**Dateien:** `README.md` (Tabelle «Stand» nachgeführt, neue Variablen dokumentiert)

**Test:** Die acht Abnahmekriterien aus Spezifikation 5 der Reihe nach auf einem frischen Stack
durchmessen und die gemessenen Werte notieren.

**Commit:** `docs: Stand im README nachgefuehrt, Abgabe bewertung-1`

Danach: `git tag bewertung-1 && git push origin main --tags`

---

## Was dieser Plan nicht enthält

Keycloak, SSE, Raumverwaltung, `web-gateway`, `load-generator`, Archivierung und Statistik. Alles
davon ist laut Auftrag nicht Teil der Bewertung und bringt keine Punkte.
