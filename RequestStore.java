import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RequestStore {
    public record TicketRequest(String id, String username, String eventId, long createdAt,
                                String status, String ticketId, String decidedBy) {}

    private final Path file;
    private final Map<String, TicketRequest> requests = new LinkedHashMap<>();

    public RequestStore(Path file) {
        this.file = file;
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
                if (p[0].equals("REQUEST") && p.length == 5) {
                    requests.put(p[1], new TicketRequest(p[1], p[2], p[3], Long.parseLong(p[4]), "PENDING", null, null));
                } else if (p[0].equals("DECISION") && p.length == 5 && requests.containsKey(p[1])) {
                    TicketRequest r = requests.get(p[1]);
                    requests.put(p[1], new TicketRequest(r.id(), r.username(), r.eventId(), r.createdAt(),
                            p[2], p[4].isEmpty() ? null : p[4], p[3]));
                } else {
                    throw new IllegalStateException("Malformed line in " + file);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Malformed number in " + file);
        }
    }

    private void append(String line) {
        try {
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized TicketRequest create(String username, String eventId) {
        for (TicketRequest r : requests.values()) {
            if (r.username().equals(username) && r.eventId().equals(eventId) && r.status().equals("PENDING")) {
                throw new IllegalArgumentException("You already have a pending request for this event");
            }
        }
        String id = "R-" + (requests.size() + 1);
        long now = System.currentTimeMillis();
        append("REQUEST\t" + id + "\t" + username + "\t" + eventId + "\t" + now);
        TicketRequest request = new TicketRequest(id, username, eventId, now, "PENDING", null, null);
        requests.put(id, request);
        return request;
    }

    public synchronized TicketRequest decide(String id, String status, String admin, String ticketId) {
        TicketRequest r = requests.get(id);
        if (r == null) {
            throw new IllegalArgumentException("Request not found");
        }
        if (!r.status().equals("PENDING")) {
            throw new IllegalArgumentException("Request already " + r.status());
        }
        append("DECISION\t" + id + "\t" + status + "\t" + admin + "\t" + (ticketId == null ? "" : ticketId));
        TicketRequest decided = new TicketRequest(r.id(), r.username(), r.eventId(), r.createdAt(),
                status, ticketId, admin);
        requests.put(id, decided);
        return decided;
    }

    public synchronized TicketRequest get(String id) {
        return requests.get(id);
    }

    public synchronized List<TicketRequest> list(String username) {
        List<TicketRequest> out = new ArrayList<>();
        for (TicketRequest r : requests.values()) {
            if (username == null || r.username().equals(username)) {
                out.add(r);
            }
        }
        return out;
    }
}
