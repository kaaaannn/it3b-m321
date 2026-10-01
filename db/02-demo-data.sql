-- Ein Raum mit zwei Mitgliedern zum Ausprobieren.
-- Die feste UUID des Raums steht auch in der Swagger-Doku als Beispiel, damit man
-- beim Senden nicht jedes Mal eine neue erfinden muss.
--
-- Demo-Nachrichten gibt es hier absichtlich keine mehr. Die Tabelle message legt der
-- batch-writer beim Start an, und zu diesem Zeitpunkt laeuft dieses Skript schon
-- laengst. Nachrichten entstehen ab jetzt auf dem echten Weg: senden, Queue, schreiben.

INSERT INTO room (id, name, created_by, created_at) VALUES
  ('11111111-1111-1111-1111-111111111111', 'Allgemein', 'lehrperson', now());

INSERT INTO room_member (room_id, username, invited_by, joined_at) VALUES
  ('11111111-1111-1111-1111-111111111111', 'lehrperson', 'lehrperson', now()),
  ('11111111-1111-1111-1111-111111111111', 'lernende1',  'lehrperson', now());
