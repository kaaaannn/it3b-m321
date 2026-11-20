package ch.benedict.m321.chat.rabbit;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Legt fest, wie der chat-service mit RabbitMQ spricht.
 *
 * Ein Fanout-Exchange verteilt jede Nachricht an ALLE Queues, die an ihm haengen -
 * ohne auf einen Schluessel zu schauen. Genau das brauchen wir: eine Kopie fuer
 * jede chat-service-Instanz (Anzeige) und eine fuer den batch-service (Speichern).
 */
@Configuration
public class RabbitConfiguration {

    /** Name des Exchange, auf den jede neue Nachricht publiziert wird. */
    public static final String EXCHANGE_NAME = "chat.messages";

    /** Anfang des Namens jeder Live-Queue. Dahinter kommt eine zufaellige ID pro Instanz. */
    public static final String LIVE_QUEUE_PREFIX = "chat.live.";

    private final int liveQueueMaxLength;
    private final int liveQueueTtlMs;

    /**
     * Die zwei Grenzen der Live-Queue kommen aus der Umgebung, damit man sie ohne neues
     * Bauen aendern kann (siehe .env.example und docs/spec-live-zustellung.md, Abschnitt 4).
     */
    public RabbitConfiguration(
            @Value("${chat-service.live-queue.max-length}") int liveQueueMaxLength,
            @Value("${chat-service.live-queue.ttl-ms}") int liveQueueTtlMs) {
        this.liveQueueMaxLength = liveQueueMaxLength;
        this.liveQueueTtlMs = liveQueueTtlMs;
    }

    /**
     * Meldet den Exchange beim Broker an. Spring legt ihn beim Start automatisch
     * an, falls es ihn noch nicht gibt - man muss in der Management-UI nichts klicken.
     *
     * "durable" heisst: der Exchange ueberlebt einen Neustart des Brokers.
     * "autoDelete = false" heisst: er verschwindet nicht, wenn gerade keine Queue dranhaengt.
     */
    @Bean
    public FanoutExchange chatExchange() {
        return new FanoutExchange(EXCHANGE_NAME, true, false);
    }

    /**
     * Die eigene Queue DIESER Instanz fuer die Live-Anzeige. Jede Instanz bekommt eine
     * eigene, damit der Fanout-Exchange jeder Instanz eine Kopie jeder Nachricht gibt
     * (Spezifikation 3.2). Eine gemeinsame Queue wuerde die Nachrichten verteilen statt
     * verdoppeln - dann saehen die Clients an der anderen Instanz sie nie.
     *
     * Die Queue ist absichtlich kurzlebig und begrenzt (Spezifikation 3.3, 3.5 und 4):
     * Live-Nachrichten sind nach kurzer Zeit veraltet, und gespeichert sind sie ohnehin
     * ueber chat.persist. Deshalb keine Quorum-Queue und keine DLQ.
     */
    @Bean
    public Queue liveQueue() {
        // Die zufaellige ID macht den Namen pro Instanz eindeutig. Zwei Instanzen mit
        // demselben Namen wuerden sich eine Queue teilen - genau das darf nicht sein.
        String queueName = LIVE_QUEUE_PREFIX + UUID.randomUUID();

        Map<String, Object> arguments = new HashMap<>();

        // Hoechstens so viele Nachrichten liegen in der Queue. Kommt eine weitere dazu,
        // verwirft der Broker die AELTESTE. So kann eine haengende Instanz den Broker
        // nicht volllaufen lassen (Szenario S6).
        arguments.put("x-max-length", liveQueueMaxLength);

        // Nach dieser Zeit in Millisekunden verfaellt eine Nachricht, die niemand abgeholt hat.
        arguments.put("x-message-ttl", liveQueueTtlMs);

        // Nicht dauerhaft: nach einem Neustart des Brokers braucht es die Queue nicht mehr,
        // die Instanz legt beim Wiederverbinden eine neue an.
        boolean durable = false;

        // Exklusiv: die Queue gehoert genau der Verbindung dieser Instanz. Ist die
        // Verbindung weg - Instanz gestoppt, abgestuerzt oder nach einem Scale-down -
        // loescht RabbitMQ die Queue sofort. Verbindet sich Spring neu, legt es die Queue
        // unter demselben Namen wieder an.
        //
        // Warum nicht "false": RabbitMQ 4 lehnt Queues ab, die weder dauerhaft noch
        // exklusiv sind (Meldung "Feature transient_nonexcl_queues is deprecated").
        // Kurzlebige Queues muessen dort exklusiv sein.
        boolean exclusive = true;

        // Selbstloeschend: sobald der letzte Verbraucher weg ist, loescht RabbitMQ die
        // Queue. Nach einem Scale-down bleibt so keine "Leichen-Queue" am Exchange (S4).
        boolean autoDelete = true;

        return new Queue(queueName, durable, exclusive, autoDelete, arguments);
    }

    /**
     * Haengt die Live-Queue an den Exchange. Ohne diese Bindung wuerde der Exchange
     * dieser Instanz nie eine Kopie geben, denn ein Exchange speichert selbst nichts.
     */
    @Bean
    public Binding liveBinding(Queue liveQueue, FanoutExchange chatExchange) {
        return BindingBuilder.bind(liveQueue).to(chatExchange);
    }

    /**
     * Ein eigener Hoer-Behaelter fuer die Live-Queue, der den Rumpf UNVERAENDERT als
     * byte[] an den LiveMessageListener gibt.
     *
     * Es gibt ihn, weil Spring Boot sonst den JSON-Umwandler unten auch beim Empfangen
     * einsetzt. Der wuerde versuchen, das JSON selbst in den Parametertyp byte[] zu
     * verwandeln, und daran scheitern. Ohne eigenen Umwandler nimmt dieser Behaelter den
     * einfachsten: Er gibt die Bytes so weiter, wie sie aus der Queue kommen. Gelesen
     * wird das JSON dann im Listener, mit der Zielklasse im Code.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory liveListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        return factory;
    }

    /**
     * Wandelt Nachrichten beim Senden in JSON um. Ohne diese Bean wuerde Spring die
     * Objekte in ein Java-eigenes Binaerformat serialisieren - in der Management-UI
     * waere dann nur Zeichensalat zu sehen.
     *
     * Wir reichen absichtlich den ObjectMapper von Spring Boot herein: der ist so
     * eingestellt, dass Zeitpunkte als lesbares "2026-09-04T08:05:00Z" geschrieben
     * werden und nicht als blosse Zahl.
     */
    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
