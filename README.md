# ChainTickets: Blockchain Event Ticketing

A prototype event ticketing platform powered by a SHA-256 hash-linked blockchain ledger, built in plain Java (JDK 17+) with vanilla HTML, CSS, and JavaScript.

## Setup and Running

### Prerequisites
- JDK 17 or higher
- No external libraries or build tools required (no Maven, no Gradle)

### Compilation
From the project root:
```bash
javac *.java
```

### Running
Start the application server:
```bash
java Main
```
Or start in demo mode with tamper simulation enabled:
```bash
java Main --demo
```
The server binds to `http://127.0.0.1:8090`.

### First-Run Admin Password
When the application starts for the first time with an empty or non-existent `users.dat`, an `admin` account is automatically bootstrapped with a cryptographically secure random 16-character password generated using `SecureRandom`. The password is printed once to `stdout` upon startup:
```text
First run: admin account created
  username: admin
  password: <generated-16-char-password>
Save this now, it will not be shown again
```
If an administrator account already exists, no password is generated or printed.

## File Layout

| File | Description |
| --- | --- |
| `Block.java` | Block data model with SHA-256 hashing and stored-hash verification constructor |
| `Blockchain.java` | Append-only blockchain with `chain.dat` tab-separated persistence and cryptographic validation |
| `TicketSystem.java` | Domain logic for events, tickets, state replay, transfer validation, and block generation |
| `UserStore.java` | User store using PBKDF2WithHmacSHA256 (600,000 iterations, 16-byte salt, constant-time verification) |
| `Sessions.java` | In-memory 256-bit cryptographic session tokens with 2-hour expiration |
| `RequestStore.java` | Append-only `requests.dat` persistence with replay for ticket purchase workflows |
| `AuthException.java` | Exception carrying HTTP 401 and 403 status codes for authentication/authorization failures |
| `Main.java` | JDK HTTP server on 127.0.0.1:8090, request dispatching, input sanitization, and JSON responses |
| `index.html` | Single-page application root markup, responsive viewport, and accessible semantic layout |
| `style.css` | Custom responsive design system with light/dark theme support via `prefers-color-scheme` |
| `app.js` | SPA router (#/), view controllers, DOM generation via `createElement` and `textContent` |
| `chain.dat` | Tab-separated ledger storage: `index \t timestamp \t previousHash \t hash \t data` |
| `users.dat` | Tab-separated user storage: `username \t role \t iterations \t salt \t hash` |
| `requests.dat` | Append-only ticket request and decision event log |

## API Table

| Method | Endpoint | Required Role | Description |
| --- | --- | --- | --- |
| `GET` | `/api/events` | Anyone | List all upcoming events |
| `GET` | `/api/verify?ticketId=...` | Anyone | Verify ticket authenticity and current state |
| `POST` | `/api/auth/register` | Anyone | Register a new user account (role USER) |
| `POST` | `/api/auth/login` | Anyone | Authenticate with credentials and receive session cookie |
| `POST` | `/api/auth/logout` | Anyone | Terminate active session and clear cookie |
| `GET` | `/api/auth/me` | Logged-in | Retrieve username and role of current session |
| `GET` | `/api/users` | Logged-in | List registered usernames for ticket transfer dropdown |
| `GET` | `/api/tickets` | Logged-in | List tickets (USER sees owned tickets, ADMIN sees all) |
| `GET` | `/api/requests/list` | Logged-in | List ticket requests (USER sees own, ADMIN sees all) |
| `GET` | `/api/history?ticketId=...` | Owner / Admin | Retrieve full block audit trail for a specific ticket |
| `GET` | `/api/chain` | Logged-in | Inspect complete blockchain state and validation status |
| `POST` | `/api/requests/create` | USER | Submit a request to buy a ticket for an event |
| `POST` | `/api/tickets/transfer` | USER | Transfer owned ticket to another registered user |
| `POST` | `/api/events/create` | ADMIN | Create a new event on the ledger |
| `POST` | `/api/requests/approve` | ADMIN | Approve request and issue ticket onto the blockchain |
| `POST` | `/api/requests/reject` | ADMIN | Reject a ticket request |
| `POST` | `/api/tickets/use` | ADMIN | Mark an active ticket as USED at event entry |
| `POST` | `/api/demo/tamper` | Anyone (`--demo`) | Tamper with block data in memory to demonstrate chain verification |

## Limitations

1. **Single Node Architecture**: This is an educational prototype running on a single server instance. It does not implement peer-to-peer gossip, consensus protocols (e.g., Proof of Work, Proof of Stake), or distributed Byzantine fault tolerance.
2. **No Login Rate Limiting or Throttling**: The authentication endpoints do not enforce brute-force rate limits, IP blacklisting, or progressive lockouts.
3. **In-Memory Session Storage**: Active sessions are held in server memory and are cleared whenever the server process restarts, requiring users to log in again.
4. **Unprotected Off-Chain Storage**: `users.dat` and `requests.dat` are append-only flat files without cryptographic hash chaining or digital signatures. Tampering with those files outside the application is not automatically detected by the blockchain.
5. **Server-Enforced Ownership**: Ticket ownership verification and transfer authorizations are enforced by server session checks rather than by client-side public-key asymmetric digital signatures.
6. **Tail Truncation Vulnerability**: Removing blocks from the end of `chain.dat` is not detected by internal chain validation because the remaining predecessor hashes remain intact. External recording of the ledger anchor (head hash and block height) is required to detect truncation.
7. **No HTTPS / Transport Security**: Runs on a plain HTTP development server, so the `Secure` cookie attribute is omitted and credentials/session tokens travel unencrypted across networks outside localhost.
8. **Transparency vs. Privacy Trade-Off**: The ledger explorer is open to all logged-in users, meaning ticket owners' usernames and block transactions are visible to other users on the system.
9. **Off-Chain Request Modification & Unauthenticated Demo Tamper**: Request statuses can be modified by altering `requests.dat`, and when `--demo` mode is enabled, the memory tamper endpoint has no authentication.
10. **No Password Reset or Account Recovery**: There is no self-service password reset. Administrator credential recovery requires removing the admin record from `users.dat` and restarting the application to regenerate bootstrap credentials.
