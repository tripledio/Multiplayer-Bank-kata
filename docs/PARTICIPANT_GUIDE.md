# 🏦 Participant Quickstart Guide: Multiplayer Banking Kata

Welcome to the **Multiplayer Banking Kata**! In this session, you and your pairing partner will build a resilient banking node in Kotlin, practice **Outside-In TDD** and **Object Calisthenics**, and connect your node to a live inter-bank **SWIFT network** alongside all other workshop participants.

---

## ⚡ 5-Minute Quickstart

### 1. Prerequisites
- **JDK 25** (configured via `.sdkmanrc`)
- **Node.js** (for `npx localtunnel`) or `ngrok`

### 2. Verify Build & Run Tests
From the repository root:
```bash
./gradlew test
```
All unit tests in `:bank-starter` should run and pass in ~1-2 seconds.

### 3. Expose Your Bank via Tunnel
In a terminal tab, expose your local port `8080` to the network / internet:
```bash
npx localtunnel --port 8080
```
*Note the public HTTPS URL printed (e.g., `https://brave-fox-42.loca.lt`).*

### 4. Register Your Bank with the SWIFT Hub
Visit the registration page on the facilitator's SWIFT Hub:
- **Registration URL**: `http://<FACILITATOR_IP>:9000/register` (or scan the **QR code** on the live facilitator scoreboard)
- Enter your **Bank Name** (e.g., `Bank Alpha`) and **Webhook URL** (e.g., `https://brave-fox-42.loca.lt`).
- The backend will generate and return your assigned 8-character ASCII **BIC code** (e.g. `BANKXXXX`).

*(Alternative CLI option)*:
```bash
curl -X POST http://<FACILITATOR_IP>:9000/swift/register \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Bank Alpha",
    "webhookUrl": "https://brave-fox-42.loca.lt"
  }'
```

### 5. Start Your Bank Node
Launch your bank application with your assigned 8-character BIC code:
```bash
PORT=8080 BIC=<YOUR_GENERATED_BIC> BANK_NAME="Bank Alpha" ./gradlew :bank-starter:run
```
Watch your bank appear on the live projector scoreboard!

---

## 🎯 Workshop Milestones & TDD Progression

### Round 1: Core Domain (00:15 - 00:45)
- Open `bank-starter/src/main/kotlin/org/craftedsw/bank/domain/`.
- Practice TDD to implement and refine:
  - Internal deposits and balance calculations.
  - Withdrawals and overdraw protections.
  - Account statement printing in reverse chronological order.
  - Statement filters (e.g., deposits only, withdrawals only, date ranges).
- Maintain 100% test coverage using JUnit 5 and AssertJ/MockK.

### Round 2: The SWIFT Network & Webhooks (00:45 - 01:15)
- Open `bank-starter/src/main/kotlin/org/craftedsw/bank/api/BankRoutes.kt`.
- Interact with and test your bank node using either **IntelliJ HTTP Client (Recommended)** or **Traditional `curl` CLI**:

#### Option A: IntelliJ HTTP Client (Recommended - Zero Terminal Juggling)
Open `requests/bank-api.http` directly inside IntelliJ IDEA:
1. Select your target environment (e.g. `local-alpha` or `workshop-live`) in the top-right environment dropdown.
2. Click the green play button (`▶`) next to any request to execute it with instant syntax highlighting and inline response assertions:
   - **`# 2. Internal Customer Deposit`**: Credits 1,000.00 EUR to your account.
   - **`# 3. Internal Customer Withdrawal`**: Debits 250.00 EUR from your account.
   - **`# 4. Query Account Statement`**: Displays full statement table and running balance.
   - **`# 6. Outgoing Cross-Bank Transfer`**: Dispatches a transfer through the SWIFT Hub to another team.
   - **`# 7. Direct Incoming Webhook Simulation`**: Tests your node's `POST /api/transfer-in` webhook handler.

---

#### Option B: Traditional Terminal `curl` Commands
1. **Deposit Funds (`POST /api/deposit`)**:
   ```bash
   curl -X POST http://localhost:8080/api/deposit \
     -H "Content-Type: application/json" \
     -d '{"iban": "BE68BANKA0001234567", "amountCents": 100000}'
   ```
2. **Withdraw Funds (`POST /api/withdraw`)**:
   ```bash
   curl -X POST http://localhost:8080/api/withdraw \
     -H "Content-Type: application/json" \
     -d '{"iban": "BE68BANKA0001234567", "amountCents": 30000}'
   ```
3. **Query Statement (`GET /api/statement/{iban}` or `GET /api/statement?iban=...`)**:
   ```bash
   curl -s http://localhost:8080/api/statement/BE68BANKA0001234567
   ```
4. **Incoming SWIFT Transfer Webhook (`POST /api/transfer-in`)**:
   ```bash
   curl -X POST http://localhost:8080/api/transfer-in \
     -H "Content-Type: application/json" \
     -d '{
       "transactionId": "tx-1001",
       "fromIban": "BE68BANKB0001234567",
       "toIban": "BE68BANKA0009876543",
       "amountCents": 150000,
       "timestamp": 1727785800000,
       "reference": "Consulting Invoice"
     }'
   ```
5. **Outgoing Cross-Bank Transfer (`POST /api/transfer-out`)**:
   ```bash
   curl -X POST http://localhost:8080/api/transfer-out \
     -H "Content-Type: application/json" \
     -d '{
       "transactionId": "tx-1002",
       "fromIban": "BE68BANKA0009876543",
       "toIban": "BE68BANKB0001234567",
       "amountCents": 50000,
       "reference": "Split Lunch"
     }'
   ```

### Round 3: Live Traffic & Chaos Survival (01:15 - 01:40)
- The facilitator will ramp up simulated customer traffic!
- Your bank will receive continuous bursts of deposits, withdrawals, and cross-bank transfers.
- **Goals for Round 3**:
  - Keep response times under 500ms for maximum points.
  - Handle malformed payloads, zero/negative amounts, and non-existent accounts gracefully (`400 Bad Request` or `422 Unprocessable Entity`).
  - Maintain zero balance discrepancies.

---

## 🧘 The 9 Object Calisthenics Rules in Kotlin

1. **Only One Level of Indentation per Method**: Use Kotlin expressions, functions, and standard library combinators (`map`, `filter`).
2. **Don't Use the `else` Keyword**: Use guard clauses, early returns, and Kotlin `when` expressions.
3. **Wrap All Primitives and Strings**: Use `@JvmInline value class Amount(val value: Int)` or `value class Iban(val value: String)`.
4. **First-Class Collections**: Wrap lists in dedicated domain aggregates (`Statement`, `AuditLedger`).
5. **One Dot per Line**: Maintain Law of Demeter.
6. **Don't Abbreviate**: Use full, intention-revealing names (`Transaction`, `StatementLine`).
7. **Keep All Entities Small**: Maximum 50 lines per class/file.
8. **No Classes with More Than Two Instance Variables**: Encourage high cohesion.
9. **No Getters / Setters / Properties for State Extraction**: Tell, Don't Ask! Pass printers or visitors to objects rather than extracting internal state.
