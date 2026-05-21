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
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    // volatile is sufficient: single-write / multi-read
    private volatile ServerSocket serverSocket;
    // volatile is sufficient: single-write / multi-read
    private volatile Thread acceptThread;
    // volatile is sufficient: single-write / multi-read
    private volatile ExecutorService executor;
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

        serverSocket = new ServerSocket();
        serverSocket.bind(address);

        // Handler pool for processing accepted connections — separate from the accept loop.
        executor = new ThreadPoolExecutor(
                2, MAX_CONNECTIONS, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                r -> {
                    Thread t = new Thread(r, "JExVote-Votifier-Handler");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());

        running.set(true);

        // Accept loop runs on its own dedicated thread — never competes with handlers.
        acceptThread = new Thread(() -> {
            while (running.get()) {
                try {
                    Socket client = serverSocket.accept();
                    client.setSoTimeout(15_000);
                    executor.submit(new VotifierProtocolHandler(
                            logger, client, keyPair, token, voteCallback));
                } catch (IOException e) {
                    if (running.get()) {
                        logger.log(Level.WARNING, "Error accepting vote connection", e);
                    }
                }
            }
        }, "JExVote-Votifier-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        logger.info(String.format("Votifier server listening on %s", address));
    }

    public void shutdown() {
        running.set(false);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) { /* Best-effort close */ }
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        if (executor != null) {
            executor.shutdownNow();
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
