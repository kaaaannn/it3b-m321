package ch.benedict.m321.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Startpunkt des chat-service. Spring Boot faehrt von hier aus den eingebauten Webserver
 * hoch und durchsucht dieses Paket samt Unterpaketen nach Klassen, die es verwalten soll
 * (Controller, Service, Repository, Konfigurationen).
 */
@SpringBootApplication
public class ChatServiceApplication {

    /**
     * Uebergibt die Startklasse an Spring Boot. Alles Weitere - Webserver, Beans,
     * Konfigurationsdateien - erledigt der Aufruf darunter.
     */
    public static void main(String[] args) {
        SpringApplication.run(ChatServiceApplication.class, args);
    }
}
