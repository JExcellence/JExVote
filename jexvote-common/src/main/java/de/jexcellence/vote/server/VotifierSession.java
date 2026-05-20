package de.jexcellence.vote.server;

import java.util.UUID;

public class VotifierSession {

    private final String challenge;
    private ProtocolVersion version;

    public VotifierSession() {
        this.challenge = UUID.randomUUID().toString().replace("-", "");
        this.version = ProtocolVersion.UNKNOWN;
    }

    public String getChallenge() {
        return challenge;
    }

    public ProtocolVersion getVersion() {
        return version;
    }

    public void setVersion(ProtocolVersion version) {
        this.version = version;
    }

    public enum ProtocolVersion {
        UNKNOWN,
        V1,
        V2
    }
}
