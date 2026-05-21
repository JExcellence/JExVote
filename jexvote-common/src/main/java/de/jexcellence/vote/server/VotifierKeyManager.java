package de.jexcellence.vote.server;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;
import java.util.logging.Logger;

public final class VotifierKeyManager {

    private VotifierKeyManager() {}

    public static @NotNull KeyPair loadOrGenerate(@NotNull Path dataFolder, @NotNull Logger logger)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        Path publicKeyFile = dataFolder.resolve("rsa/public.key");
        Path privateKeyFile = dataFolder.resolve("rsa/private.key");

        if (Files.exists(publicKeyFile) && Files.exists(privateKeyFile)) {
            logger.info("Loading existing RSA keypair");
            return loadKeyPair(publicKeyFile, privateKeyFile);
        }

        logger.info("Generating new 2048-bit RSA keypair for Votifier protocol");
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();

        Files.createDirectories(publicKeyFile.getParent());
        writeKey(publicKeyFile, pair.getPublic().getEncoded());
        writeKey(privateKeyFile, pair.getPrivate().getEncoded());

        logger.info(String.format("RSA keypair saved to %s", dataFolder.resolve("rsa")));
        return pair;
    }

    public static @NotNull String generateToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static @NotNull String encodePublicKey(@NotNull PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    private static @NotNull KeyPair loadKeyPair(Path publicFile, Path privateFile)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        KeyFactory factory = KeyFactory.getInstance("RSA");

        byte[] pubBytes = Base64.getDecoder().decode(
                Files.readString(publicFile).replaceAll("\\s+", ""));
        PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(pubBytes));

        byte[] privBytes = Base64.getDecoder().decode(
                Files.readString(privateFile).replaceAll("\\s+", ""));
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(privBytes));

        return new KeyPair(publicKey, privateKey);
    }

    private static void writeKey(Path file, byte[] encoded) throws IOException {
        Files.writeString(file, Base64.getEncoder().encodeToString(encoded));
    }
}
