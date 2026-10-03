# 🌐 Multiplayer Banking Kata (Unconference Edition)

An interactive, multi-node evolution of the classic Bank Account Kata designed for **1–2 hour unconference workshop sessions**. 

Participants implement their own bank node in Kotlin using **Outside-In TDD** and **Object Calisthenics**, then connect their node to a live **SWIFT Clearing House** and battle on the real-time scoreboard under live customer traffic.

---

## 🚀 Quick Links & Guides

- 📖 **[Participant Quickstart Guide](docs/PARTICIPANT_GUIDE.md)**: 5-minute setup, TDD milestones, and webhook specs.
- 🎙️ **[Facilitator Workshop Guide](docs/FACILITATOR_GUIDE.md)**: 2-hour agenda, live scoreboard setup, traffic burst controls.

---

## 🏗️ Repository Architecture

This repository is built with **Gradle Kotlin DSL (`build.gradle.kts`)** and **Kotlin 2.x** targeting **JVM 25**:

```
Bank-kata/
├── build.gradle.kts
├── settings.gradle.kts
├── requests/                    # IntelliJ HTTP Client request suite & environments
│   ├── http-client.env.json     # Environment variables (local-alpha, local-beta, workshop-live)
│   ├── bank-api.http            # Participant bank API test collection (click-to-run)
│   └── swift-hub.http           # Facilitator SWIFT network & simulator controls
├── shared-contracts/            # Shared DTOs, BIC/IBAN validation models, JSON serializers
│   └── src/main/kotlin/org/craftedsw/contracts/
├── bank-starter/                # Participant bank starter template
│   ├── src/main/kotlin/org/craftedsw/bank/
│   │   ├── domain/              # Calisthenics domain (Account, Amount, Statement, etc.)
│   │   ├── api/                 # Embedded Ktor HTTP webhook routes
│   │   ├── client/              # SWIFT network client
│   │   └── BankApplication.kt   # Runnable entrypoint
│   └── src/test/kotlin/org/craftedsw/bank/
├── swift-hub/                   # Facilitator SWIFT network clearing house & scoreboard
│   ├── src/main/kotlin/org/craftedsw/swift/
│   │   ├── router/              # BIC registry, transfer router, audit ledger
│   │   ├── simulator/           # Customer traffic & surge generator
│   │   ├── ui/                  # Live web scoreboard dashboard
│   │   └── SwiftHubApplication.kt
│   └── src/test/kotlin/org/craftedsw/swift/
├── docs/                        # Facilitator and participant documentation
└── scripts/                     # Helper launch and tunnel scripts
```

---

## ⚡ Running the Project

### 1. Run all tests
```bash
./gradlew test
```

### 2. Start the Facilitator SWIFT Hub (Port 9000)
```bash
./gradlew :swift-hub:run
# Open live scoreboard dashboard at http://localhost:9000
```

### 3. Register Your Bank & Get BIC Code
Visit the registration page at `http://<FACILITATOR_IP>:9000/register` (or scan the QR code on the live facilitator scoreboard).
Enter your Bank Name and Webhook URL to receive your assigned 8-character ASCII BIC code (e.g. `BANKXXXX`).

### 4. Start a Participant Bank (Port 8080)
```bash
PORT=8080 BIC=<YOUR_GENERATED_BIC> BANK_NAME="Bank Alpha" ./gradlew :bank-starter:run
```

### 5. Interactive API Testing (IntelliJ HTTP Client)
- Open `requests/bank-api.http` to test participant bank operations (deposit, withdraw, statements, inter-bank transfers).
- Open `requests/swift-hub.http` to trigger simulated traffic bursts or inspect the central audit ledger.
- Choose your environment (`local-alpha`, `local-beta`, `workshop-live`) from the top-right dropdown and click `▶` on any request.

---

## 🧘 The 9 Object Calisthenics Constraints

1. **Only One Level of Indentation per Method**
2. **Don’t Use the `else` Keyword**
3. **Wrap All Primitives and Strings** (e.g. `@JvmInline value class Amount(val value: Int)`)
4. **First-Class Collections** (e.g. `Statement`, `AuditLedger`)
5. **One Dot per Line** (Law of Demeter)
6. **Don’t Abbreviate**
7. **Keep All Entities Small** (<= 50 lines)
8. **No Classes with More Than Two Instance Variables**
9. **No Getters / Setters / Properties for State Extraction** (Tell-Don't-Ask)
