package de.jexcellence.vote.server.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.jexcellence.vote.model.Vote;
import de.jexcellence.vote.server.VoteResult;
import org.jetbrains.annotations.NotNull;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedInputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class VotifierProtocolHandler implements Runnable {

    private static final int V1_BLOCK_SIZE = 256;
    /** NuVotifier v2 binary frame magic: 0x733A ("s:") */
    private static final short V2_MAGIC = 0x733A;
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Logger logger;
    private final Socket socket;
    private final KeyPair keyPair;
    private final String token;
    private final Consumer<VoteResult> voteCallback;

    public VotifierProtocolHandler(@NotNull Logger logger, @NotNull Socket socket,
                                   @NotNull KeyPair keyPair, @NotNull String token,
                                   @NotNull Consumer<VoteResult> voteCallback) {
        this.logger = logger;
        this.socket = socket;
        this.keyPair = keyPair;
        this.token = token;
        this.voteCallback = voteCallback;
    }

    @Override
    public void run() {
        try (socket) {
            String challenge = UUID.randomUUID().toString().replace("-", "");
            OutputStream out = socket.getOutputStream();
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());

            String greeting = "VOTIFIER 2 " + challenge + "\n";
            out.write(greeting.getBytes(StandardCharsets.UTF_8));
            out.flush();

            int firstByte = in.read();
            if (firstByte == -1) return;

            if (firstByte == (V2_MAGIC >> 8 & 0xFF)) {
                // Possible NuVotifier v2 binary frame (magic 0x733A)
                int secondByte = in.read();
                if (secondByte == (V2_MAGIC & 0xFF)) {
                    handleV2Binary(in, out, challenge);
                } else {
                    // Not v2 magic — treat first two bytes as start of v1 block
                    handleV1(in, firstByte, secondByte);
                }
            } else if (firstByte == 0x00 || firstByte == '{') {
                handleV2(in, out, challenge, firstByte);
            } else {
                handleV1(in, firstByte, -1);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error handling vote connection", e);
        }
    }

    private void handleV1(@NotNull BufferedInputStream in, int firstByte, int secondByte) throws Exception {
        byte[] block = new byte[V1_BLOCK_SIZE];
        block[0] = (byte) firstByte;

        int read = 1;
        if (secondByte != -1) {
            block[1] = (byte) secondByte;
            read = 2;
        }

        while (read < V1_BLOCK_SIZE) {
            int n = in.read(block, read, V1_BLOCK_SIZE - read);
            if (n == -1) {
                logger.warning("Incomplete v1 vote block (got " + read + " bytes)");
                return;
            }
            read += n;
        }

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
        byte[] decrypted = cipher.doFinal(block);

        String payload = new String(decrypted, StandardCharsets.UTF_8);
        String[] lines = payload.split("\n");

        if (lines.length < 4 || !"VOTE".equals(lines[0])) {
            logger.warning("Invalid v1 vote payload");
            return;
        }

        String serviceName = lines[1].trim();
        String username = lines[2].trim();
        String address = lines[3].trim();

        Vote vote = new Vote(username, serviceName, address, Instant.now());
        voteCallback.accept(new VoteResult(vote, VoteResult.Protocol.V1));
        logger.info("Received v1 vote from " + serviceName + " for " + username);
    }

    /**
     * Handles the NuVotifier v2 binary frame format:
     * [2-byte magic 0x733A] [2-byte length] [JSON payload]
     * The magic bytes have already been consumed.
     */
    private void handleV2Binary(@NotNull BufferedInputStream in, @NotNull OutputStream out,
                                 @NotNull String challenge) throws Exception {
        int high = in.read();
        int low = in.read();
        if (high == -1 || low == -1) return;
        int length = (high << 8) | low;
        if (length <= 0 || length > 8192) {
            logger.warning("Invalid v2 binary frame length: " + length);
            return;
        }
        byte[] rawBytes = in.readNBytes(length);
        String rawJson = new String(rawBytes, StandardCharsets.UTF_8);
        processV2Json(rawJson, out, challenge);
    }

    private void handleV2(@NotNull BufferedInputStream in, @NotNull OutputStream out,
                           @NotNull String challenge, int firstByte) throws Exception {
        byte[] rawBytes;

        if (firstByte == 0x00) {
            if (in.available() < 2) {
                Thread.sleep(100);
            }
            int high = in.read();
            int low = in.read();
            if (high == -1 || low == -1) return;
            int length = (high << 8) | low;
            if (length <= 0 || length > 8192) {
                logger.warning("Invalid v2 frame length: " + length);
                return;
            }
            rawBytes = in.readNBytes(length);
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append((char) firstByte);
            int braces = firstByte == '{' ? 1 : 0;
            while (braces > 0) {
                int b = in.read();
                if (b == -1) break;
                sb.append((char) b);
                if (b == '{') braces++;
                else if (b == '}') braces--;
            }
            rawBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        }

        String rawJson = new String(rawBytes, StandardCharsets.UTF_8);
        processV2Json(rawJson, out, challenge);
    }

    private void processV2Json(@NotNull String rawJson, @NotNull OutputStream out,
                                @NotNull String challenge) throws Exception {
        JsonNode root = MAPPER.readTree(rawJson);
        String payloadStr = root.get("payload").asText();
        String signatureStr = root.get("signature").asText();

        byte[] signatureBytes = Base64.getDecoder().decode(signatureStr);
        byte[] computedSig = hmac(token, payloadStr);

        if (!MessageDigest.isEqual(signatureBytes, computedSig)) {
            logger.warning("v2 vote signature mismatch — invalid token or tampered payload");
            sendV2Response(out, "error", "signature verification failed");
            return;
        }

        JsonNode payload = MAPPER.readTree(payloadStr);
        String payloadChallenge = payload.has("challenge") ? payload.get("challenge").asText() : "";

        if (!challenge.equals(payloadChallenge)) {
            logger.warning("v2 vote challenge mismatch");
            sendV2Response(out, "error", "challenge mismatch");
            return;
        }

        String serviceName = payload.get("serviceName").asText();
        String username = payload.get("username").asText();
        String address = payload.has("address") ? payload.get("address").asText() : "";
        long timestamp = payload.has("timestamp") ? payload.get("timestamp").asLong() : 0;

        Vote vote = new Vote(
                username, serviceName, address,
                timestamp > 0 ? Instant.ofEpochMilli(timestamp) : Instant.now());

        voteCallback.accept(new VoteResult(vote, VoteResult.Protocol.V2));
        logger.info("Received v2 vote from " + serviceName + " for " + username);
        sendV2Response(out, "ok", null);
    }

    private void sendV2Response(@NotNull OutputStream out, @NotNull String status, String error) {
        try {
            String json;
            if (error != null) {
                json = "{\"status\":\"" + status + "\",\"error\":\"" + error.replace("\"", "\\\"") + "\"}";
            } else {
                json = "{\"status\":\"" + status + "\"}";
            }
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {}
    }

    private static byte[] hmac(@NotNull String key, @NotNull String data) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }
}
