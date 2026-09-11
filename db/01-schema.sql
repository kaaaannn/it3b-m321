-- Schema der Chat-App (Modul M321).
-- Wird vom Postgres-Image beim ERSTEN Start automatisch ausgefuehrt.

CREATE TABLE room (
    id         UUID         PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL
);

-- Wer darf in welchem Raum mitlesen. Der Schluessel besteht aus beiden Spalten,
-- damit dieselbe Person nicht zweimal im selben Raum stehen kann.
CREATE TABLE room_member (
    room_id    UUID         NOT NULL REFERENCES room (id),
    username   VARCHAR(100) NOT NULL,
    invited_by VARCHAR(100) NOT NULL,
    joined_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (room_id, username)
);

CREATE TABLE message (
    -- Die ID kommt vom chat-service, NICHT von der Datenbank. Nur so kann der
    -- batch-service ein Paket gefahrlos wiederholen (ON CONFLICT DO NOTHING).
    id       UUID         PRIMARY KEY,
    room_id  UUID         NOT NULL REFERENCES room (id),
    sender   VARCHAR(100) NOT NULL,
    text     TEXT         NOT NULL,
    -- Zeitpunkt des SENDENS, gesetzt vom chat-service. Absichtlich kein DEFAULT now():
    -- sonst haetten alle 500 Nachrichten eines Pakets dieselbe Zeit.
    sent_at  TIMESTAMPTZ  NOT NULL
);

-- Der Verlauf wird immer pro Raum und nach Zeit sortiert gelesen.
-- Genau dafuer ist dieser Index da.
CREATE INDEX idx_message_room_time ON message (room_id, sent_at DESC);
