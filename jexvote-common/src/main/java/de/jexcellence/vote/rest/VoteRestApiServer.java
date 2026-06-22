package de.jexcellence.vote.rest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import de.jexcellence.vote.config.VoteConfig;
import de.jexcellence.vote.database.repository.VotePlayerRepository;
import de.jexcellence.vote.database.repository.VoteRecordRepository;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Embedded HTTP server exposing JExVote data as a JSON REST API, consumed
 * by the Mythblock web backend. Uses the JDK's built-in {@link HttpServer}
 * (zero external dependencies) and mirrors JExOneblock's season API:
 * HMAC-SHA256 auth over {@code <timestamp>.<path>} ({@code X-Signature} +
 * {@code X-Timestamp}, 5-minute replay window), per-IP rate limiting, and
 * CORS handling.
 *
 * <h2>Endpoints (all GET)</h2>
 * <ul>
 *   <li>{@code /api/v1/vote/sites} — configured vote listing sites</li>
 *   <li>{@code /api/v1/vote/player/{uuid}/cooldown} — per-site next-vote time</li>
 *   <li>{@code /api/v1/vote/player/{uuid}/stats} — aggregate vote stats</li>
 * </ul>
 *
 * <p>Player UUIDs are supplied by the backend from the linked Minecraft
 * account; this API only reads vote data the player generated (DSGVO
 * Art. 6 (1)(b) — contract performance for the vote-reward feature).</p>
 */
public final class VoteRestApiServer {

    private static final String SITES_ROUTE = "/api/v1/vote/sites";
    private static final String PLAYER_PREFIX = "/api/v1/vote/player/";
    private static final String THREAD_NAME = "jexvote-rest-api";

    private final VoteRestApiConfig config;
    private final VoteEndpoints endpoints;
    private final Logger logger;

    private @Nullable HmacAuthenticator authenticator;
    private @Nullable RateLimiter rateLimiter;
    private @Nullable HttpServer server;
    private @Nullable ScheduledExecutorService executor;

    public VoteRestApiServer(@NotNull VoteRestApiConfig config,
                             @NotNull VoteConfig voteConfig,
                             @NotNull VotePlayerRepository playerRepository,
                             @NotNull VoteRecordRepository recordRepository,
                             @NotNull Logger logger) {
        this.config = config;
        this.endpoints = new VoteEndpoints(voteConfig, playerRepository, recordRepository);
        this.logger = logger;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /** Start the HTTP server. No-op if disabled or the secret is empty. */
    public void start() {
        if (!config.enabled()) {
            logger.info("[rest-api] disabled in config — skipping");
            return;
        }
        if (config.secret().isBlank()) {
            logger.warning("[rest-api] api.secret is empty — refusing to start (authentication would be impossible)");
            return;
        }

        this.authenticator = new HmacAuthenticator(config.secret());
        this.rateLimiter = new RateLimiter(config.rateLimitPerMinute());

        try {
            server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        } catch (IOException ex) {
            final int port = config.port();
            logger.log(Level.SEVERE, ex, () -> String.format("[rest-api] failed to bind port %d", port));
            return;
        }

        executor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);

        Map<String, EndpointHandler> getRoutes = new LinkedHashMap<>();
        getRoutes.put(SITES_ROUTE, endpoints::handleSites);
        for (var entry : getRoutes.entrySet()) {
            server.createContext(entry.getKey(), wrapHandler(entry.getKey(), entry.getValue(), Set.of("GET")));
        }

        // Player sub-routes share a prefix; HttpServer matches the longest
        // prefix, so this context catches {uuid}/cooldown and {uuid}/stats.
        server.createContext(PLAYER_PREFIX, wrapHandler(
                PLAYER_PREFIX + "{uuid}/{sub}", this::dispatchPlayer, Set.of("GET")));

        executor.scheduleAtFixedRate(rateLimiter::evictStale, 2, 2, TimeUnit.MINUTES);

        server.start();
        final int port = config.port();
        logger.log(Level.INFO, () -> String.format("[rest-api] started on port %d", port));
    }

    /** Gracefully stop the server. Called from {@code onDisable()}. */
    public void stop() {
        if (server != null) {
            server.stop(1); // 1-second grace period
            server = null;
            logger.info("[rest-api] stopped");
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    // ── Request pipeline ──────────────────────────────────────────────────

    private void dispatchPlayer(@NotNull HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/cooldown")) {
            endpoints.handlePlayerCooldown(exchange);
        } else if (path.endsWith("/stats")) {
            endpoints.handlePlayerStats(exchange);
        } else {
            JsonResponse.sendError(exchange, 404, "Not found");
        }
    }

    private HttpHandler wrapHandler(@NotNull String route, @NotNull EndpointHandler handler,
                                    @NotNull Set<String> allowedMethods) {
        return exchange -> {
            try {
                applyCorsHeaders(exchange);
                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }

                String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
                if (!allowedMethods.contains(method)) {
                    JsonResponse.sendError(exchange, 405, "Method not allowed");
                    return;
                }

                String clientIp = extractClientIp(exchange);
                if (!rateLimiter.tryAcquire(clientIp)) {
                    exchange.getResponseHeaders().set("Retry-After", "60");
                    JsonResponse.sendError(exchange, 429, "Rate limit exceeded");
                    return;
                }

                HmacAuthenticator.AuthResult authResult = authenticate(exchange);
                if (authResult != HmacAuthenticator.AuthResult.OK) {
                    int status = authResult == HmacAuthenticator.AuthResult.TIMESTAMP_EXPIRED ? 401 : 403;
                    String message = switch (authResult) {
                        case TIMESTAMP_EXPIRED -> "Timestamp expired (max " + HmacAuthenticator.MAX_AGE_SECONDS + "s)";
                        case INVALID_SIGNATURE -> "Invalid signature";
                        default -> "Authentication failed";
                    };
                    JsonResponse.sendError(exchange, status, message);
                    return;
                }

                handler.handle(exchange);
            } catch (Exception ex) {
                logger.log(Level.WARNING, ex, () -> "[rest-api] unhandled error on " + route);
                try {
                    JsonResponse.sendError(exchange, 500, "Internal server error");
                } catch (IOException ignored) {
                    // Connection may already be closed.
                }
            } finally {
                exchange.close();
            }
        };
    }

    private HmacAuthenticator.AuthResult authenticate(@NotNull HttpExchange exchange) {
        String signature = exchange.getRequestHeaders().getFirst(HmacAuthenticator.HEADER_SIGNATURE);
        String timestampStr = exchange.getRequestHeaders().getFirst(HmacAuthenticator.HEADER_TIMESTAMP);

        if (signature == null || timestampStr == null) {
            return HmacAuthenticator.AuthResult.INVALID_SIGNATURE;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampStr);
        } catch (NumberFormatException ex) {
            return HmacAuthenticator.AuthResult.INVALID_SIGNATURE;
        }

        String path = exchange.getRequestURI().getPath();
        return authenticator.verify(signature, timestamp, path);
    }

    private void applyCorsHeaders(@NotNull HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", config.corsOrigin());
        headers.set("Access-Control-Allow-Methods", "GET, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "X-Signature, X-Timestamp, Content-Type");
        headers.set("Access-Control-Max-Age", "3600");
    }

    private static @NotNull String extractClientIp(@NotNull HttpExchange exchange) {
        String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    @FunctionalInterface
    private interface EndpointHandler {
        void handle(@NotNull HttpExchange exchange) throws IOException;
    }
}
