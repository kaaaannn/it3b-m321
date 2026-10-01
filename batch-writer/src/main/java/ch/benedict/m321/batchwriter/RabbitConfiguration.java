package ch.benedict.m321.batchwriter;

import java.util.HashMap;
import java.util.Map;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Legt fest, wie der batch-writer mit RabbitMQ spricht: welche Queues es gibt, woran sie
 * haengen, und wie gross die Pakete sind, die der Dienst zu schreiben bekommt.
 *
 * Spring legt Exchange, Queues und Bindung beim Start automatisch an, falls sie fehlen.
 * Man muss in der Management-Oberflaeche nichts klicken, und der Dienst laeuft auch dann
 * hoch, wenn er vor dem chat-service startet.
 */
@Configuration
public class RabbitConfiguration {

    /** Derselbe Exchange, auf den der chat-service publiziert. */
    public static final String EXCHANGE_NAME = "chat.messages";

    /** Unsere Queue. Aus ihr liest ausschliesslich der batch-writer. */
    public static final String PERSIST_QUEUE_NAME = "chat.persist";

    /** Endstation fuer Nachrichten, die sich nie schreiben lassen. */
    public static final String DEAD_LETTER_QUEUE_NAME = "chat.dlq";

    private final int batchSize;
    private final long batchTimeoutMs;

    /**
     * Die zwei Werte steuern die Buendelung und kommen aus der Umgebung, damit man sie
     * ohne neues Bauen aendern kann (siehe .env.example).
     */
    public RabbitConfiguration(
            @Value("${batch-writer.batch-size}") int batchSize,
            @Value("${batch-writer.batch-timeout-ms}") long batchTimeoutMs) {
        this.batchSize = batchSize;
        this.batchTimeoutMs = batchTimeoutMs;
    }

    /**
     * Meldet den Fanout-Exchange an. Ein Fanout-Exchange gibt jede Nachricht an ALLE
     * Queues weiter, die an ihm haengen, und schaut den Routing-Key nicht an. Deshalb
     * bekommt der Schreibweg eine Kopie und der Live-Weg spaeter eine eigene, ohne dass
     * einer vom anderen weiss.
     *
     * Die zwei Wahrheitswerte heissen: dauerhaft (uebersteht einen Neustart des Brokers)
     * und nicht selbstloeschend (bleibt, auch wenn gerade keine Queue dranhaengt).
     */
    @Bean
    public FanoutExchange chatExchange() {
        return new FanoutExchange(EXCHANGE_NAME, true, false);
    }

    /**
     * Die Queue, aus der dieser Dienst liest. Hier darf nichts verloren gehen, deshalb
     * ist sie dauerhaft und als Quorum-Queue angelegt.
     */
    @Bean
    public Queue persistQueue() {
        Map<String, Object> arguments = new HashMap<>();

        // Quorum-Queue: der Broker haelt sie mehrfach vor. Fuer den Unterricht laeuft nur
        // ein Knoten, aber die Art der Queue ist die, die man fuer wichtige Daten nimmt.
        arguments.put("x-queue-type", "quorum");

        // Wie oft eine Nachricht erneut zugestellt werden darf, bevor sie in die DLQ geht.
        // Die Zahl haengt mit der Wartepause nach einem Datenbankausfall zusammen: bei
        // fuenf Sekunden Pause und fuenfzehn Sekunden Ausfall sind es etwa drei Versuche,
        // also weit unter dieser Grenze. Wer die Pause verkuerzt, muss hier erhoehen.
        arguments.put("x-delivery-limit", 20);

        // Wohin eine Nachricht geht, welche die Grenze oben reisst. Der leere Exchange-Name
        // ist der Standard-Exchange: dort gilt der Routing-Key als Queue-Name.
        arguments.put("x-dead-letter-exchange", "");
        arguments.put("x-dead-letter-routing-key", DEAD_LETTER_QUEUE_NAME);

        boolean durable = true;
        boolean exclusive = false;
        boolean autoDelete = false;
        return new Queue(PERSIST_QUEUE_NAME, durable, exclusive, autoDelete, arguments);
    }

    /**
     * Die Queue fuer dauerhaft kaputte Nachrichten. Hier gibt es absichtlich keine
     * Laengenbegrenzung und keine Ablaufzeit: was hier liegt, soll man in Ruhe anschauen
     * koennen, statt dass es still verschwindet.
     */
    @Bean
    public Queue deadLetterQueue() {
        boolean durable = true;
        boolean exclusive = false;
        boolean autoDelete = false;
        return new Queue(DEAD_LETTER_QUEUE_NAME, durable, exclusive, autoDelete);
    }

    /**
     * Haengt die Queue an den Exchange. Ohne diese Bindung wuerde der Exchange die
     * Nachrichten ins Nichts verteilen, denn ein Exchange speichert selbst nichts.
     */
    @Bean
    public Binding persistBinding(Queue persistQueue, FanoutExchange chatExchange) {
        return BindingBuilder.bind(persistQueue).to(chatExchange);
    }

    /**
     * Baut den Hoer-Behaelter so um, dass die Listener-Methode eine LISTE von Nachrichten
     * bekommt statt einzelne. Das ist der Kern der Buendelung.
     *
     * Die vier Einstellungen zusammen ergeben die Regel aus der Spezifikation: ein Paket
     * wird geschrieben, sobald es voll ist ODER das Zeitlimit abgelaufen ist.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory batchListenerContainerFactory(
            ConnectionFactory connectionFactory) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);

        // Die Methode bekommt eine Liste.
        factory.setBatchListener(true);

        // Der Behaelter sammelt die Nachrichten selbst zu Paketen.
        factory.setConsumerBatchEnabled(true);

        // Hoechstens so viele Nachrichten pro Paket.
        factory.setBatchSize(batchSize);

        // Kommt innerhalb dieser Zeit nichts Neues, wird das unvolle Paket geschrieben.
        factory.setReceiveTimeout(batchTimeoutMs);

        // Der Vorabruf muss mindestens so gross sein wie die Paketgroesse, sonst haelt
        // der Broker zu wenige Nachrichten bereit und ein Paket wird nie voll.
        factory.setPrefetchCount(batchSize);

        // AUTO heisst hier NICHT "sofort bestaetigen", sondern: bestaetige, wenn die
        // Listener-Methode ohne Ausnahme zurueckkommt. Da die Methode erst nach dem
        // Commit zurueckkommt, wird also erst nach dem Commit bestaetigt. Wirft sie eine
        // Ausnahme, wird nicht bestaetigt und der Broker stellt das Paket erneut zu.
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);

        // Abgelehnte Nachrichten kommen zurueck in die Queue, statt verworfen zu werden.
        factory.setDefaultRequeueRejected(true);

        return factory;
    }
}
