package de.jexcellence.vote.rest;

import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Immutable configuration for JExVote's embedded REST API server.
 * Parsed from the {@code api} section of {@code config.yml}.
 *
 * <p>Port defaults to 8096 to avoid clashing with JExOneblock's season
 * API (8095) when both plugins run on the same host. The HMAC scheme is
 * identical to JExOneblock's, so the SAME shared secret works for both.</p>
 *
 * @param enabled            whether the API server should start
 * @param port               TCP port to bind (default 8096)
 * @param secret             HMAC-SHA256 shared secret for request auth
 * @param corsOrigin         allowed origin for CORS ({@code *} = any)
 * @param rateLimitPerMinute max requests per IP per minute (0 = unlimited)
 * @param trustedProxies     IPs allowed to set X-Forwarded-For (empty = never trust)
 */
public record VoteRestApiConfig(
        boolean enabled,
        int port,
        @NotNull String secret,
        @NotNull String corsOrigin,
        int rateLimitPerMinute,
        @NotNull Set<String> trustedProxies
) {

    /** Sensible defaults when the YAML section is absent. */
    public static final VoteRestApiConfig DISABLED = new VoteRestApiConfig(false, 8096, "", "*", 60, Set.of());
}
