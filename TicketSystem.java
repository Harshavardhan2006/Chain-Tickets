import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TicketSystem {
    public record Event(String id, String name, String date, String venue, double price) {}

    public static class Ticket {
        public final String id;
        public final String eventId;
        public String owner;
        public String status;

        Ticket(String id, String eventId, String owner) {
            this.id = id;
            this.eventId = eventId;
            this.owner = owner;
            this.status = "ACTIVE";
        }
    }

    private static class State {
        final Map<String, Event> events = new LinkedHashMap<>();
        final Map<String, Ticket> tickets = new LinkedHashMap<>();
    }

    private final Blockchain blockchain;

    public TicketSystem(Blockchain blockchain) {
        this.blockchain = blockchain;
    }

    private void requireValid() {
        int bad = blockchain.findInvalidIndex();
        if (bad != -1) {
            throw new IllegalStateException("Blockchain integrity check failed at block " + bad);
        }
    }

    private State replay() {
        State state = new State();
        for (Block block : blockchain.getChain()) {
            String[] p = block.getData().split("\\|", -1);
            switch (p[0]) {
                case "EVENT" -> state.events.put(p[1], new Event(p[1], p[2], p[3], p[4], Double.parseDouble(p[5])));
                case "ISSUE" -> state.tickets.put(p[1], new Ticket(p[1], p[2], p[3]));
                case "TRANSFER" -> state.tickets.get(p[1]).owner = p[3];
                case "USE" -> state.tickets.get(p[1]).status = "USED";
                default -> { }
            }
        }
        return state;
    }

    private String clean(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        String v = value.trim();
        if (v.length() > 60 || v.contains("|") || v.contains("\n") || v.contains("\r")) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return v;
    }

    public synchronized Event createEvent(String name, String date, String venue, double price) {
        requireValid();
        String n = clean(name, "Event name");
        String d = clean(date, "Event date");
        String v = clean(venue, "Venue");
        if (price < 0) {
            throw new IllegalArgumentException("Price cannot be negative");
        }
        String id = "E-" + (replay().events.size() + 1);
        blockchain.addBlock("EVENT|" + id + "|" + n + "|" + d + "|" + v + "|" + price);
        return new Event(id, n, d, v, price);
    }

    public synchronized void ensureInitialEvents() {
        if (!getEvents().isEmpty()) {
            return;
        }
        createEvent("Symphony Night", "2026-12-10", "Concert Hall", 120.0);
        createEvent("Tech Summit 2026", "2026-11-15", "Convention Center", 75.0);
        createEvent("Campus Music Fest", "2026-10-31", "Grand Amphitheater", 50.0);
    }

    public synchronized Ticket issueTicket(String eventId, String owner, String requestId) {
        requireValid();
        String o = clean(owner, "Owner");
        if (!replay().events.containsKey(eventId)) {
            throw new IllegalArgumentException("Event not found");
        }
        String id = "T-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        blockchain.addBlock("ISSUE|" + id + "|" + eventId + "|" + o + "|" + requestId);
        return new Ticket(id, eventId, o);
    }

    public synchronized Ticket transferTicket(String ticketId, String from, String to) {
        requireValid();
        String newOwner = clean(to, "New owner");
        Ticket ticket = replay().tickets.get(ticketId);
        if (ticket == null) {
            throw new IllegalArgumentException("Ticket not found");
        }
        if (!ticket.status.equals("ACTIVE")) {
            throw new IllegalArgumentException("Only ACTIVE tickets can be transferred");
        }
        if (!ticket.owner.equals(from)) {
            throw new IllegalArgumentException("Sender is not the current owner");
        }
        if (newOwner.equals(ticket.owner)) {
            throw new IllegalArgumentException("New owner must be different");
        }
        blockchain.addBlock("TRANSFER|" + ticketId + "|" + ticket.owner + "|" + newOwner);
        ticket.owner = newOwner;
        return ticket;
    }

    public synchronized Ticket useTicket(String ticketId) {
        requireValid();
        Ticket ticket = replay().tickets.get(ticketId);
        if (ticket == null) {
            throw new IllegalArgumentException("Ticket not found");
        }
        if (!ticket.status.equals("ACTIVE")) {
            throw new IllegalArgumentException("Ticket " + ticketId + " is already " + ticket.status);
        }
        blockchain.addBlock("USE|" + ticketId);
        ticket.status = "USED";
        return ticket;
    }

    public synchronized String verifyTicket(String ticketId) {
        if (!blockchain.isValid()) {
            return "CHAIN_INVALID";
        }
        Ticket ticket = replay().tickets.get(ticketId);
        return ticket == null ? "NOT_FOUND" : ticket.status;
    }

    public synchronized Ticket getTicket(String ticketId) {
        requireValid();
        return replay().tickets.get(ticketId);
    }

    public synchronized List<Event> getEvents() {
        requireValid();
        return new ArrayList<>(replay().events.values());
    }

    public synchronized List<Ticket> getTickets() {
        requireValid();
        return new ArrayList<>(replay().tickets.values());
    }

    public synchronized List<Block> getHistory(String ticketId) {
        List<Block> history = new ArrayList<>();
        for (Block block : blockchain.getChain()) {
            String[] p = block.getData().split("\\|", -1);
            boolean ticketOp = p[0].equals("ISSUE") || p[0].equals("TRANSFER") || p[0].equals("USE");
            if (ticketOp && p.length > 1 && p[1].equals(ticketId)) {
                history.add(block);
            }
        }
        return history;
    }

    public synchronized String findTicketByRequest(String requestId) {
        requireValid();
        for (Block block : blockchain.getChain()) {
            String[] p = block.getData().split("\\|", -1);
            if (p[0].equals("ISSUE") && p.length > 4 && p[4].equals(requestId)) {
                return p[1];
            }
        }
        return null;
    }
}