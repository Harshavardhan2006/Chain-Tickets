import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Sessions {
    public record Session(String username, String role, long expiresAt) {}

    private static final long TTL_MILLIS = 2L * 60 * 60 * 1000;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public String create(String username, String role) {
        long now = System.currentTimeMillis();
        sessions.values().removeIf(s -> s.expiresAt() < now);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        sessions.put(token, new Session(username, role, now + TTL_MILLIS));
        return token;
    }

    public Session find(String token) {
        if (token == null) {
            return null;
        }
        Session session = sessions.get(token);
        if (session == null) {
            return null;
        }
        if (session.expiresAt() < System.currentTimeMillis()) {
            sessions.remove(token);
            return null;
        }
        return session;
    }

    public void destroy(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }
}
