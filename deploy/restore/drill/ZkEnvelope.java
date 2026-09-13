// =============================================================================
// Seals and opens zero-knowledge values exactly as the web client does
// (backend/src/main/resources/static/app/e2e.js, docs/12 §3), for the restore
// drill only.
//
// The drill needs REAL sealed values, not random bytes shaped like them: the
// question after a restore is not "does it parse" but "does it open", and only
// a value that was genuinely sealed can answer that. JDK only, no build:
//
//   java deploy/restore/drill/ZkEnvelope.java key  <passphrase>
//   java deploy/restore/drill/ZkEnvelope.java seal <passphrase> <kdfSalt> <iterations> <wrappedKey> <aad> <plaintext>
//   java deploy/restore/drill/ZkEnvelope.java open <passphrase> <kdfSalt> <iterations> <wrappedKey> <aad> <ciphertext>
//
// <aad> is householdId|recordType|recordId|fieldKey, ids lowercased.
// `open` exits 1 if the value does not open — that is the signal the drill uses.
// =============================================================================

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Base64;

public class ZkEnvelope {
    static final SecureRandom RANDOM = new SecureRandom();
    static final int ITERATIONS = 600_000;

    static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static byte[] unb64(String s) { return Base64.getUrlDecoder().decode(s); }

    static SecretKeySpec wrappingKey(String passphrase, byte[] salt, int iterations) throws Exception {
        char[] chars = Normalizer.normalize(passphrase, Normalizer.Form.NFC).toCharArray();
        byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(new PBEKeySpec(chars, salt, iterations, 256)).getEncoded();
        return new SecretKeySpec(raw, "AES");
    }

    static String seal(SecretKeySpec key, byte[] plaintext, byte[] aad, int version) throws Exception {
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        if (aad != null) c.updateAAD(aad);
        byte[] body = c.doFinal(plaintext);
        return b64(ByteBuffer.allocate(17 + body.length).put((byte) 1).putInt(version).put(iv).put(body).array());
    }

    static byte[] open(SecretKeySpec key, String envelope, byte[] aad) throws Exception {
        byte[] e = unb64(envelope);
        if (e.length < 33 || e[0] != 1) throw new IllegalArgumentException("not an envelope");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOfRange(e, 5, 17)));
        if (aad != null) c.updateAAD(aad);
        return c.doFinal(Arrays.copyOfRange(e, 17, e.length));
    }

    static SecretKeySpec contentKey(String passphrase, String salt, String iterations, String wrapped) throws Exception {
        byte[] raw = open(wrappingKey(passphrase, unb64(salt), Integer.parseInt(iterations)), wrapped, null);
        return new SecretKeySpec(raw, "AES");
    }

    public static void main(String[] a) throws Exception {
        switch (a.length == 0 ? "" : a[0]) {
            case "key" -> {
                byte[] salt = new byte[16];
                RANDOM.nextBytes(salt);
                byte[] content = new byte[32];
                RANDOM.nextBytes(content);
                String wrapped = seal(wrappingKey(a[1], salt, ITERATIONS), content, null, 1);
                String verifier = seal(new SecretKeySpec(content, "AES"),
                    "almira".getBytes(StandardCharsets.UTF_8), null, 1);
                System.out.printf(
                    "{\"kdf\":\"PBKDF2-SHA256\",\"kdfSalt\":\"%s\",\"iterations\":%d,\"wrapAlgorithm\":\"AES-GCM-256\","
                        + "\"wrappedKey\":\"%s\",\"verifier\":\"%s\",\"keyVersion\":1}%n",
                    b64(salt), ITERATIONS, wrapped, verifier);
            }
            case "seal" -> System.out.println(seal(contentKey(a[1], a[2], a[3], a[4]),
                a[6].getBytes(StandardCharsets.UTF_8), a[5].getBytes(StandardCharsets.UTF_8), 1));
            case "open" -> {
                try {
                    byte[] plain = open(contentKey(a[1], a[2], a[3], a[4]), a[6], a[5].getBytes(StandardCharsets.UTF_8));
                    System.out.println(new String(plain, StandardCharsets.UTF_8));
                } catch (Exception e) {
                    System.err.println("does not open: " + e.getClass().getSimpleName());
                    System.exit(1);
                }
            }
            default -> {
                System.err.println("usage: key <passphrase> | seal|open <passphrase> <salt> <iterations> <wrappedKey> <aad> <text>");
                System.exit(2);
            }
        }
    }
}
