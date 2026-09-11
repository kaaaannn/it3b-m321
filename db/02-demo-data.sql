-- Ein Raum und drei Nachrichten zum Ausprobieren.
-- Die feste UUID des Raums steht auch im Plan und in der Swagger-Doku als Beispiel.

INSERT INTO room (id, name, created_by, created_at) VALUES
  ('11111111-1111-1111-1111-111111111111', 'Allgemein', 'lehrperson', now());

INSERT INTO room_member (room_id, username, invited_by, joined_at) VALUES
  ('11111111-1111-1111-1111-111111111111', 'lehrperson', 'lehrperson', now()),
  ('11111111-1111-1111-1111-111111111111', 'lernende1',  'lehrperson', now());

INSERT INTO message (id, room_id, sender, text, sent_at) VALUES
  ('aaaaaaaa-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111',
   'lehrperson', 'Willkommen im Raum Allgemein.',     now() - interval '3 minutes'),
  ('aaaaaaaa-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111',
   'lernende1',  'Danke, der Verlauf wird gelesen.',  now() - interval '2 minutes'),
  ('aaaaaaaa-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111',
   'lehrperson', 'Genau, geschrieben wird spaeter.',  now() - interval '1 minute');
