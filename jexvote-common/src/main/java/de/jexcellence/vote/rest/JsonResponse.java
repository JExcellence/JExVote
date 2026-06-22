package de.jexcellence.vote.rest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Utility for writing JSON responses to {@link HttpExchange} instances.
 * Uses a shared {@link Gson} instance with HTML escaping disabled.
 */
final class JsonResponse {

    static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private JsonResponse() {
        // utility
    }

    static void send(@NotNull HttpExchange exchange, int status, @NotNull Object body) throws IOException {
        byte[] json = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, json.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(json);
        }
    }

    static void sendError(@NotNull HttpExchange exchange, int status, @NotNull String message) throws IOException {
        send(exchange, status, new ErrorBody(message));
    }

    record ErrorBody(@NotNull String error) {}
}
