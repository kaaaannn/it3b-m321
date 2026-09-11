package ch.benedict.m321.chat.message;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fachlogik rund um Nachrichten. Der Controller kennt nur diese Klasse, nicht das
 * Repository und nicht RabbitMQ - so bleibt die Weboberflaeche von der Technik
 * dahinter getrennt.
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    private final MessageRepository messageRepository;

    public MessageService(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    /**
     * Liefert den Verlauf eines Raums. Reines Lesen - hier wird nichts veraendert.
     */
    public List<Message> loadHistory(UUID roomId, int limit) {
        log.debug("Verlauf angefordert: Raum {}, hoechstens {} Nachrichten", roomId, limit);
        return messageRepository.findLatest(roomId, limit);
    }
}
