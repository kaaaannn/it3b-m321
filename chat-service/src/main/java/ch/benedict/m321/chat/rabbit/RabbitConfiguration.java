package ch.benedict.m321.chat.rabbit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
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
