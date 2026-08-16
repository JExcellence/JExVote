package de.jexcellence.vote.server;

import de.jexcellence.vote.server.protocol.VotifierProtocolHandler;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyPair;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class VotifierServer {

    private final Logger logger;
    private final String host;
    private final int port;
    private final KeyPair keyPair;
    private final String token;
    private final Consumer<VoteResult> voteCallback;

    /** Max concurrent vote connections. Protects against connection floods. */
    private static final int MAX_CONNECTIONS = 16;

    private final AtomicReference<ServerSocket> serverSocket = new AtomicReference<>();
    private final AtomicReference<Thread> acceptThread = new AtomicReference<>();
    private final AtomicReference<ExecutorService> executor = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public VotifierServer(@NotNull Logger logger, @NotNull String host, int port,
                          @NotNull KeyPair keyPair, @NotNull String token,
                          @NotNull Consumer<VoteResult> voteCallback) {
        this.logger = logger;
        this.host = host;
        this.port = port;
        this.keyPair = keyPair;
        this.token = token;
        this.voteCallback = voteCallback;
    }

    public void start() throws IOException {
        InetSocketAddress address = host.isEmpty()
                ? new InetSocketAddress(port)
                : new InetSocketAddress(host, port);

        ServerSocket ss = new ServerSocket();
        try {
            ss.bind(address);
            serverSocket.set(ss);

            // Handler pool for processing accepted connections - separate from the accept loop.
            // AbortPolicy ensures the accept thread is NEVER used as a handler (prevents
            // slowloris from blocking the listener). Rejected connections are closed below.
            executor.set(new ThreadPoolExecutor(
                    2, MAX_CONNECTIONS, 60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(MAX_CONNECTIONS),
                    r -> {
                        Thread t = new Thread(r, "JExVote-Votifier-Handler");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy()));

            running.set(true);

            // Accept loop runs on its own dedicated thread - never competes with handlers.
            Thread at = new Thread(() -> {
                while (running.get()) {
                    try {
                        Socket client = serverSocket.get().accept();
                        client.setSoTimeout(5_000);
                        try {
                            executor.get().submit(new VotifierProtocolHandler(
                                    logger, client, keyPair, token, voteCallback));
                        } catch (RejectedExecutionException e) {
                            try { client.close(); } catch (IOException ignored) { }
                            logger.log(Level.FINE, () -> "Vote connection rejected - handler pool full");
                        }
                    } catch (IOException e) {
                        if (running.get()) {
                            logger.log(Level.WARNING, "Error accepting vote connection", e);
                        }
                    }
                }
            }, "JExVote-Votifier-Accept");
            at.setDaemon(true);
            at.start();
            acceptThread.set(at);

            logger.log(Level.INFO, () -> String.format("Votifier server listening on %s", address));
        } catch (IOException e) {
            try {
                ss.close();
            } catch (IOException ignored) {
                // Best-effort close
            }
            throw e;
        }
    }

    public void shutdown() {
        running.set(false);
        ServerSocket ss = serverSocket.get();
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) { /* Best-effort close */ }
        }
        Thread at = acceptThread.get();
        if (at != null) {
            at.interrupt();
        }
        ExecutorService ex = executor.get();
        if (ex != null) {
            ex.shutdownNow();
        }
        logger.info("Votifier server shut down");
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getPort() {
        return port;
    }
}
