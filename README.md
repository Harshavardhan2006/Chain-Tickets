# ChainTickets: Blockchain Event Ticketing

A multi-user event ticketing platform powered by a SHA-256 hash-linked blockchain ledger, built entirely in plain Java (JDK 17+) with vanilla HTML, CSS, and JavaScript.

**Zero external dependencies**: No Maven, no Gradle, no Spring, no npm packages, no CSS frameworks, and no CDN imports.

---

## Architecture Overview

ChainTickets pairs a lightweight Java HTTP backend with an append-only cryptographic ledger and an interactive single-page web client.

```
                    Browser (SPA: HTML/CSS/JS)
                                |
                   HTTP JSON API & Static Files
                                |
               +----------------+----------------+
               |      JDK HttpServer (Main.java) |
               +----------------+----------------+
                                |
        +-----------------------+-----------------------+
        |                       |                       |
+-------v-------+       +-------v-------+       +-------v-------+
|  UserStore    |       | TicketSystem  |       | RequestStore  |
| (PBKDF2-HMAC) |       | (State Replay)|       | (Append-Only) |
+-------+-------+       +-------+-------+       +-------+-------+
        |                       |                       |
   users.dat            +-------v-------+          requests.dat
                        |  Blockchain   |
                        | (chain.dat)   |
                        +---------------+
```

### On-Chain vs. Off-Chain Data Separation

To protect sensitive user information while maintaining ledger immutability and auditability:

- **On-Chain (`chain.dat`)**:
  - `EVENT|eventId|name|date|venue|totalTickets|price`: Event creation records.
  - `ISSUE|ticketId|eventId|owner|price`: Ticket issuance upon admin approval.
  - `TRANSFER|ticketId|fromUser|toUser`: Ticket ownership transfer between registered users.
  - `USE|ticketId|byUser`: Ticket redemption/entry at the venue door.
- **Off-Chain (`users.dat`, `requests.dat`, In-Memory)**:
  - User credentials, salts, iteration counts, and password hashes (`users.dat`).
  - Active 256-bit cryptographically secure session tokens (in-memory only).
  - Ticket purchase requests, timestamps, and approval/rejection statuses (`requests.dat`).

---

## Core Features

- **Public Event Catalog**: Anyone can browse published events and event details.
- **Public Ticket Verification**: Verify ticket authenticity, current state (`VALID` or `USED`), and owner without logging in.
- **Role-Based Authentication**:
  - **USER**: Request tickets for events, track request statuses, view owned tickets, transfer tickets to other users with a recipient dropdown, view complete block-by-block audit trail for owned tickets.
  - **ADMIN**: Create new events on the ledger, review pending purchase requests, approve requests to issue new blockchain tickets, reject requests, mark tickets as `USED` at the venue entrance, inspect the entire ledger, and simulate memory tampering.
- **Instant Recipient Selection**: Seamless dropdown populated from `/api/users` when transferring tickets, preventing username mistypes.
- **Live Blockchain Explorer & Tamper Detection**: Inspect all blocks in the chain with real-time validation. When launched with `--demo`, simulate memory tampering to observe instant chain invalidation and block index detection.

---

## Getting Started

### Prerequisites
- Java Development Kit (JDK) 17 or higher
- Optional: Docker (for containerized execution)

### Compilation
From the project root:
```bash
javac *.java
```

### Running Locally

#### Standard Mode
```bash
java Main
```

#### Demo Mode (Enables Tamper Simulation Endpoint)
```bash
java Main --demo
```

The server binds to `http://0.0.0.0:8090` by default.

#### Custom Port
Set the `PORT` environment variable:
- **Windows PowerShell**:
  ```powershell
  $env:PORT="3000"; java Main --demo
  ```
- **Windows CMD**:
  ```cmd
  set PORT=3000 && java Main --demo
  ```
- **Linux / macOS**:
  ```bash
  PORT=3000 java Main --demo
  ```

---

## Running with Docker

Build the Docker image using the multi-stage build:
```bash
docker build -t chain-tickets .
```

Run the container:
```bash
docker run -d -p 8090:8090 --name chain-tickets-app chain-tickets
```

With custom admin password:
```bash
docker run -d -p 8090:8090 -e ADMIN_PASSWORD=StrongAdminPassword123 chain-tickets
```

---

## Cloud Deployment (Render)

