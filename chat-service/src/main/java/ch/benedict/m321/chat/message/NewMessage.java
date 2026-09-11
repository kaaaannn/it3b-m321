package ch.benedict.m321.chat.message;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Was der Client beim Senden mitschickt. Bewusst NICHT dasselbe wie Message:
 * id und sentAt vergibt der Server, nicht der Client. Wuerde der Client sie
 * mitschicken duerfen, koennte er sich eine fremde Uhrzeit oder eine fremde ID aussuchen.
 */
public record NewMessage(

        @Schema(description = "In welchen Raum die Nachricht gehoert",
                example = "11111111-1111-1111-1111-111111111111")
        UUID roomId,

        @Schema(description = "PLATZHALTER bis Keycloak da ist. Danach kommt der Absender "
                            + "aus dem Token und dieses Feld faellt ersatzlos weg.",
                example = "lernende1")
        String sender,

        @Schema(description = "Der Nachrichtentext", example = "Hallo zusammen")
        String text) {
}
