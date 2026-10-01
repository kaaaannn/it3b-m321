-- Schema der Chat-App (Modul M321).
-- Wird vom Postgres-Image beim ERSTEN Start automatisch ausgefuehrt, in alphabetischer
-- Reihenfolge der Dateinamen. Danach nie wieder.
--
-- Achtung, hier fehlt die Tabelle message mit Absicht.
-- Sie gehoert dem batch-writer, weil er ihr einziger Schreiber ist, und er legt sie
-- bei jedem Start selbst an (siehe docs/spec-batch-writer.md, Abschnitt 4.2).
-- Grund: Init-Skripte laufen nur auf einem leeren Volume. Liegt schon ein Volume von
-- frueher, laeuft kein Skript mehr, und die Tabelle fehlte stillschweigend.

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