The repository includes a ready-to-use `render.yaml` specification for deploying as a Docker Web Service on [Render](https://render.com):

1. Fork or push this repository to GitHub.
2. Sign in to Render and create a **New Blueprint Instance** or **New Web Service** pointing to this repository.
3. Configure environment variables in the Render dashboard:
   - `ADMIN_PASSWORD`: Your secret administrator password.
   - `USER_NAME`: (Optional) Your preferred persistent user username (e.g. `harsha`).
   - `USER_PASSWORD`: (Optional) Your preferred persistent user password (e.g. `Password123`, min 8 chars).
   - `SEED_USERS`: (Optional) Additional comma-separated users (`user1:pass1,user2:pass2`).
   - `DATA_DIR`: (Optional) Path to a mounted Render Persistent Disk (e.g. `/data`) for full permanent storage of dynamic registrations.
4. Render automatically reads the Dockerfile, binds to `$PORT`, and serves the application with automated HTTPS.

---

## Default Accounts & Cloud Persistence

On Render's Free tier, container file systems are stateless and spin down after 15 minutes of inactivity. To ensure seamless testing without having to re-register after spin-downs, ChainTickets automatically bootstraps standard accounts on startup:

| Account | Role | Default Password | Environment Variable Override |
| --- | --- | --- | --- |
| `admin` | `ADMIN` | Generated on first run (or set via env) | `ADMIN_PASSWORD` |
| `harsha_06` | `USER` | `harsha1234` | `USER_NAME` & `USER_PASSWORD` |
| `user` | `USER` | `user12345` | Built-in fallback |
| `alice` | `USER` | `correcthorse` | Built-in fallback |
| `bob` | `USER` | `correcthorse` | Built-in fallback |

- **One-Click Demo Login**: The `#/login` screen includes clickable chips (`harsha_06 / harsha1234`, `user / user12345`, `alice / correcthorse`, `admin`) that auto-fill the login form instantly.
- **Custom Persistent User on Render**: `render.yaml` pre-configures `USER_NAME=harsha_06` and `USER_PASSWORD=harsha1234`. You can also customize them under the Environment tab in the Render dashboard. ChainTickets ensures your account is always available whenever the container starts.
- **Full Ledger Persistence**: Attach a Render Persistent Disk (e.g. mounted at `/data`) and set `DATA_DIR=/data` to permanently keep all dynamic user registrations, ticket transfers, and blockchain blocks across container redeploys.

---

## File Layout

| File | Description |
| --- | --- |
| `Block.java` | Block record model containing index, timestamp, previousHash, hash, and data with SHA-256 computation |
| `Blockchain.java` | Append-only blockchain manager with `chain.dat` tab-separated persistence and cryptographic chain verification |
| `TicketSystem.java` | Domain logic for events, tickets, state replay from ledger, transfer validation, and block emission |
| `UserStore.java` | PBKDF2WithHmacSHA256 password hashing (600,000 iterations, 16-byte salt, constant-time verification) |
| `Sessions.java` | In-memory 256-bit cryptographic session token manager with 2-hour TTL and periodic expiration sweeps |
| `RequestStore.java` | Append-only `requests.dat` persistence with state replay for purchase requests and admin decisions |
| `AuthException.java` | HTTP status exception carrying 401 (Unauthorized) and 403 (Forbidden) codes |
| `Main.java` | Core HTTP server on `0.0.0.0:8090` (or `$PORT`), REST request dispatching, role enforcement, and static file serving |
| `index.html` | Semantic single-page application container, responsive meta tags, and accessibility markup |
| `style.css` | Tailored ivory and deep navy design system with fluid typography and full cross-device responsiveness |
| `app.js` | SPA router (`#/`), view controllers, and safe DOM construction via `createElement` and `textContent` |
| `Dockerfile` | Multi-stage Docker build with Eclipse Temurin JDK 21 build stage and slim JRE runtime |
| `render.yaml` | Cloud deployment blueprint for Render Docker Web Services |
| `.dockerignore` | Context exclusions for container image builds |
| `.gitignore` | Exclusions for local compiled classes, runtime logs, and data stores |
| `chain.dat` | Tab-separated ledger storage: `index \t timestamp \t previousHash \t hash \t data` |
| `users.dat` | Tab-separated user storage: `username \t role \t iterations \t salt \t hash` |
| `requests.dat` | Append-only request log: `requestId \t eventId \t username \t status \t timestamp` |

---

## API Reference

### Public Endpoints
| Method | Endpoint | Query / Body Parameters | Description |
| --- | --- | --- | --- |
| `GET` | `/api/events` | None | Returns list of all published events |
| `GET` | `/api/verify` | `?ticketId=<ticketId>` | Verify ticket authenticity, validity, and current owner |
| `POST` | `/api/auth/register` | `{"username":"...","password":"..."}` | Register a new user account (role `USER`) |
| `POST` | `/api/auth/login` | `{"username":"...","password":"..."}` | Authenticate and obtain session cookie |
| `POST` | `/api/auth/logout` | None | Clear session token and invalidate cookie |

### Authenticated Endpoints (Logged-In User)
| Method | Endpoint | Role Required | Body Parameters | Description |
| --- | --- | --- | --- | --- |
| `GET` | `/api/auth/me` | Any | None | Retrieve active user's username and role |
| `GET` | `/api/users` | Any | None | Retrieve list of registered usernames for transfer modal |
| `GET` | `/api/tickets` | Any | None | List tickets (`USER` sees owned; `ADMIN` sees all) |
| `GET` | `/api/requests/list` | Any | None | List ticket requests (`USER` sees own; `ADMIN` sees all) |
| `GET` | `/api/history` | Owner / Admin | `?ticketId=<ticketId>` | Retrieve complete block provenance trail for a ticket |
| `GET` | `/api/chain` | Any | None | Inspect all ledger blocks and cryptographic validity status |
| `POST` | `/api/requests/create`| `USER` | `{"eventId":"..."}` | Submit request to purchase a ticket |
| `POST` | `/api/tickets/transfer`| `USER` | `{"ticketId":"...","toUser":"..."}` | Transfer ticket ownership to another registered user |
| `POST` | `/api/events/create` | `ADMIN` | `{"name":"...","date":"...","venue":"...","totalTickets":...,"price":...}` | Publish a new event onto the blockchain |
| `POST` | `/api/requests/approve`| `ADMIN` | `{"requestId":"..."}` | Approve request and issue new ticket on the ledger |
| `POST` | `/api/requests/reject` | `ADMIN` | `{"requestId":"..."}` | Reject a ticket purchase request |
| `POST` | `/api/tickets/use` | `ADMIN` | `{"ticketId":"..."}` | Mark an active ticket as `USED` upon venue check-in |

### Demo / Tamper Endpoint
| Method | Endpoint | Access | Body Parameters | Description |
| --- | --- | --- | --- | --- |
| `POST` | `/api/demo/tamper` | Anyone (`--demo` only) | `{"index":...,"data":"..."}` | Modify in-memory block contents to demonstrate chain invalidation |

---

## Security Implementation Details

1. **Zero Framework Footprint**: Built solely on standard Java SE packages (`com.sun.net.httpserver`, `java.security`, `javax.crypto`). Eliminates supply-chain vulnerabilities, CVEs in third-party dependencies, and framework bloat.
2. **PBKDF2 Password Hashing**: Passwords are hashed using `PBKDF2WithHmacSHA256` with 600,000 iterations and a 16-byte random salt generated by `SecureRandom`.
3. **Timing-Attack Resistance**: When an unknown username is supplied to `/api/auth/login`, `UserStore` executes a constant-time dummy PBKDF2 hash computation using an internal benchmark salt, matching the computation time of valid accounts (~330ms) to prevent username enumeration.
4. **Strict Session Controls**: 256-bit cryptographically secure session IDs generated with `SecureRandom`, stored in memory with 2-hour sliding/fixed expiration and an automatic periodic background sweep.
5. **DOM XSS Immunity**: The frontend JavaScript single-page application builds all UI elements dynamically using `document.createElement()` and `textContent`. Server-provided content is never injected via `innerHTML`, `outerHTML`, or `insertAdjacentHTML`.
6. **Input & Ledger Delimiter Sanitization**: Event names, venue descriptions, and usernames are strictly stripped of pipe (`|`), tab (`\t`), and newline (`\r`, `\n`) characters before serialization to prevent TSV or ledger field injection.

---

## Documented Limitations

1. **Single Node Architecture**: This is an educational prototype running on a single server instance. It does not implement peer-to-peer gossip networks, distributed consensus protocols (e.g., Proof of Work, Proof of Stake, Raft), or Byzantine fault tolerance.
2. **No Login Rate Limiting or Lockout**: The authentication endpoints do not enforce brute-force IP rate limits or progressive account lockouts.
3. **In-Memory Session Storage**: Active sessions reside in memory and do not survive server restarts. All users must log in again if the server process restarts.
4. **Unprotected Off-Chain Storage**: `users.dat` and `requests.dat` are append-only flat files without cryptographic hash chaining or digital signatures. Modifications made directly to those files while the server is offline are not detected by blockchain validation.
5. **Server-Enforced Ownership**: Ticket ownership verification and transfer authorizations are verified via server session checks rather than asymmetric client-side public/private key cryptographic signatures.
6. **Tail Truncation Limitation**: Deleting blocks from the end of `chain.dat` is not detected by internal chain validation because the predecessor hash of each remaining block remains consistent. To detect tail truncation, external recording of the ledger anchor (head hash and block height) is required.
7. **Transport Layer Security**: The built-in HTTP server runs unencrypted plain HTTP. In production environments, it must be deployed behind an SSL/TLS termination proxy (e.g., Nginx, Caddy, Cloudflare, or Render).
8. **Public Ledger Transparency**: All authenticated users can inspect all blocks in the blockchain explorer. Block contents disclose event identifiers, ticket numbers, and usernames.
9. **Unauthenticated Demo Tamper Endpoint**: When the application is launched with `--demo`, the `/api/demo/tamper` endpoint is accessible without authentication to facilitate educational demonstrations.
10. **No Self-Service Password Reset**: There is no email verification or self-service password recovery mechanism. Administrator password recovery requires removing the admin record from `users.dat` and restarting the server (or supplying `ADMIN_PASSWORD`).
