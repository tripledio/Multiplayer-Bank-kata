package org.craftedsw.swift.ui

object ScoreboardHtml {

    fun render(): String = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>SWIFT Hub - Multiplayer Bank Kata Scoreboard</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css" rel="stylesheet">
    <script src="https://cdn.jsdelivr.net/npm/qrcodejs@1.0.0/qrcode.min.js"></script>
    <style>
        body { background-color: #0d1117; color: #c9d1d9; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
        .card { background-color: #161b22; border: 1px solid #30363d; border-radius: 8px; margin-bottom: 20px; }
        .card-header { background-color: #21262d; border-bottom: 1px solid #30363d; font-weight: 600; }
        .table { color: #c9d1d9; border-color: #30363d; }
        .table > :not(caption) > * > * { background-color: transparent; color: #c9d1d9; }
        .badge-healthy { background-color: #238636; }
        .badge-degraded { background-color: #d29922; color: #000; }
        .badge-down { background-color: #da3633; }
        .kpi-val { font-size: 2.2rem; font-weight: 700; color: #58a6ff; }
        .pulse { animation: pulse-animation 2s infinite; }
        @keyframes pulse-animation { 0% { opacity: 1; } 50% { opacity: 0.4; } 100% { opacity: 1; } }
        .btn-control { font-weight: 600; }
    </style>
</head>
<body class="p-4">
    <div class="container-fluid">
        <!-- Header -->
        <div class="d-flex justify-content-between align-items-center mb-4 pb-2 border-bottom border-secondary">
            <div>
                <h1 class="h3 text-white mb-0">🌐 SWIFT Network Clearing House</h1>
                <p class="text-secondary small mb-0">Unconference Multiplayer Banking Kata • Live Network Monitor</p>
            </div>
            <div class="d-flex align-items-center gap-2">
                <span id="liveBadge" class="badge bg-success pulse px-3 py-2">LIVE STREAM</span>
                <span id="clock" class="text-secondary font-monospace small">--:--:--</span>
            </div>
        </div>

        <!-- KPI Cards -->
        <div class="row g-3 mb-4">
            <div class="col-md-3">
                <div class="card p-3 text-center">
                    <div class="text-secondary small text-uppercase">Active Bank Nodes</div>
                    <div id="kpiBanks" class="kpi-val">0</div>
                </div>
            </div>
            <div class="col-md-3">
                <div class="card p-3 text-center">
                    <div class="text-secondary small text-uppercase">Settled Volume</div>
                    <div id="kpiVolume" class="kpi-val">€ 0.00</div>
                </div>
            </div>
            <div class="col-md-3">
                <div class="card p-3 text-center">
                    <div class="text-secondary small text-uppercase">Total Transactions</div>
                    <div id="kpiTransactions" class="kpi-val">0</div>
                </div>
            </div>
            <div class="col-md-3">
                <div class="card p-3 text-center">
                    <div class="text-secondary small text-uppercase">Network Error Rate</div>
                    <div id="kpiErrorRate" class="kpi-val text-warning">0.0%</div>
                </div>
            </div>
        </div>

        <div class="row">
            <!-- Leaderboard -->
            <div class="col-lg-7">
                <div class="card">
                    <div class="card-header d-flex justify-content-between align-items-center">
                        <span>🏆 Participant Bank Leaderboard</span>
                        <span class="badge bg-secondary" id="bankCountBadge">0 registered</span>
                    </div>
                    <div class="card-body p-0">
                        <div class="table-responsive">
                            <table class="table table-hover mb-0 align-middle">
                                <thead>
                                    <tr>
                                        <th class="ps-3">#</th>
                                        <th>BIC</th>
                                        <th>Bank Name</th>
                                        <th>Score</th>
                                        <th>Processed</th>
                                        <th>Success Rate</th>
                                        <th>Status</th>
                                    </tr>
                                </thead>
                                <tbody id="leaderboardBody">
                                    <tr><td colspan="7" class="text-center py-4 text-secondary">No banks registered yet. Run <code>./gradlew :bank-starter:run</code> to connect.</td></tr>
                                </tbody>
                            </table>
                        </div>
                    </div>
                </div>

                <!-- Facilitator Controls -->
                <div class="card">
                    <div class="card-header">⚡ Facilitator Simulation Controls</div>
                    <div class="card-body">
                        <div class="d-flex flex-wrap gap-2 align-items-center">
                            <button id="btnStartSim" class="btn btn-success btn-control" onclick="startSim()">▶ Start Traffic Sim</button>
                            <button id="btnStopSim" class="btn btn-danger btn-control" onclick="stopSim()">⏹ Stop Sim</button>
                            <button class="btn btn-primary btn-control" onclick="triggerBurst(5)">⚡ Fire 5 Tx Burst</button>
                            <button class="btn btn-warning btn-control" onclick="triggerBurst(20)">💥 Fire 20 Tx Surge</button>
                            <span id="simStatus" class="badge bg-secondary ms-auto">Simulator: Idle</span>
                        </div>
                    </div>
                </div>
            </div>

            <!-- Live Transaction Feed -->
            <div class="col-lg-5">
                <!-- Participant Registration & QR Code -->
                <div class="card mb-3">
                    <div class="card-header d-flex justify-content-between align-items-center">
                        <span>📱 Register Your Bank</span>
                        <a href="/register" target="_blank" class="badge bg-primary text-decoration-none px-2 py-1">Open Page ↗</a>
                    </div>
                    <div class="card-body">
                        <div class="d-flex align-items-center gap-3">
                            <div id="qrcode" class="p-2 bg-white rounded shadow-sm d-flex align-items-center justify-content-center" style="min-width: 100px; min-height: 100px;"></div>
                            <div>
                                <div class="fw-bold text-white mb-1">Scan to Register Bank</div>
                                <p class="text-secondary small mb-2">Scan with your phone to register your bank and get your generated 8-character BIC code.</p>
                                <div><a id="regUrlLink" href="/register" target="_blank" class="text-info font-monospace small text-break"></a></div>
                            </div>
                        </div>
                    </div>
                </div>

                <div class="card">
                    <div class="card-header d-flex justify-content-between align-items-center">
                        <span>📋 Live Settlement Feed</span>
                        <span class="small text-secondary">Recent 15</span>
                    </div>
                    <div class="card-body p-0">
                        <div class="table-responsive" style="max-height: 520px; overflow-y: auto;">
                            <table class="table table-sm table-striped mb-0 small">
                                <thead>
                                    <tr>
                                        <th class="ps-3">Tx ID</th>
                                        <th>Route</th>
                                        <th>Amount</th>
                                        <th>Status</th>
                                    </tr>
                                </thead>
                                <tbody id="feedBody">
                                    <tr><td colspan="4" class="text-center py-4 text-secondary">Awaiting transactions...</td></tr>
                                </tbody>
                            </table>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    </div>

    <script>
        function updateClock() {
            document.getElementById('clock').innerText = new Date().toLocaleTimeString();
        }
        setInterval(updateClock, 1000);
        updateClock();

        function formatCents(cents) {
            return '€ ' + (cents / 100).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
        }

        async function pollState() {
            try {
                const res = await fetch('/swift/metrics');
                if (!res.ok) return;
                const data = await res.json();
                renderScoreboard(data);

                const ledgerRes = await fetch('/swift/ledger');
                if (ledgerRes.ok) {
                    const ledgerData = await ledgerRes.json();
                    renderLedger(ledgerData.recentEntries || []);
                }
            } catch (err) {
                console.warn('Poll error:', err);
            }
        }

        function renderScoreboard(data) {
            const m = data.metrics || {};
            document.getElementById('kpiBanks').innerText = m.activeBanksCount || 0;
            document.getElementById('kpiVolume').innerText = formatCents(m.totalVolumeCents || 0);
            document.getElementById('kpiTransactions').innerText = m.totalTransactions || 0;
            document.getElementById('kpiErrorRate').innerText = (m.errorRatePercentage || 0).toFixed(1) + '%';
            document.getElementById('bankCountBadge').innerText = (data.banks || []).length + ' registered';

            const tbody = document.getElementById('leaderboardBody');
            if (!data.banks || data.banks.length === 0) {
                tbody.innerHTML = '<tr><td colspan="7" class="text-center py-4 text-secondary">No banks registered yet. Run <code>./gradlew :bank-starter:run</code> to connect.</td></tr>';
                return;
            }

            let html = '';
            data.banks.forEach((b, index) => {
                const total = b.totalTransactions || 0;
                const success = b.successfulTransactions || 0;
                const rate = total > 0 ? ((success / total) * 100).toFixed(0) + '%' : '100%';
                const badgeClass = b.status === 'HEALTHY' ? 'badge-healthy' : (b.status === 'DEGRADED' ? 'badge-degraded' : 'badge-down');

                html += `
                    <tr>
                        <td class="ps-3 font-monospace text-secondary">${'$'}{index + 1}</td>
                        <td><span class="badge bg-dark border border-secondary font-monospace">${'$'}{b.bic}</span></td>
                        <td class="fw-bold text-white">${'$'}{b.name}</td>
                        <td class="text-primary fw-bold font-monospace">${'$'}{b.score} pts</td>
                        <td>${'$'}{b.totalTransactions}</td>
                        <td><span class="text-success">${'$'}{rate}</span></td>
                        <td><span class="badge ${'$'}{badgeClass}">${'$'}{b.status}</span></td>
                    </tr>
                `;
            });
            tbody.innerHTML = html;
        }

        function renderLedger(entries) {
            const feed = document.getElementById('feedBody');
            if (!entries || entries.length === 0) {
                feed.innerHTML = '<tr><td colspan="4" class="text-center py-4 text-secondary">Awaiting transactions...</td></tr>';
                return;
            }

            let html = '';
            entries.slice(0, 15).forEach(e => {
                const statusBadge = e.status === 'ACCEPTED' ? 'bg-success' : (e.status === 'REJECTED' ? 'bg-warning text-dark' : 'bg-danger');
                const fromBic = e.fromIban.length >= 12 ? e.fromIban.substring(4, 12) : e.fromIban;
                const toBic = e.toIban.length >= 12 ? e.toIban.substring(4, 12) : e.toIban;

                html += `
                    <tr>
                        <td class="ps-3 font-monospace text-secondary">${'$'}{e.transactionId}</td>
                        <td><code>${'$'}{fromBic}</code> → <code>${'$'}{toBic}</code></td>
                        <td class="fw-bold">${'$'}{formatCents(e.amountCents)}</td>
                        <td><span class="badge ${'$'}{statusBadge}">${'$'}{e.status}</span></td>
                    </tr>
                `;
            });
            feed.innerHTML = html;
        }

        // SSE or regular polling fallback
        setInterval(pollState, 1500);
        pollState();

        function initQrCode() {
            const regUrl = new URL('/register', window.location.href).href;
            const linkEl = document.getElementById('regUrlLink');
            if (linkEl) {
                linkEl.innerText = regUrl;
                linkEl.href = regUrl;
            }
            const qrEl = document.getElementById('qrcode');
            if (qrEl && typeof QRCode !== 'undefined') {
                qrEl.innerHTML = '';
                new QRCode(qrEl, {
                    text: regUrl,
                    width: 100,
                    height: 100,
                    colorDark: '#000000',
                    colorLight: '#ffffff',
                    correctLevel: QRCode.CorrectLevel.M
                });
            }
        }
        initQrCode();

        async function startSim() {
            await fetch('/swift/simulator/start', { method: 'POST' });
            document.getElementById('simStatus').innerText = 'Simulator: Running (2s interval)';
            document.getElementById('simStatus').className = 'badge bg-success ms-auto';
        }

        async function stopSim() {
            await fetch('/swift/simulator/stop', { method: 'POST' });
            document.getElementById('simStatus').innerText = 'Simulator: Stopped';
            document.getElementById('simStatus').className = 'badge bg-secondary ms-auto';
        }

        async function triggerBurst(count) {
            await fetch('/swift/simulator/burst?count=' + count, { method: 'POST' });
            pollState();
        }
    </script>
</body>
</html>
    """.trimIndent()
}
