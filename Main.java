import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {
    private static Blockchain blockchain;
    private static TicketSystem system;
    private static UserStore users;
    private static Sessions sessions;
    private static RequestStore requests;
    private static final Object approvalLock = new Object();

    private static final Map<String, String> STATIC_FILES = Map.of(
            "index.html", "text/html; charset=utf-8",
            "style.css", "text/css; charset=utf-8",
            "app.js", "application/javascript; charset=utf-8");

    private interface Handler {
        String handle(Map<String, String> params) throws Exception;
    }

    private interface ExchangeHandler {
        String handle(HttpExchange exchange, Map<String, String> params) throws Exception;
    }

    public static void main(String[] args) throws IOException {
        try {
            blockchain = new Blockchain(Path.of("chain.dat"));
            users = new UserStore(Path.of("users.dat"));
            requests = new RequestStore(Path.of("requests.dat"));
        } catch (IllegalStateException e) {
            System.err.println("Startup refused: " + e.getMessage());
            System.exit(1);
        }
        system = new TicketSystem(blockchain);
        sessions = new Sessions();
        String envAdminPass = System.getenv("ADMIN_PASSWORD");
        String adminPassword = users.ensureAdmin(envAdminPass != null && !envAdminPass.trim().isEmpty() ? envAdminPass.trim() : null);
        if (adminPassword != null) {
            System.out.println("First run: admin account created");
            System.out.println("  username: admin");
            System.out.println("  password: " + adminPassword);
            System.out.println("Save this now, it will not be shown again");
        }

        int port = 8090;
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.trim().isEmpty()) {
            try {
                port = Integer.parseInt(envPort.trim());
            } catch (NumberFormatException ignored) {}
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        boolean demo = args.length > 0 && args[0].equals("--demo");

        register(server, "/api/events", "GET", p -> toJson(system.getEvents()));
        register(server, "/api/verify", "GET", p ->
                "{\"ticketId\":" + quote(p.get("ticketId")) + ",\"status\":"
                        + quote(system.verifyTicket(p.get("ticketId"))) + "}");

        if (demo) {
            register(server, "/api/demo/tamper", "POST", p -> {
                int index;
                try {
                    index = Integer.parseInt(p.getOrDefault("index", ""));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Index must be a number");
                }
                List<Block> blocks = blockchain.getChain();
                if (index < 0 || index >= blocks.size()) {
                    throw new IllegalArgumentException("Block not found");
                }
                blocks.get(index).setData(p.getOrDefault("data", "TAMPERED"));
                return "{\"tampered\":" + index + "}";
            });
        }

        register(server, "/api/auth/register", "POST", p -> {
            UserStore.User user = users.register(p.get("username"), p.get("password"));
            return userJson(user.username(), user.role());
        });
        wire(server, "/api/auth/login", "POST", (exchange, p) -> {
            UserStore.User user = users.authenticate(p.get("username"), p.get("password"));
            if (user == null) {
                throw new AuthException(401, "Invalid username or password");
            }
            String token = sessions.create(user.username(), user.role());
            exchange.getResponseHeaders().add("Set-Cookie",
                    "session=" + token + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=7200");
            return userJson(user.username(), user.role());
        });
        wire(server, "/api/auth/logout", "POST", (exchange, p) -> {
            sessions.destroy(sessionToken(exchange));
            exchange.getResponseHeaders().add("Set-Cookie", "session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0");
            return "{\"ok\":true}";
        });
        wire(server, "/api/auth/me", "GET", (exchange, p) -> {
            Sessions.Session session = requireSession(exchange);
            return userJson(session.username(), session.role());
        });
        wire(server, "/api/users", "GET", (ex, p) -> {
            requireSession(ex);
            List<String> list = users.listUsernames();
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                sb.append(i > 0 ? "," : "").append(quote(list.get(i)));
            }
            return sb.append("]").toString();
        });

        wire(server, "/api/events/create", "POST", (ex, p) -> {
            requireRole(ex, "ADMIN");
            double price;
            try {
                price = Double.parseDouble(p.getOrDefault("price", ""));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Price must be a number");
            }
            return toJson(system.createEvent(p.get("name"), p.get("date"), p.get("venue"), price));
        });
        wire(server, "/api/tickets", "GET", (ex, p) -> {
            Sessions.Session s = requireSession(ex);
            List<TicketSystem.Ticket> all = system.getTickets();
            if (s.role().equals("ADMIN")) {
                return toJsonTickets(all);
            }
            return toJsonTickets(all.stream().filter(t -> t.owner.equals(s.username())).toList());
        });
        wire(server, "/api/requests/create", "POST", (ex, p) -> {
            Sessions.Session s = requireRole(ex, "USER");
            String eventId = p.get("eventId");
            boolean exists = system.getEvents().stream().anyMatch(e -> e.id().equals(eventId));
            if (!exists) {
                throw new IllegalArgumentException("Event not found");
            }
            return toJson(requests.create(s.username(), eventId));
        });
        wire(server, "/api/requests/list", "GET", (ex, p) -> {
            Sessions.Session s = requireSession(ex);
            return toJsonRequests(requests.list(s.role().equals("ADMIN") ? null : s.username()));
        });
        wire(server, "/api/requests/approve", "POST", (ex, p) -> {
            Sessions.Session s = requireRole(ex, "ADMIN");
            return approve(p.get("requestId"), s.username());
        });
        wire(server, "/api/requests/reject", "POST", (ex, p) -> {
            Sessions.Session s = requireRole(ex, "ADMIN");
            return toJson(requests.decide(p.get("requestId"), "REJECTED", s.username(), null));
        });
        wire(server, "/api/tickets/transfer", "POST", (ex, p) -> {
            Sessions.Session s = requireRole(ex, "USER");
            String to = p.getOrDefault("to", "").trim().toLowerCase();
            if (!users.exists(to)) {
                throw new IllegalArgumentException("Recipient account not found");
            }
            return toJson(system.transferTicket(p.get("ticketId"), s.username(), to));
        });
        wire(server, "/api/tickets/use", "POST", (ex, p) -> {
            requireRole(ex, "ADMIN");
            return toJson(system.useTicket(p.get("ticketId")));
        });
        wire(server, "/api/history", "GET", (ex, p) -> {
            Sessions.Session s = requireSession(ex);
            TicketSystem.Ticket t = system.getTicket(p.get("ticketId"));
            if (t == null) {
                return "[]";
            }
            if (!s.role().equals("ADMIN") && !t.owner.equals(s.username())) {
                throw new AuthException(403, "Not allowed");
            }
            return toJsonBlocks(system.getHistory(p.get("ticketId")));
        });
        wire(server, "/api/chain", "GET", (ex, p) -> {
            requireSession(ex);
            int bad = blockchain.findInvalidIndex();
            return "{\"valid\":" + (bad == -1) + ",\"invalidIndex\":" + bad
                    + ",\"blocks\":" + toJsonBlocks(blockchain.getChain()) + "}";
        });

        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String file = path.equals("/") ? "index.html" : path.substring(1);
            String type = STATIC_FILES.get(file);
            if (type == null || !Files.exists(Path.of(file))) {
                send(exchange, 404, "text/plain; charset=utf-8", "Not found");
                return;
            }
            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            send(exchange, 200, type, Files.readString(Path.of(file)));
        });

        server.start();
        System.out.println("Server running on port " + port);
        if (demo) {
            System.out.println("Demo mode: tamper endpoint enabled");
        }
    }

    private static void register(HttpServer server, String path, String method, Handler handler) {
        wire(server, path, method, (exchange, params) -> handler.handle(params));
    }

    private static void wire(HttpServer server, String path, String method, ExchangeHandler handler) {
        server.createContext(path, exchange -> {
            try {
                if (!exchange.getRequestMethod().equals(method)) {
                    send(exchange, 405, "application/json; charset=utf-8", error("Method not allowed"));
                    return;
                }
                Map<String, String> params = parse(exchange.getRequestURI().getRawQuery());
                if (method.equals("POST")) {
                    String body = new String(exchange.getRequestBody().readNBytes(4096), StandardCharsets.UTF_8);
                    params.putAll(parse(body));
                }
                send(exchange, 200, "application/json; charset=utf-8", handler.handle(exchange, params));
            } catch (AuthException e) {
                send(exchange, e.getStatus(), "application/json; charset=utf-8", error(e.getMessage()));
            } catch (IllegalArgumentException e) {
                send(exchange, 400, "application/json; charset=utf-8", error(e.getMessage()));
            } catch (IllegalStateException e) {
                send(exchange, 409, "application/json; charset=utf-8", error(e.getMessage()));
            } catch (Exception e) {
                send(exchange, 500, "application/json; charset=utf-8", error("Internal server error"));
            }
        });
    }

    private static Map<String, String> parse(String raw) {
        Map<String, String> params = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return params;
        }
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            params.put(key, value);
        }
        return params;
    }

    private static void send(HttpExchange exchange, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return sb.append("\"").toString();
    }

    private static String error(String message) {
        return "{\"error\":" + quote(message) + "}";
    }

    private static String sessionToken(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Cookie");
        if (header == null) {
            return null;
        }
        for (String part : header.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals("session")) {
                return kv[1];
            }
        }
        return null;
    }

    private static Sessions.Session requireSession(HttpExchange exchange) {
        Sessions.Session session = sessions.find(sessionToken(exchange));
        if (session == null) {
            throw new AuthException(401, "Please log in");
        }
        return session;
    }

    private static String userJson(String username, String role) {
        return "{\"username\":" + quote(username) + ",\"role\":" + quote(role) + "}";
    }

    private static Sessions.Session requireRole(HttpExchange exchange, String role) {
        Sessions.Session session = requireSession(exchange);
        if (!session.role().equals(role)) {
            throw new AuthException(403, "Not allowed");
        }
        return session;
    }

    private static String toJson(RequestStore.TicketRequest r) {
        return "{\"id\":" + quote(r.id()) + ",\"username\":" + quote(r.username())
                + ",\"eventId\":" + quote(r.eventId()) + ",\"createdAt\":" + r.createdAt()
                + ",\"status\":" + quote(r.status()) + ",\"ticketId\":" + quote(r.ticketId())
                + ",\"decidedBy\":" + quote(r.decidedBy()) + "}";
    }

    private static String toJsonRequests(List<RequestStore.TicketRequest> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            sb.append(i > 0 ? "," : "").append(toJson(list.get(i)));
        }
        return sb.append("]").toString();
    }

    private static String approve(String requestId, String admin) {
        synchronized (approvalLock) {
            RequestStore.TicketRequest request = requests.get(requestId);
            if (request == null) {
                throw new IllegalArgumentException("Request not found");
            }
            if (!request.status().equals("PENDING")) {
                throw new IllegalArgumentException("Request already " + request.status());
            }
            String ticketId = system.findTicketByRequest(requestId);
            if (ticketId == null) {
                ticketId = system.issueTicket(request.eventId(), request.username(), requestId).id;
            }
            return toJson(requests.decide(requestId, "APPROVED", admin, ticketId));
        }
    }

    private static String toJson(TicketSystem.Event e) {
        return "{\"id\":" + quote(e.id()) + ",\"name\":" + quote(e.name()) + ",\"date\":" + quote(e.date())
                + ",\"venue\":" + quote(e.venue()) + ",\"price\":" + e.price() + "}";
    }

    private static String toJson(List<TicketSystem.Event> events) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < events.size(); i++) {
            sb.append(i > 0 ? "," : "").append(toJson(events.get(i)));
        }
        return sb.append("]").toString();
    }

    private static String toJson(TicketSystem.Ticket t) {
        return "{\"id\":" + quote(t.id) + ",\"eventId\":" + quote(t.eventId) + ",\"owner\":" + quote(t.owner)
                + ",\"status\":" + quote(t.status) + "}";
    }

    private static String toJsonTickets(List<TicketSystem.Ticket> tickets) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tickets.size(); i++) {
            sb.append(i > 0 ? "," : "").append(toJson(tickets.get(i)));
        }
        return sb.append("]").toString();
    }

    private static String toJsonBlocks(List<Block> blocks) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            sb.append(i > 0 ? "," : "")
                    .append("{\"index\":").append(b.getIndex())
                    .append(",\"timestamp\":").append(b.getTimestamp())
                    .append(",\"data\":").append(quote(b.getData()))
                    .append(",\"previousHash\":").append(quote(b.getPreviousHash()))
                    .append(",\"hash\":").append(quote(b.getHash())).append("}");
        }
        return sb.append("]").toString();
    }
}