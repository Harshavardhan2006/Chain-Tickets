import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class UserStore {
    public record User(String username, String role) {}

    private record Credential(String role, int iterations, byte[] salt, byte[] hash) {}

    private static final int ITERATIONS = 600_000;
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9_]{3,20}");
    private static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path file;
    private final Map<String, Credential> users = new LinkedHashMap<>();
    private final byte[] dummySalt = new byte[16];
    private final byte[] dummyHash;

    public UserStore(Path file) {
        this.file = file;
        RANDOM.nextBytes(dummySalt);
        dummyHash = derive("dummy-password", dummySalt, ITERATIONS);
        if (Files.exists(file)) {
            load();
        }
    }

    private void load() {
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] p = line.split("\t", -1);
                if (p.length != 5) {
                    throw new IllegalStateException("Malformed line in " + file);
                }
                users.put(p[0], new Credential(p[1], Integer.parseInt(p[2]),
                        Base64.getDecoder().decode(p[3]), Base64.getDecoder().decode(p[4])));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Malformed data in " + file);
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } finally {
            spec.clearPassword();
        }
    }

    private static String normalize(String username) {
        String name = username == null ? "" : username.trim().toLowerCase();
        if (!USERNAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Username must be 3 to 20 characters: letters, numbers or underscore");
        }
        return name;
    }

    private void save(String name, Credential credential) {
        String line = name + "\t" + credential.role() + "\t" + credential.iterations() + "\t"
                + Base64.getEncoder().encodeToString(credential.salt()) + "\t"
                + Base64.getEncoder().encodeToString(credential.hash()) + "\n";
        try {
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        users.put(name, credential);
    }

    private User add(String name, String role, String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        Credential credential = new Credential(role, ITERATIONS, salt, derive(password, salt, ITERATIONS));
        synchronized (this) {
            if (users.containsKey(name)) {
                throw new IllegalArgumentException("Username already taken");
            }
            save(name, credential);
        }
        return new User(name, role);
    }

    public User register(String username, String password) {
        String name = normalize(username);
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new IllegalArgumentException("Password must be 8 to 128 characters");
        }
        return add(name, "USER", password);
    }

    public String ensureAdmin() {
        synchronized (this) {
            for (Credential credential : users.values()) {
                if (credential.role().equals("ADMIN")) {
                    return null;
                }
            }
        }
        StringBuilder password = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            password.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
        }
        add("admin", "ADMIN", password.toString());
        return password.toString();
    }

    public User authenticate(String username, String password) {
        String name = username == null ? "" : username.trim().toLowerCase();
        Credential credential;
        synchronized (this) {
            credential = users.get(name);
        }
        byte[] salt = credential == null ? dummySalt : credential.salt();
        int iterations = credential == null ? ITERATIONS : credential.iterations();
        byte[] expected = credential == null ? dummyHash : credential.hash();
        byte[] actual = derive(password == null ? "" : password, salt, iterations);
        boolean match = MessageDigest.isEqual(expected, actual);
        return credential != null && match ? new User(name, credential.role()) : null;
    }

    public synchronized boolean exists(String username) {
        return username != null && users.containsKey(username.trim().toLowerCase());
    }

    public synchronized List<String> listUsernames() {
        return new ArrayList<>(users.keySet());
    }
}
