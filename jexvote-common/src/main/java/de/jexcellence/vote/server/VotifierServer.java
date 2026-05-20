package de.jexcellence.vote.server;

import de.jexcellence.vote.server.protocol.VotifierProtocolHandler;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyPair;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private ServerSocket serverSocket;
    private ExecutorService executor;
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

        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "JExVote-Votifier");
            t.setDaemon(true);
            return t;
        });

        running.set(true);

        executor.submit(() -> {
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
        });

        logger.info("Votifier server listening on " + address);
    }

    public void shutdown() {
        running.set(false);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
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
