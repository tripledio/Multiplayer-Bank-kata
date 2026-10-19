# 🏦 Participant Guide: Multiplayer Banking Kata

## 🥑 Welcome to the Hipster FinTech Revolution!

**Congratulations!** You and your pairing partner have just been picked to build the core IT system and transaction engine for a brand-new, up-and-coming **hipster neo-bank** (think artisanal accounts, craft transactions, and locally sourced micro-balances). ☕✨

---

### 📖 The Background Story

Picture this: Your founders—sporting raw Japanese selvedge denim, riding vintage single-speed bamboo bicycles, and fueled exclusively by single-origin oat milk flat whites—just pitched an audacious vision to venture capitalists. The world doesn't need another soulless corporate conglomerate; it needs an ethical, friction-free, bespoke digital bank where every euro is treated like an artisanal pour-over.

The seed funding is secured, the launch party is already being planned at a converted warehouse roastery, and the community is buzzing. Now, the spotlight is on you: **the founding engineering team**.

---

### 💡 The Lean & Agile Playbook: Ship Early, Learn from Feedback Loops

In traditional enterprise banking, teams spend six months drawing UML diagrams, negotiating 400-page waterfall specification documents, and praying that everything magically integrates during a weekend release. 

**Not here.**

In true Lean Startup and Agile spirit, our hipster bank operates on **rapid feedback loops**, **continuous product discovery**, and **evolutionary architecture**:

- 🚀 **Deliver Value Over Big-Bang Waterfalling**: Better to get small, working vertical slices (like basic deposits and withdrawals) into production immediately and earn points from real customers than spending the entire session gold-plating an unverified monolith in isolation.
- 🔄 **Build-Measure-Learn Feedback Loops**: Deploy an MVP (Minimum Viable Product), wire your bank node into the live SWIFT clearing house, and let actual customer traffic and scoreboard metrics tell you how you're performing.
- 🔍 **Continuous Product Discovery**: Treat every scenario as a learning hypothesis. As new customer demands and inter-bank clearing requirements emerge, discover what your domain model truly needs rather than over-engineering upfront.
- 🧘 **Outside-In TDD & Object Calisthenics**: Write clean, test-driven domain logic guided by Calisthenics rules. Refactor mercilessly with the safety net of fast-running unit and acceptance tests.
- 🛡️ **Fail Fast & Adapt**: When unexpected edge cases, rogue negative amounts, or traffic surges hit your endpoints, pivot quickly, patch your guards, and keep your balances watertight.

---

### 🎨 A Fun Manual Challenge: Human Craftsmanship Over AI Prompts

This kata is designed to be a **fun, collaborative, hands-on programming challenge**. While prompting an AI assistant to generate `BankService` might be trivial, doing so takes all the fun, discovery, and learning out of the experience!

Here is what will genuinely put you ahead and build a resilient bank:
- 🧠 **Think Functionally & Customer-Oriented**: Design your API and business flows from the outside in, keeping the customer experience front and center. Modeling state transitions with pure functions, expressive types, and clear domain invariants keeps your code robust.
- 🏛️ **Give Your Domain Some Body**: Avoid anemic CRUD structures with primitive obsession (raw strings and ints everywhere). Give your domain rich models, encapsulated business rules, and real domain language (e.g. dedicated value objects and aggregates).
- 🛡️ **Prevent Regressions with Strong Test Nets**: As new phases unlock and traffic ramps up, you will continuously adapt your logic. Fast, comprehensive tests ensure you never break previously delivered functionality or leak customer money.
- 🤝 **Pair & Hone Your Craft**: Enjoy pairing with your partner, discussing design trade-offs, and experiencing the thrill of seeing your green tests translate to live scoreboard points!

Grab your pairing partner, spin up your tunnel, and let's disrupt modern banking one test at a time! 🚀

---

## ⚡ 5-Minute Quickstart

### 1. Prerequisites
- **JDK 25** (configured via `.sdkmanrc`)
- **Node.js** (for `npx localtunnel`) or `ngrok`

### 2. Verify Build & Run Tests
From the repository root:
```bash
./gradlew :bank-starter:test
```
*The starter test compiles cleanly and fails with `NotImplementedError` at runtime, ready for you to drive the implementation outside-in!*

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
- Open `bank-starter/src/main/kotlin/org/craftedsw/bank/service/BankService.kt` and `bank-starter/src/test/kotlin/org/craftedsw/bank/service/BankServiceTest.kt`.
- Practice Outside-In TDD to implement and refine:
  - Internal deposits and balance calculations.
  - Withdrawals and overdraw protections.
  - Account statement queries in reverse chronological order with running balance.
  - Create and shape rich domain abstractions in `org.craftedsw.bank.domain`.
- Maintain high test coverage using JUnit 5 and AssertJ.

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
