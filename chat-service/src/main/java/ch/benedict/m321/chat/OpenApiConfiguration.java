package ch.benedict.m321.chat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Setzt Titel, Version und Beschreibung der Swagger-Oberflaeche. Ohne diese Klasse
 * traegt die Dokumentation nur den Standardtitel "OpenAPI definition".
 */
@Configuration
public class OpenApiConfiguration {

    /**
     * Baut das Kopf-Objekt der API-Dokumentation. springdoc nimmt diese Bean und
     * ergaenzt sie um alles, was es in den Controllern findet.
     */
    @Bean
    public OpenAPI chatOpenApi() {
        Info info = new Info();
        info.setTitle("Chat-Service API");
        info.setVersion("0.1.0");
        info.setDescription(
                "REST-Schnittstelle des chat-service (Modul M321). "
                + "Neue Nachrichten werden entgegengenommen und an RabbitMQ weitergegeben. "
                + "Der Verlauf wird aus PostgreSQL gelesen. "
                + "Der chat-service schreibt selbst NICHT in die Nachrichtentabelle.");

        OpenAPI documentation = new OpenAPI();
        documentation.setInfo(info);
        return documentation;
    }
}
