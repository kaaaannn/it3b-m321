package ch.benedict.m321.chat.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ch.benedict.m321.chat.message.Message;
import org.junit.jupiter.api.Test;

/**
 * Prueft die Regeln des Zwischenspeichers ohne Spring, ohne Broker und ohne Netz:
 * nur die Klasse ReplayBuffer und ein paar Nachrichten. Darum laeuft er in Millisekunden.
 */
class ReplayBufferTest {

    private static final UUID ROOM_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROOM_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /** Nach einer bekannten ID kommen genau die spaeteren Nachrichten, ohne die ID selbst. */
    @Test
    void afterReturnsOnlyTheMessagesAfterTheGivenIdInOrder() {
        ReplayBuffer buffer = new ReplayBuffer(10);
        Message first = newMessage(ROOM_A);
        Message second = newMessage(ROOM_A);
        Message third = newMessage(ROOM_A);
        buffer.add(first);
        buffer.add(second);
        buffer.add(third);

        ReplayResult result = buffer.after(ROOM_A, first.id().toString());

        assertThat(result.lastEventIdKnown()).isTrue();
        assertThat(result.missedMessages()).isEqualTo(List.of(second, third));
    }

    /** Die ID der neuesten Nachricht ist bekannt, verpasst ist dann nichts. */
    @Test
    void afterTheNewestIdNothingIsMissed() {
        ReplayBuffer buffer = new ReplayBuffer(10);
        Message only = newMessage(ROOM_A);
        buffer.add(only);

        ReplayResult result = buffer.after(ROOM_A, only.id().toString());

        assertThat(result.lastEventIdKnown()).isTrue();
        assertThat(result.missedMessages()).isEmpty();
    }

    /** Ist der Puffer voll, faellt die aelteste Nachricht raus - ihre ID ist dann unbekannt. */
    @Test
    void theOldestMessageIsDroppedWhenTheBufferIsFull() {
        ReplayBuffer buffer = new ReplayBuffer(2);
        Message oldest = newMessage(ROOM_A);
        Message middle = newMessage(ROOM_A);
        Message newest = newMessage(ROOM_A);
        buffer.add(oldest);
        buffer.add(middle);
        buffer.add(newest);

        ReplayResult result = buffer.after(ROOM_A, oldest.id().toString());

        assertThat(result.lastEventIdKnown()).isFalse();
        assertThat(result.missedMessages()).isEmpty();
    }

    /** Jeder Raum hat seinen eigenen Puffer: eine ID aus Raum A ist in Raum B unbekannt. */
    @Test
    void anIdFromAnotherRoomIsUnknown() {
        ReplayBuffer buffer = new ReplayBuffer(10);
        Message inRoomA = newMessage(ROOM_A);
        buffer.add(inRoomA);

        ReplayResult result = buffer.after(ROOM_B, inRoomA.id().toString());

        assertThat(result.lastEventIdKnown()).isFalse();
    }

    /** Baut eine Testnachricht mit zufaelliger ID fuer einen Raum. */
    private Message newMessage(UUID roomId) {
        UUID id = UUID.randomUUID();
        Instant sentAt = Instant.now();
        return new Message(id, roomId, "lernende1", "test", sentAt);
    }
}
