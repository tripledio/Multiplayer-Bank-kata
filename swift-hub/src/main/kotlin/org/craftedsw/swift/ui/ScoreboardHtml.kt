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
    <script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
    <script src="https://cdn.jsdelivr.net/npm/qrcodejs@1.0.0/qrcode.min.js"></script>
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
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
        .graph-container { position: relative; width: 100%; height: 380px; background: #0d1117; border-radius: 6px; overflow: hidden; border: 1px solid #21262d; }
        .graph-svg { width: 100%; height: 100%; display: block; }
        .graph-node { cursor: grab; transition: transform 0.1s ease; }
        .graph-node:active { cursor: grabbing; }
        .graph-edge { fill: none; stroke-linecap: round; transition: stroke-width 0.2s, stroke 0.2s; }
        .edge-label-bg { fill: #161b22; stroke: #30363d; stroke-width: 1px; rx: 4px; }
        .edge-label-text { fill: #58a6ff; font-family: monospace; font-size: 11px; font-weight: 600; text-anchor: middle; dominant-baseline: middle; }
        .edge-label-subtext { fill: #8b949e; font-family: monospace; font-size: 9px; text-anchor: middle; dominant-baseline: middle; }
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

        <!-- Top Section: Leaderboard & Registration -->
        <div class="row g-3 mb-4">
            <!-- Leaderboard -->
            <div class="col-lg-8">
                <div class="card h-100">
                    <div class="card-header d-flex justify-content-between align-items-center">
                        <span>🏆 Participant Bank Leaderboard</span>
                        <div class="d-flex align-items-center gap-2">
                            <button class="btn btn-xs btn-outline-info py-0 px-2" style="font-size: 0.75rem;" onclick="pingAllBanks()" title="Test network connectivity to all banks">⚡ Ping All</button>
                            <span class="badge bg-secondary" id="bankCountBadge">0 registered</span>
                        </div>
                    </div>
                    <div class="card-body p-0">
                        <div class="table-responsive">
                            <table class="table table-hover mb-0 align-middle">
                                <thead>
                                    <tr>
                                        <th class="ps-3">#</th>
                                        <th>BIC</th>
                                        <th>Bank Name</th>
                                        <th>Total Money</th>
                                        <th>Score</th>
                                        <th>Processed</th>
                                        <th>Success Rate</th>
                                        <th>Network health</th>
                                        <th class="text-end pe-3">Actions</th>
                                    </tr>
                                </thead>
                                <tbody id="leaderboardBody">
                                    <tr><td colspan="9" class="text-center py-4 text-secondary">No banks registered yet. Run <code>./gradlew :bank-starter:run</code> to connect.</td></tr>
                                </tbody>
                            </table>
                        </div>
                    </div>
                </div>
            </div>

            <!-- Participant Registration & QR Code -->
            <div class="col-lg-4">
                <div class="card h-100">
                    <div class="card-header d-flex justify-content-between align-items-center">
                        <span>📱 Register Your Bank</span>
                        <a href="/register" target="_blank" class="badge bg-primary text-decoration-none px-2 py-1">Open Page ↗</a>
                    </div>
                    <div class="card-body d-flex flex-column justify-content-center">
                        <div class="d-flex align-items-center gap-3">
                            <div id="qrcode" class="p-2 bg-white rounded shadow-sm d-flex align-items-center justify-content-center" style="min-width: 100px; min-height: 100px;"></div>
                            <div>
                                <div class="fw-bold text-white mb-1">Scan to Register Bank</div>
                                <p class="text-secondary small mb-2">Scan with your phone or open the register page to get your generated 8-character BIC code.</p>
                                <div><a id="regUrlLink" href="/register" target="_blank" class="text-info font-monospace small text-break"></a></div>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </div>

        <!-- 3 Phase Scenarios & Visualizations Header -->
        <div class="d-flex justify-content-between align-items-center mb-3">
            <div>
                <h2 class="h5 text-white mb-0">🧪 Test Simulator Scenarios &amp; Visualizations</h2>
                <span class="text-secondary small">• Automated evaluation every 10s • 1 point per scenario</span>
            </div>
            <div class="d-flex gap-2 align-items-center">
                <span id="simStatus" class="badge bg-secondary">Traffic: Idle</span>
                <button id="btnToggleSim" class="btn btn-sm btn-outline-secondary" onclick="toggleSim()">⚡ Background Traffic</button>
            </div>
        </div>

        <!-- Manual Scenario Detailed Results Box -->
        <div id="scenarioResultBox" class="mb-3 d-none">
            <div class="alert alert-dark border-secondary mb-0 p-3" role="alert">
                <div class="d-flex justify-content-between align-items-center mb-2 pb-1 border-bottom border-secondary">
                    <strong class="text-info" id="scenarioResultTitle">🧪 Test Scenario Results</strong>
                    <button type="button" class="btn-close btn-close-white btn-sm" onclick="dismissScenarioResults()" aria-label="Close"></button>
                </div>
                <div id="scenarioResultContent"></div>
            </div>
        </div>

        <!-- 3 Scenario Columns with Embedded Visualizations -->
        <div class="row g-3 mb-4">
            <!-- Column 1: Deposits -->
            <div class="col-lg-4">
                <!-- Phase 1: Deposits Scenario Card -->
                <div class="card bg-black bg-opacity-25 border-info mb-3">
                    <div class="card-header d-flex justify-content-between align-items-center py-2">
                        <div>
                            <span class="fw-bold text-info">1. Deposits</span>
                            <div class="small text-secondary" style="font-size: 0.75rem;" id="phaseSubtitleDeposits">Automated evaluation</div>
                        </div>
                        <span id="phaseTimerDeposits" class="badge bg-dark border border-info font-monospace text-info">00:00</span>
                    </div>
                    <div class="card-body d-flex flex-column p-3">
                        <div class="d-flex justify-content-between align-items-center mb-2">
                            <button id="btnPhaseDeposits" class="btn btn-sm btn-success fw-bold px-3" onclick="togglePhase('DEPOSITS')">▶ Play</button>
                            <button class="btn btn-sm btn-outline-info" onclick="runDepositScenario('deposit')">⚡ Run Now</button>
                        </div>
                        <div class="small text-secondary mb-2" id="phaseDescDeposits">
                            • Scenario 1 (Hidden)<br>
                            • Scenario 2 (Hidden)
                        </div>
                        <div id="phaseResultsDeposits" class="mt-auto border-top border-secondary pt-2 small text-secondary">
                            <em>Awaiting first evaluation...</em>
                        </div>
                    </div>
                </div>

                <!-- Deposits Money Line Chart -->
                <div class="card border-secondary">
                    <div class="card-header d-flex justify-content-between align-items-center py-2">
                        <span class="small fw-bold text-light">📈 Deposits Money Timeline</span>
                        <span class="badge bg-dark border border-secondary text-info font-monospace small" id="depositsMoneyTotalBadge">€ 0.00</span>
                    </div>
                    <div class="card-body p-2">
                        <div style="position: relative; height: 260px;">
                            <canvas id="depositMoneyLineChart"></canvas>
                        </div>
                        <div id="depositMoneyEmptyNotice" class="text-center py-4 text-secondary small d-none">
                            Awaiting registered banks...
                        </div>
                    </div>
                </div>
            </div>

            <!-- Column 2: Withdrawals -->
            <div class="col-lg-4">
                <!-- Phase 2: Withdrawals Scenario Card -->
                <div class="card bg-black bg-opacity-25 border-warning mb-3">
                    <div class="card-header d-flex justify-content-between align-items-center py-2">
                        <div>
                            <span class="fw-bold text-warning">2. Withdrawals</span>
                            <div class="small text-secondary" style="font-size: 0.75rem;" id="phaseSubtitleWithdrawals">Automated evaluation</div>
                        </div>
                        <span id="phaseTimerWithdrawals" class="badge bg-dark border border-warning font-monospace text-warning">00:00</span>
                    </div>
                    <div class="card-body d-flex flex-column p-3">
                        <div class="d-flex justify-content-between align-items-center mb-2">
                            <button id="btnPhaseWithdrawals" class="btn btn-sm btn-success fw-bold px-3" onclick="togglePhase('WITHDRAWALS')">▶ Play</button>
                            <button class="btn btn-sm btn-outline-warning" onclick="runDepositScenario('withdraw')">⚡ Run Now</button>
                        </div>
                        <div class="small text-secondary mb-2" id="phaseDescWithdrawals">
                            • Scenario 1 (Hidden)<br>
                            • Scenario 2 (Hidden)<br>
                            • Scenario 3 (Hidden)
                        </div>
                        <div id="phaseResultsWithdrawals" class="mt-auto border-top border-secondary pt-2 small text-secondary">
                            <em>Awaiting first evaluation...</em>
                        </div>
                    </div>
                </div>

                <!-- Withdrawals Money Line Chart -->
                <div class="card border-secondary">
                    <div class="card-header d-flex justify-content-between align-items-center py-2">
                        <span class="small fw-bold text-light">📉 Withdrawals Money Timeline</span>
                        <span class="badge bg-dark border border-secondary text-warning font-monospace small" id="withdrawalsMoneyTotalBadge">€ 0.00</span>
                    </div>
                    <div class="card-body p-2">
                        <div style="position: relative; height: 260px;">
                            <canvas id="withdrawalMoneyLineChart"></canvas>
                        </div>
                        <div id="withdrawalMoneyEmptyNotice" class="text-center py-4 text-secondary small d-none">
                            Awaiting registered banks...
                        </div>
                    </div>
                </div>
            </div>

            <!-- Column 3: Transfers (Scenarios, Graph, and Transactions Feed) -->
            <div class="col-lg-4">
                <!-- Phase 3: Transfers Scenario Card -->
                <div class="card bg-black bg-opacity-25 border-primary mb-3">
                    <div class="card-header d-flex justify-content-between align-items-center py-2">
                        <div>
                            <span class="fw-bold text-primary">3. Transfers</span>
                            <div class="small text-secondary" style="font-size: 0.75rem;" id="phaseSubtitleTransfers">Automated evaluation</div>
                        </div>
                        <span id="phaseTimerTransfers" class="badge bg-dark border border-primary font-monospace text-primary">00:00</span>
                    </div>
                    <div class="card-body d-flex flex-column p-3">
                        <div class="d-flex justify-content-between align-items-center mb-2">
                            <button id="btnPhaseTransfers" class="btn btn-sm btn-success fw-bold px-3" onclick="togglePhase('TRANSFERS')">▶ Play</button>
                            <button class="btn btn-sm btn-outline-primary" onclick="runDepositScenario('transfer')">⚡ Run Now</button>
                        </div>
                        <div class="small text-secondary mb-2" id="phaseDescTransfers">
                            • Scenario 1 (Hidden)<br>
                            • Scenario 2 (Hidden)
                        </div>
                        <div id="phaseResultsTransfers" class="mt-auto border-top border-secondary pt-2 small text-secondary">
                            <em>Awaiting first evaluation...</em>
                        </div>
                    </div>
                </div>

                <!-- Inter-Bank Transfer Network Graph (Nodes & Edges) -->
                <div class="card border-primary mb-3">
                    <div class="card-header bg-dark bg-gradient d-flex justify-content-between align-items-center py-2">
                        <div class="d-flex align-items-center gap-1">
                            <span class="small fw-bold text-white">🌐 Transfer Network Graph</span>
                            <span class="badge bg-secondary" style="font-size: 0.7rem;" id="graphNodesBadge">0 nodes</span>
                        </div>
                        <div class="d-flex gap-1 align-items-center">
                            <button class="btn btn-xs btn-outline-secondary py-0 px-2" style="font-size: 0.75rem;" onclick="resetGraphLayout()" title="Rearrange nodes in a circular layout">🔄 Reset</button>
                        </div>
                    </div>
                    <div class="card-body p-1">
                        <div class="graph-container" id="graphContainer" style="height: 250px;">
                            <svg id="networkGraphSvg" class="graph-svg" viewBox="0 0 600 340" preserveAspectRatio="xMidYMid meet">
                                <defs>
                                    <marker id="arrow-head" viewBox="0 0 10 10" refX="28" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                                        <path d="M 0 1.5 L 8 5 L 0 8.5 z" fill="#58a6ff" />
                                    </marker>
                                    <marker id="arrow-head-hover" viewBox="0 0 10 10" refX="28" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                                        <path d="M 0 1 L 9 5 L 0 9 z" fill="#39c5bb" />
                                    </marker>
                                    <filter id="node-glow" x="-20%" y="-20%" width="140%" height="140%">
                                        <feGaussianBlur stdDeviation="3" result="blur" />
                                        <feComposite in="SourceGraphic" in2="blur" operator="over" />
                                    </filter>
                                </defs>
                                <g id="graphEdgesLayer"></g>
                                <g id="graphNodesLayer"></g>
                            </svg>
                            <div id="graphEmptyNotice" class="position-absolute top-50 start-50 translate-middle text-center text-secondary small d-none">
                                <div class="fs-4 mb-1">🕸️</div>
                                <div style="font-size: 0.75rem;">Awaiting registered banks &amp; transfers...</div>
                            </div>
                            <div id="graphTooltip" class="position-absolute p-2 bg-black bg-opacity-75 border border-secondary rounded shadow small text-light d-none" style="pointer-events: none; z-index: 100; font-size: 0.75rem;"></div>
                        </div>
                    </div>
                </div>

                <!-- Live Settlement Feed / Transactions Table -->
                <div class="card border-secondary">
                    <div class="card-header d-flex justify-content-between align-items-center py-2">
                        <span class="small fw-bold text-light">📋 Live Settlement Feed</span>
                        <span class="badge bg-secondary" id="graphVolumeSummary" style="font-size: 0.7rem;">Transferred: € 0.00</span>
                    </div>
                    <div class="card-body p-0">
                        <div class="table-responsive" style="max-height: 220px; overflow-y: auto;">
                            <table class="table table-sm table-striped mb-0 small">
                                <thead>
                                    <tr>
                                        <th class="ps-2">Tx ID</th>
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

        <!-- Bottom Row: 1 Graph Showing the Evolution of Points (Not Money) -->
        <div class="row mb-4">
            <div class="col-12">
                <div class="card border-warning">
                    <div class="card-header d-flex justify-content-between align-items-center">
                        <span class="fw-bold text-warning">🏆 Participant Points Evolution (Score Timeline)</span>
                        <span class="badge bg-warning text-dark font-monospace" id="pointsChartBadge">Points Live History</span>
                    </div>
                    <div class="card-body">
                        <div id="pointsChartContainer" style="position: relative; height: 260px;">
                            <canvas id="pointsLineChart"></canvas>
                        </div>
                        <div id="pointsChartEmptyNotice" class="text-center py-4 text-secondary small d-none">
                            Awaiting registered banks to plot points evolution...
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

        const palette = [
            '#58a6ff', '#3fb950', '#d29922', '#f85149', '#a371f7',
            '#39c5bb', '#f0883e', '#79c0ff', '#56d364', '#e3b341',
            '#db61a2', '#7ee787', '#1f6feb', '#238636', '#8957e5'
        ];

        let lastGraphData = null;
        let graphPositions = {};
        let activeDraggedNode = null;
        let dragOffset = { x: 0, y: 0 };

        function resetGraphLayout() {
            graphPositions = {};
            if (lastGraphData) {
                renderTransferGraph(lastGraphData.graph, lastGraphData.banks);
            }
        }

        function calculateDefaultNodePositions(nodes, width, height) {
            const count = nodes.length;
            if (count === 0) return {};
            const positions = {};
            const cx = width / 2;
            const cy = height / 2;
            const radiusX = Math.min(width * 0.36, 180);
            const radiusY = Math.min(height * 0.34, 100);

            if (count === 1) {
                positions[nodes[0].bic] = { x: cx, y: cy };
                return positions;
            }

            nodes.forEach((node, i) => {
                const angle = (i / count) * 2 * Math.PI - Math.PI / 2;
                positions[node.bic] = {
                    x: cx + radiusX * Math.cos(angle),
                    y: cy + radiusY * Math.sin(angle)
                };
            });
            return positions;
        }

        function renderTransferGraph(graph, banks) {
            const svg = document.getElementById('networkGraphSvg');
            const edgesLayer = document.getElementById('graphEdgesLayer');
            const nodesLayer = document.getElementById('graphNodesLayer');
            const emptyNotice = document.getElementById('graphEmptyNotice');
            const nodesBadge = document.getElementById('graphNodesBadge');
            const volumeSummary = document.getElementById('graphVolumeSummary');

            if (!svg || !edgesLayer || !nodesLayer) return;

            const currentNodes = (graph && graph.nodes && graph.nodes.length > 0) ? graph.nodes : (banks || []);
            const currentEdges = (graph && graph.edges) ? graph.edges : [];

            if (nodesBadge) nodesBadge.innerText = currentNodes.length + ' node' + (currentNodes.length === 1 ? '' : 's');

            const totalEdgeCents = currentEdges.reduce((sum, e) => sum + (e.totalAmountCents || 0), 0);
            if (volumeSummary) volumeSummary.innerText = 'Transferred: ' + formatCents(totalEdgeCents);

            if (currentNodes.length === 0) {
                edgesLayer.innerHTML = '';
                nodesLayer.innerHTML = '';
                if (emptyNotice) emptyNotice.classList.remove('d-none');
                return;
            }

            if (emptyNotice) emptyNotice.classList.add('d-none');

            const width = 600;
            const height = 340;

            const defaultPos = calculateDefaultNodePositions(currentNodes, width, height);
            currentNodes.forEach(node => {
                if (!graphPositions[node.bic]) {
                    graphPositions[node.bic] = defaultPos[node.bic] || { x: width / 2, y: height / 2 };
                }
            });

            // Clean up removed nodes
            Object.keys(graphPositions).forEach(bic => {
                if (!currentNodes.some(n => n.bic === bic)) {
                    delete graphPositions[bic];
                }
            });

            // 1. Render Edges
            let edgesHtml = '';
            currentEdges.forEach((edge, idx) => {
                const srcPos = graphPositions[edge.sourceBic] || defaultPos[edge.sourceBic] || { x: 150, y: 170 };
                const dstPos = graphPositions[edge.targetBic] || defaultPos[edge.targetBic] || { x: 450, y: 170 };

                const isSelf = edge.sourceBic === edge.targetBic;
                let pathD = '';
                let labelX = 0;
                let labelY = 0;

                if (isSelf) {
                    const sx = srcPos.x;
                    const sy = srcPos.y - 24;
                    pathD = `M ${'$'}{sx - 12} ${'$'}{sy} C ${'$'}{sx - 38} ${'$'}{sy - 46}, ${'$'}{sx + 38} ${'$'}{sy - 46}, ${'$'}{sx + 12} ${'$'}{sy}`;
                    labelX = sx;
                    labelY = sy - 36;
                } else {
                    const dx = dstPos.x - srcPos.x;
                    const dy = dstPos.y - srcPos.y;
                    const dist = Math.sqrt(dx * dx + dy * dy) || 1;
                    const nx = -dy / dist;
                    const ny = dx / dist;

                    const offset = 22;
                    const cx = (srcPos.x + dstPos.x) / 2 + nx * offset;
                    const cy = (srcPos.y + dstPos.y) / 2 + ny * offset;

                    pathD = `M ${'$'}{srcPos.x} ${'$'}{srcPos.y} Q ${'$'}{cx} ${'$'}{cy} ${'$'}{dstPos.x} ${'$'}{dstPos.y}`;
                    labelX = (srcPos.x + 2 * cx + dstPos.x) / 4;
                    labelY = (srcPos.y + 2 * cy + dstPos.y) / 4;
                }

                const strokeColor = palette[idx % palette.length];
                const strokeWidth = Math.min(5, Math.max(1.5, Math.log10(Math.max(100, (edge.totalAmountCents || 0) / 100))));
                const amountFormatted = formatCents(edge.totalAmountCents || 0);

                edgesHtml += `
                    <g class="graph-edge-group" data-edge-src="${'$'}{escapeHtml(edge.sourceBic)}" data-edge-dst="${'$'}{escapeHtml(edge.targetBic)}" data-edge-amount="${'$'}{escapeHtml(amountFormatted)}" data-edge-count="${'$'}{edge.transactionCount}" data-edge-success="${'$'}{edge.successfulCount}">
                        <path d="${'$'}{pathD}" class="graph-edge" stroke="${'$'}{strokeColor}" stroke-width="${'$'}{strokeWidth}" marker-end="url(#arrow-head)" opacity="0.85" />
                        <g transform="translate(${'$'}{labelX}, ${'$'}{labelY})">
                            <rect x="-38" y="-12" width="76" height="22" class="edge-label-bg" />
                            <text y="-1" class="edge-label-text">${'$'}{escapeHtml(amountFormatted)}</text>
                            <text y="7" class="edge-label-subtext">${'$'}{edge.transactionCount} tx</text>
                        </g>
                    </g>
                `;
            });
            edgesLayer.innerHTML = edgesHtml;

            // 2. Render Nodes
            let nodesHtml = '';
            currentNodes.forEach((node, idx) => {
                const pos = graphPositions[node.bic] || { x: width / 2, y: height / 2 };
                const color = palette[idx % palette.length];
                const statusColor = node.status === 'HEALTHY' ? '#238636' : (node.status === 'DEGRADED' ? '#d29922' : '#da3633');
                const moneyFormatted = formatCents(node.totalMoneyCents || 0);
                const latencyFormatted = node.pingLatencyMs ? (node.pingLatencyMs + 'ms') : '';
                const msgFormatted = node.statusMessage || '';

                nodesHtml += `
                    <g class="graph-node" data-node-bic="${'$'}{escapeHtml(node.bic)}" data-node-name="${'$'}{escapeHtml(node.name)}" data-node-status="${'$'}{escapeHtml(node.status)}" data-node-money="${'$'}{escapeHtml(moneyFormatted)}" data-node-score="${'$'}{node.score || 0}" data-node-latency="${'$'}{escapeHtml(latencyFormatted)}" data-node-msg="${'$'}{escapeHtml(msgFormatted)}" transform="translate(${'$'}{pos.x}, ${'$'}{pos.y})">
                        <circle r="24" fill="#161b22" stroke="${'$'}{statusColor}" stroke-width="2.5" filter="url(#node-glow)" />
                        <circle r="20" fill="#1f242c" stroke="${'$'}{color}" stroke-width="1.5" />
                        <text y="-30" fill="#c9d1d9" font-family="-apple-system, BlinkMacSystemFont, Segoe UI, Roboto, sans-serif" font-size="10" font-weight="600" text-anchor="middle">${'$'}{escapeHtml(node.name)}</text>
                        <text y="4" fill="#ffffff" font-family="monospace" font-size="10" font-weight="700" text-anchor="middle">${'$'}{escapeHtml(node.bic)}</text>
                        <g transform="translate(0, 34)">
                            <rect x="-34" y="-8" width="68" height="16" fill="#161b22" stroke="#30363d" rx="3" />
                            <text y="3" fill="#58a6ff" font-family="monospace" font-size="8.5" font-weight="600" text-anchor="middle">${'$'}{escapeHtml(moneyFormatted)}</text>
                        </g>
                    </g>
                `;
            });
            nodesLayer.innerHTML = nodesHtml;

            setupGraphInteractions();
        }

        function setupGraphInteractions() {
            const svg = document.getElementById('networkGraphSvg');
            const tooltip = document.getElementById('graphTooltip');
            if (!svg) return;

            const nodes = svg.querySelectorAll('.graph-node');
            const edges = svg.querySelectorAll('.graph-edge-group');

            nodes.forEach(nodeEl => {
                const bic = nodeEl.getAttribute('data-node-bic');
                const name = nodeEl.getAttribute('data-node-name');
                const status = nodeEl.getAttribute('data-node-status');
                const money = nodeEl.getAttribute('data-node-money');
                const score = nodeEl.getAttribute('data-node-score');
                const latency = nodeEl.getAttribute('data-node-latency');
                const msg = nodeEl.getAttribute('data-node-msg');

                nodeEl.onmousedown = (e) => startDrag(e, bic);
                nodeEl.ontouchstart = (e) => startDrag(e, bic);

                nodeEl.onmouseenter = (e) => {
                    if (tooltip) {
                        const latencyHtml = latency ? (' (' + escapeHtml(latency) + ')') : '';
                        const msgHtml = msg ? ('<br><small class="text-secondary">' + escapeHtml(msg) + '</small>') : '';
                        const badgeClass = status === 'HEALTHY' ? 'bg-success' : (status === 'DEGRADED' ? 'bg-warning text-dark' : 'bg-danger');
                        tooltip.innerHTML = '<strong>' + escapeHtml(name) + '</strong> (<code>' + escapeHtml(bic) + '</code>)<br>Network health: <span class="badge ' + badgeClass + '">' + escapeHtml(status) + latencyHtml + '</span>' + msgHtml + '<br>Balance: <span class="text-info font-monospace">' + escapeHtml(money) + '</span><br>Score: <span class="text-primary font-monospace">' + escapeHtml(score) + ' pts</span>';
                        tooltip.classList.remove('d-none');
                        updateTooltipPos(e);
                    }
                };

                nodeEl.onmousemove = (e) => updateTooltipPos(e);

                nodeEl.onmouseleave = () => {
                    if (tooltip) tooltip.classList.add('d-none');
                };
            });

            edges.forEach(edgeEl => {
                const src = edgeEl.getAttribute('data-edge-src');
                const dst = edgeEl.getAttribute('data-edge-dst');
                const amount = edgeEl.getAttribute('data-edge-amount');
                const count = edgeEl.getAttribute('data-edge-count');
                const success = edgeEl.getAttribute('data-edge-success');

                edgeEl.onmouseenter = (e) => {
                    if (tooltip) {
                        tooltip.innerHTML = `<strong>Transfer Route:</strong><br><code>${'$'}{escapeHtml(src)}</code> ➔ <code>${'$'}{escapeHtml(dst)}</code><br>Total Settled: <span class="text-success fw-bold font-monospace">${'$'}{escapeHtml(amount)}</span><br>Transactions: <span class="font-monospace">${'$'}{escapeHtml(success)} / ${'$'}{escapeHtml(count)} successful</span>`;
                        tooltip.classList.remove('d-none');
                        updateTooltipPos(e);
                    }
                };

                edgeEl.onmousemove = (e) => updateTooltipPos(e);

                edgeEl.onmouseleave = () => {
                    if (tooltip) tooltip.classList.add('d-none');
                };
            });
        }

        function updateTooltipPos(e) {
            const tooltip = document.getElementById('graphTooltip');
            const container = document.getElementById('graphContainer');
            if (!tooltip || !container) return;
            const rect = container.getBoundingClientRect();
            const clientX = e.clientX || (e.touches && e.touches[0] ? e.touches[0].clientX : 0);
            const clientY = e.clientY || (e.touches && e.touches[0] ? e.touches[0].clientY : 0);
            const x = clientX - rect.left + 15;
            const y = clientY - rect.top + 15;
            tooltip.style.left = Math.min(x, rect.width - 200) + 'px';
            tooltip.style.top = Math.min(y, rect.height - 85) + 'px';
        }

        function startDrag(e, bic) {
            e.preventDefault();
            activeDraggedNode = bic;
            const svg = document.getElementById('networkGraphSvg');
            const pt = getSvgCoordinates(e, svg);
            const currentPos = graphPositions[bic] || { x: 300, y: 170 };
            dragOffset = { x: pt.x - currentPos.x, y: pt.y - currentPos.y };

            const onMove = (moveEvt) => {
                if (!activeDraggedNode) return;
                const movePt = getSvgCoordinates(moveEvt, svg);
                const newX = Math.max(30, Math.min(570, movePt.x - dragOffset.x));
                const newY = Math.max(30, Math.min(310, movePt.y - dragOffset.y));
                graphPositions[activeDraggedNode] = { x: newX, y: newY };
                if (lastGraphData) {
                    renderTransferGraph(lastGraphData.graph, lastGraphData.banks);
                }
            };

            const onEnd = () => {
                activeDraggedNode = null;
                window.removeEventListener('mousemove', onMove);
                window.removeEventListener('mouseup', onEnd);
                window.removeEventListener('touchmove', onMove);
                window.removeEventListener('touchend', onEnd);
            };

            window.addEventListener('mousemove', onMove);
            window.addEventListener('mouseup', onEnd);
            window.addEventListener('touchmove', onMove);
            window.addEventListener('touchend', onEnd);
        }

        function getSvgCoordinates(e, svg) {
            const clientX = e.clientX || (e.touches && e.touches[0] ? e.touches[0].clientX : 0);
            const clientY = e.clientY || (e.touches && e.touches[0] ? e.touches[0].clientY : 0);
            const pt = svg.createSVGPoint();
            pt.x = clientX;
            pt.y = clientY;
            const ctm = svg.getScreenCTM();
            if (ctm) {
                return pt.matrixTransform(ctm.inverse());
            }
            return { x: clientX, y: clientY };
        }

        let depositMoneyChart = null;
        let withdrawalMoneyChart = null;
        let pointsChart = null;
        const lineTimeLabels = [];
        const bankMoneyHistory = {};
        const bankPointsHistory = {};
        const MAX_HISTORY_POINTS = 60;

        function updateCharts(banks) {
            const registeredBanks = banks || [];
            const totalCents = registeredBanks.reduce((acc, b) => acc + (b.totalMoneyCents || 0), 0);

            const depBadge = document.getElementById('depositsMoneyTotalBadge');
            if (depBadge) depBadge.innerText = formatCents(totalCents);
            const withBadge = document.getElementById('withdrawalsMoneyTotalBadge');
            if (withBadge) withBadge.innerText = formatCents(totalCents);

            if (registeredBanks.length === 0) {
                const depNotice = document.getElementById('depositMoneyEmptyNotice');
                const depCanvas = document.getElementById('depositMoneyLineChart');
                if (depNotice) depNotice.classList.remove('d-none');
                if (depCanvas) depCanvas.style.display = 'none';
                if (depositMoneyChart) { depositMoneyChart.destroy(); depositMoneyChart = null; }

                const withNotice = document.getElementById('withdrawalMoneyEmptyNotice');
                const withCanvas = document.getElementById('withdrawalMoneyLineChart');
                if (withNotice) withNotice.classList.remove('d-none');
                if (withCanvas) withCanvas.style.display = 'none';
                if (withdrawalMoneyChart) { withdrawalMoneyChart.destroy(); withdrawalMoneyChart = null; }

                const ptsNotice = document.getElementById('pointsChartEmptyNotice');
                const ptsCanvas = document.getElementById('pointsLineChart');
                if (ptsNotice) ptsNotice.classList.remove('d-none');
                if (ptsCanvas) ptsCanvas.style.display = 'none';
                if (pointsChart) { pointsChart.destroy(); pointsChart = null; }
                return;
            }

            const depNotice = document.getElementById('depositMoneyEmptyNotice');
            const depCanvas = document.getElementById('depositMoneyLineChart');
            if (depNotice) depNotice.classList.add('d-none');
            if (depCanvas) depCanvas.style.display = 'block';

            const withNotice = document.getElementById('withdrawalMoneyEmptyNotice');
            const withCanvas = document.getElementById('withdrawalMoneyLineChart');
            if (withNotice) withNotice.classList.add('d-none');
            if (withCanvas) withCanvas.style.display = 'block';

            const ptsNotice = document.getElementById('pointsChartEmptyNotice');
            const ptsCanvas = document.getElementById('pointsLineChart');
            if (ptsNotice) ptsNotice.classList.add('d-none');
            if (ptsCanvas) ptsCanvas.style.display = 'block';

            const nowLabel = new Date().toLocaleTimeString();
            lineTimeLabels.push(nowLabel);
            if (lineTimeLabels.length > MAX_HISTORY_POINTS) {
                lineTimeLabels.shift();
            }

            registeredBanks.forEach((b, idx) => {
                const moneyVal = (b.totalMoneyCents || 0) / 100;
                const scoreVal = b.score || 0;
                const color = palette[idx % palette.length];

                if (!bankMoneyHistory[b.bic]) {
                    bankMoneyHistory[b.bic] = {
                        name: b.name,
                        bic: b.bic,
                        color: color,
                        data: Array(Math.max(0, lineTimeLabels.length - 1)).fill(0)
                    };
                }
                bankMoneyHistory[b.bic].name = b.name;
                bankMoneyHistory[b.bic].color = color;
                bankMoneyHistory[b.bic].data.push(moneyVal);
                if (bankMoneyHistory[b.bic].data.length > MAX_HISTORY_POINTS) {
                    bankMoneyHistory[b.bic].data.shift();
                }

                if (!bankPointsHistory[b.bic]) {
                    bankPointsHistory[b.bic] = {
                        name: b.name,
                        bic: b.bic,
                        color: color,
                        data: Array(Math.max(0, lineTimeLabels.length - 1)).fill(0)
                    };
                }
                bankPointsHistory[b.bic].name = b.name;
                bankPointsHistory[b.bic].color = color;
                bankPointsHistory[b.bic].data.push(scoreVal);
                if (bankPointsHistory[b.bic].data.length > MAX_HISTORY_POINTS) {
                    bankPointsHistory[b.bic].data.shift();
                }
            });

            Object.keys(bankMoneyHistory).forEach(bic => {
                if (!registeredBanks.some(b => b.bic === bic)) {
                    const lastVal = bankMoneyHistory[bic].data.length > 0 ? bankMoneyHistory[bic].data[bankMoneyHistory[bic].data.length - 1] : 0;
                    bankMoneyHistory[bic].data.push(lastVal);
                    if (bankMoneyHistory[bic].data.length > MAX_HISTORY_POINTS) {
                        bankMoneyHistory[bic].data.shift();
                    }
                }
            });

            Object.keys(bankPointsHistory).forEach(bic => {
                if (!registeredBanks.some(b => b.bic === bic)) {
                    const lastVal = bankPointsHistory[bic].data.length > 0 ? bankPointsHistory[bic].data[bankPointsHistory[bic].data.length - 1] : 0;
                    bankPointsHistory[bic].data.push(lastVal);
                    if (bankPointsHistory[bic].data.length > MAX_HISTORY_POINTS) {
                        bankPointsHistory[bic].data.shift();
                    }
                }
            });

            const moneyDatasets = Object.keys(bankMoneyHistory).map(bic => {
                const item = bankMoneyHistory[bic];
                return {
                    label: item.name + ' (' + item.bic + ')',
                    data: [...item.data],
                    borderColor: item.color,
                    backgroundColor: item.color,
                    borderWidth: 2,
                    tension: 0.25,
                    pointRadius: 2,
                    pointHoverRadius: 5,
                    fill: false
                };
            });

            const pointsDatasets = Object.keys(bankPointsHistory).map(bic => {
                const item = bankPointsHistory[bic];
                return {
                    label: item.name + ' (' + item.bic + ')',
                    data: [...item.data],
                    borderColor: item.color,
                    backgroundColor: item.color,
                    borderWidth: 2.5,
                    tension: 0.25,
                    pointRadius: 3,
                    pointHoverRadius: 6,
                    fill: false
                };
            });

            function createOrUpdateMoneyChart(canvasId, existingChart) {
                const canvas = document.getElementById(canvasId);
                if (!canvas) return null;
                if (!existingChart) {
                    const ctx = canvas.getContext('2d');
                    return new Chart(ctx, {
                        type: 'line',
                        data: {
                            labels: [...lineTimeLabels],
                            datasets: moneyDatasets
                        },
                        options: {
                            responsive: true,
                            maintainAspectRatio: false,
                            animation: false,
                            interaction: { mode: 'index', intersect: false },
                            scales: {
                                x: {
                                    grid: { color: '#21262d' },
                                    ticks: { color: '#8b949e', maxTicksLimit: 6, font: { size: 9 } }
                                },
                                y: {
                                    beginAtZero: true,
                                    grid: { color: '#21262d' },
                                    ticks: {
                                        color: '#8b949e',
                                        font: { size: 9 },
                                        callback: function(v) { return '€ ' + v.toLocaleString('en-US'); }
                                    }
                                }
                            },
                            plugins: {
                                legend: {
                                    position: 'top',
                                    labels: { color: '#c9d1d9', boxWidth: 10, padding: 6, font: { size: 10 } }
                                },
                                tooltip: {
                                    callbacks: {
                                        label: function(ctx) {
                                            const v = ctx.raw || 0;
                                            return ' ' + ctx.dataset.label + ': € ' + v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
                                        }
                                    }
                                }
                            }
                        }
                    });
                } else {
                    existingChart.data.labels = [...lineTimeLabels];
                    existingChart.data.datasets = moneyDatasets;
                    existingChart.update('none');
                    return existingChart;
                }
            }

            depositMoneyChart = createOrUpdateMoneyChart('depositMoneyLineChart', depositMoneyChart);
            withdrawalMoneyChart = createOrUpdateMoneyChart('withdrawalMoneyLineChart', withdrawalMoneyChart);

            const pointsCanvas = document.getElementById('pointsLineChart');
            if (pointsCanvas) {
                if (!pointsChart) {
                    const ctx = pointsCanvas.getContext('2d');
                    pointsChart = new Chart(ctx, {
                        type: 'line',
                        data: {
                            labels: [...lineTimeLabels],
                            datasets: pointsDatasets
                        },
                        options: {
                            responsive: true,
                            maintainAspectRatio: false,
                            animation: false,
                            interaction: { mode: 'index', intersect: false },
                            scales: {
                                x: {
                                    grid: { color: '#21262d' },
                                    ticks: { color: '#8b949e', maxTicksLimit: 10, font: { size: 10 } }
                                },
                                y: {
                                    beginAtZero: true,
                                    grid: { color: '#21262d' },
                                    ticks: {
                                        color: '#8b949e',
                                        font: { size: 10 },
                                        callback: function(v) { return v + ' pts'; }
                                    }
                                }
                            },
                            plugins: {
                                legend: {
                                    position: 'top',
                                    labels: { color: '#c9d1d9', boxWidth: 12, padding: 8, font: { size: 11 } }
                                },
                                tooltip: {
                                    callbacks: {
                                        label: function(ctx) {
                                            const v = ctx.raw || 0;
                                            return ' ' + ctx.dataset.label + ': ' + v + ' pts';
                                        }
                                    }
                                }
                            }
                        }
                    });
                } else {
                    pointsChart.data.labels = [...lineTimeLabels];
                    pointsChart.data.datasets = pointsDatasets;
                    pointsChart.update('none');
                }
            }
        }

        let lastBanks = [];

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

            lastBanks = data.banks || [];
            lastGraphData = data;
            updateCharts(data.banks);
            renderTransferGraph(data.graph, data.banks);

            const tbody = document.getElementById('leaderboardBody');
            if (!data.banks || data.banks.length === 0) {
                tbody.innerHTML = '<tr><td colspan="9" class="text-center py-4 text-secondary">No banks registered yet. Run <code>./gradlew :bank-starter:run</code> to connect.</td></tr>';
                return;
            }

            let html = '';
            data.banks.forEach((b, index) => {
                const total = b.totalTransactions || 0;
                const success = b.successfulTransactions || 0;
                const rate = total > 0 ? ((success / total) * 100).toFixed(0) + '%' : '100%';
                
                let healthBadge = '';
                const latencyText = b.pingLatencyMs ? (' (' + b.pingLatencyMs + 'ms)') : '';
                const tooltipText = b.statusMessage ? escapeHtml(b.statusMessage) : (b.status || 'UNKNOWN');
                if (b.status === 'HEALTHY' || b.status === 'ONLINE' || b.status === 'UP') {
                    healthBadge = '<span class="badge badge-healthy text-white" title="' + tooltipText + '"><span class="me-1">●</span>Healthy' + latencyText + '</span>';
                } else if (b.status === 'DEGRADED') {
                    healthBadge = '<span class="badge badge-degraded" title="' + tooltipText + '"><span class="me-1">●</span>Degraded</span>';
                } else {
                    const detail = b.statusMessage && (b.statusMessage.startsWith('HTTP ') || b.statusMessage.startsWith('Unreachable')) ? (' (' + escapeHtml(b.statusMessage.replace('Unreachable: ', '')) + ')') : '';
                    healthBadge = '<span class="badge badge-down text-white" title="' + tooltipText + '"><span class="me-1">●</span>Disconnected' + detail + '</span>';
                }

                html += `
                    <tr>
                        <td class="ps-3 font-monospace text-secondary">${'$'}{index + 1}</td>
                        <td><span class="badge bg-dark border border-secondary font-monospace">${'$'}{escapeHtml(b.bic)}</span></td>
                        <td>
                            <div class="fw-bold text-white">${'$'}{escapeHtml(b.name)}</div>
                            <div class="small text-secondary font-monospace" style="font-size: 0.72rem;" title="${'$'}{escapeHtml(b.webhookUrl)}">
                                🔗 ${'$'}{escapeHtml(b.webhookUrl || 'No URL')}
                            </div>
                        </td>
                        <td class="text-info fw-bold font-monospace">${'$'}{formatCents(b.totalMoneyCents || 0)}</td>
                        <td class="text-primary fw-bold font-monospace">${'$'}{b.score} pts</td>
                        <td>${'$'}{b.totalTransactions}</td>
                        <td><span class="text-success">${'$'}{rate}</span></td>
                        <td>${'$'}{healthBadge}</td>
                        <td class="text-end pe-3">
                            <div class="d-inline-flex gap-1">
                                <button class="btn btn-sm btn-outline-info" title="Test connection to bank" onclick="pingSingleBank('${'$'}{escapeHtml(b.bic)}')">⚡ Ping</button>
                                <button class="btn btn-sm btn-outline-info" title="Edit bank tunnel URL" onclick="openEditTunnelModal('${'$'}{escapeHtml(b.bic)}')">✏️ Edit URL</button>
                                <button class="btn btn-sm btn-outline-danger" title="Remove bank" onclick="removeBank('${'$'}{escapeHtml(b.bic)}', this)">🗑 Remove</button>
                            </div>
                        </td>
                    </tr>
                `;
            });
            tbody.innerHTML = html;
        }

        async function removeBank(bic, btn) {
            const bank = (lastBanks || []).find(b => b.bic === bic);
            const label = bank ? bank.name + ' (' + bic + ')' : bic;
            if (!confirm('Remove ' + label + ' from the SWIFT network?')) return;
            btn.disabled = true;
            try {
                const res = await fetch('/swift/banks/' + encodeURIComponent(bic), { method: 'DELETE' });
                if (!res.ok && res.status !== 404) {
                    btn.disabled = false;
                    console.warn('Failed to remove bank', bic, res.status);
                    return;
                }
                delete bankMoneyHistory[bic];
                delete bankPointsHistory[bic];
                delete graphPositions[bic];
            } catch (err) {
                btn.disabled = false;
                console.warn('Failed to remove bank', bic, err);
                return;
            }
            pollState();
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

        let simRunning = false;

        function renderSimStatus(sim) {
            simRunning = sim.status === 'RUNNING';
            const btn = document.getElementById('btnToggleSim');
            const badge = document.getElementById('simStatus');
            if (simRunning) {
                btn.innerText = '⏹ Stop Traffic Sim';
                btn.className = 'btn btn-danger btn-control';
                badge.innerText = 'Simulator: Running (' + (sim.intervalMs / 1000) + 's interval)';
                badge.className = 'badge bg-success ms-auto';
            } else {
                btn.innerText = '▶ Start Traffic Sim';
                btn.className = 'btn btn-success btn-control';
                badge.innerText = 'Simulator: Stopped';
                badge.className = 'badge bg-secondary ms-auto';
            }
        }

        function formatElapsed(seconds) {
            const sec = Math.max(0, parseInt(seconds || 0, 10));
            const mins = Math.floor(sec / 60);
            const remainingSecs = sec % 60;
            return String(mins).padStart(2, '0') + ':' + String(remainingSecs).padStart(2, '0');
        }

        async function togglePhase(phaseName) {
            try {
                const res = await fetch('/swift/simulator/phase/' + phaseName.toLowerCase() + '/toggle', { method: 'POST' });
                if (res.ok) {
                    const data = await res.json();
                    if (data.phases) renderPhases(data.phases);
                }
            } catch (err) {
                console.error('Failed to toggle phase ' + phaseName, err);
            }
        }

        const PHASE_SCENARIOS = {
            deposits: [
                { id: 'DEPOSIT_50_EUR', name: 'Deposit 50 EUR (Assert 50€ & statement)' },
                { id: 'DEPOSIT_NEGATIVE_50_EUR', name: 'Deposit -50 EUR (Assert error & no 0€)' }
            ],
            withdrawals: [
                { id: 'WITHDRAW_HAPPY_20_EUR', name: 'Deposit 50€ + Withdraw 20€ (Happy)' },
                { id: 'WITHDRAW_NEGATIVE_50_EUR', name: 'Withdraw -50 EUR (Assert rejection)' },
                { id: 'WITHDRAW_OVERDRAFT_50_EUR', name: 'Withdraw > balance (Assert rejection)' }
            ],
            transfers: [
                { id: 'TRANSFER_INTRA_BANK', name: 'Intra-Bank transfer within bank' },
                { id: 'TRANSFER_CROSS_BANK', name: 'Cross-Bank transfer via SWIFT Hub' }
            ]
        };

        function isScenarioGreenForAll(scenarioId, results) {
            if (!results || results.length === 0) return false;
            const matching = results.filter(r => r.scenario === scenarioId);
            return matching.length > 0 && matching.every(r => r.passed);
        }

        function getScenarioDisplayName(scenarioId, results, phaseKey) {
            if (isScenarioGreenForAll(scenarioId, results)) {
                return scenarioId;
            }
            let index = -1;
            if (phaseKey && PHASE_SCENARIOS[phaseKey]) {
                index = PHASE_SCENARIOS[phaseKey].findIndex(s => s.id === scenarioId);
            }
            if (index === -1 && results) {
                const unique = [];
                results.forEach(r => {
                    if (!unique.includes(r.scenario)) unique.push(r.scenario);
                });
                index = unique.indexOf(scenarioId);
            }
            const num = index >= 0 ? (index + 1) : 1;
            return 'Scenario ' + num;
        }

        function updatePhaseScenarioDescriptions(phaseKey, results, descElementId, subtitleElementId, fullSubtitle) {
            const descEl = document.getElementById(descElementId);
            const scenarios = PHASE_SCENARIOS[phaseKey] || [];
            if (descEl) {
                const lines = scenarios.map((s, idx) => {
                    if (isScenarioGreenForAll(s.id, results)) {
                        return '• ' + escapeHtml(s.name);
                    } else {
                        return '• Scenario ' + (idx + 1) + ' (Hidden)';
                    }
                });
                descEl.innerHTML = lines.join('<br>');
            }
            const subEl = document.getElementById(subtitleElementId);
            if (subEl && fullSubtitle) {
                const allGreen = scenarios.length > 0 && scenarios.every(s => isScenarioGreenForAll(s.id, results));
                subEl.innerText = allGreen ? fullSubtitle : 'Automated evaluation';
            }
        }

        function renderPhases(phases) {
            if (!phases) return;

            // Deposits
            const dep = phases.deposits || {};
            const depBtn = document.getElementById('btnPhaseDeposits');
            const depTimer = document.getElementById('phaseTimerDeposits');
            const depResults = document.getElementById('phaseResultsDeposits');
            if (depBtn) {
                depBtn.innerText = dep.running ? '⏸ Pause' : '▶ Play';
                depBtn.className = dep.running ? 'btn btn-sm btn-danger fw-bold px-3' : 'btn btn-sm btn-success fw-bold px-3';
            }
            if (depTimer) {
                depTimer.innerText = formatElapsed(dep.elapsedSeconds);
            }
            if (depResults && dep.results && dep.results.length > 0) {
                const passedCount = dep.results.filter(r => r.passed).length;
                const totalCount = dep.results.length;
                depResults.innerHTML = `
                    <div class="d-flex justify-content-between align-items-center mb-1">
                        <span class="fw-semibold text-light">Last Run:</span>
                        <span class="badge ${'$'}{passedCount === totalCount ? 'bg-success' : 'bg-warning text-dark'}">${'$'}{passedCount}/${'$'}{totalCount} Passed (+${'$'}{passedCount} pts)</span>
                    </div>
                    <div class="font-monospace text-secondary" style="font-size: 0.72rem; max-height: 120px; overflow-y: auto;">
                        ${'$'}{dep.results.map(r => `<div><span class="${'$'}{r.passed ? 'text-success' : 'text-danger'}">${'$'}{r.passed ? '✓' : '✗'}</span> ${'$'}{escapeHtml(r.bic)} - ${'$'}{escapeHtml(getScenarioDisplayName(r.scenario, dep.results, 'deposits'))}</div>`).join('')}
                    </div>
                `;
            }
            updatePhaseScenarioDescriptions('deposits', dep.results, 'phaseDescDeposits', 'phaseSubtitleDeposits', '+50€ & -50€ Rejection');

            // Withdrawals
            const withDr = phases.withdrawals || {};
            const withBtn = document.getElementById('btnPhaseWithdrawals');
            const withTimer = document.getElementById('phaseTimerWithdrawals');
            const withResults = document.getElementById('phaseResultsWithdrawals');
            if (withBtn) {
                withBtn.innerText = withDr.running ? '⏸ Pause' : '▶ Play';
                withBtn.className = withDr.running ? 'btn btn-sm btn-danger fw-bold px-3' : 'btn btn-sm btn-success fw-bold px-3';
            }
            if (withTimer) {
                withTimer.innerText = formatElapsed(withDr.elapsedSeconds);
            }
            if (withResults && withDr.results && withDr.results.length > 0) {
                const passedCount = withDr.results.filter(r => r.passed).length;
                const totalCount = withDr.results.length;
                withResults.innerHTML = `
                    <div class="d-flex justify-content-between align-items-center mb-1">
                        <span class="fw-semibold text-light">Last Run:</span>
                        <span class="badge ${'$'}{passedCount === totalCount ? 'bg-success' : 'bg-warning text-dark'}">${'$'}{passedCount}/${'$'}{totalCount} Passed (+${'$'}{passedCount} pts)</span>
                    </div>
                    <div class="font-monospace text-secondary" style="font-size: 0.72rem; max-height: 120px; overflow-y: auto;">
                        ${'$'}{withDr.results.map(r => `<div><span class="${'$'}{r.passed ? 'text-success' : 'text-danger'}">${'$'}{r.passed ? '✓' : '✗'}</span> ${'$'}{escapeHtml(r.bic)} - ${'$'}{escapeHtml(getScenarioDisplayName(r.scenario, withDr.results, 'withdrawals'))}</div>`).join('')}
                    </div>
                `;
            }
            updatePhaseScenarioDescriptions('withdrawals', withDr.results, 'phaseDescWithdrawals', 'phaseSubtitleWithdrawals', 'Happy, Negative, Overdraft');

            // Transfers
            const trans = phases.transfers || {};
            const transBtn = document.getElementById('btnPhaseTransfers');
            const transTimer = document.getElementById('phaseTimerTransfers');
            const transResults = document.getElementById('phaseResultsTransfers');
            if (transBtn) {
                transBtn.innerText = trans.running ? '⏸ Pause' : '▶ Play';
                transBtn.className = trans.running ? 'btn btn-sm btn-danger fw-bold px-3' : 'btn btn-sm btn-success fw-bold px-3';
            }
            if (transTimer) {
                transTimer.innerText = formatElapsed(trans.elapsedSeconds);
            }
            if (transResults && trans.results && trans.results.length > 0) {
                const passedCount = trans.results.filter(r => r.passed).length;
                const totalCount = trans.results.length;
                transResults.innerHTML = `
                    <div class="d-flex justify-content-between align-items-center mb-1">
                        <span class="fw-semibold text-light">Last Run:</span>
                        <span class="badge ${'$'}{passedCount === totalCount ? 'bg-success' : 'bg-warning text-dark'}">${'$'}{passedCount}/${'$'}{totalCount} Passed (+${'$'}{passedCount} pts)</span>
                    </div>
                    <div class="font-monospace text-secondary" style="font-size: 0.72rem; max-height: 120px; overflow-y: auto;">
                        ${'$'}{trans.results.map(r => `<div><span class="${'$'}{r.passed ? 'text-success' : 'text-danger'}">${'$'}{r.passed ? '✓' : '✗'}</span> ${'$'}{escapeHtml(r.bic)} - ${'$'}{escapeHtml(getScenarioDisplayName(r.scenario, trans.results, 'transfers'))}</div>`).join('')}
                    </div>
                `;
            }
            updatePhaseScenarioDescriptions('transfers', trans.results, 'phaseDescTransfers', 'phaseSubtitleTransfers', 'Intra-Bank & Cross-Bank');
        }

        async function pollSimStatus() {
            try {
                const res = await fetch('/swift/simulator/status');
                if (res.ok) {
                    const data = await res.json();
                    renderSimStatus(data);
                    if (data.phases) renderPhases(data.phases);
                }
            } catch (err) {
                console.error('Failed to fetch simulator status', err);
            }
        }

        async function toggleSim() {
            const action = simRunning ? 'stop' : 'start';
            await fetch('/swift/simulator/' + action, { method: 'POST' });
            await pollSimStatus();
        }

        setInterval(pollSimStatus, 1500);
        pollSimStatus();

        async function triggerBurst(count) {
            await fetch('/swift/simulator/burst?count=' + count, { method: 'POST' });
            pollState();
        }

        function dismissScenarioResults() {
            const box = document.getElementById('scenarioResultBox');
            if (box) box.classList.add('d-none');
        }

        function escapeHtml(str) {
            if (!str) return '';
            return String(str)
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;')
                .replace(/'/g, '&#039;');
        }

        async function runDepositScenario(type) {
            const box = document.getElementById('scenarioResultBox');
            const titleEl = document.getElementById('scenarioResultTitle');
            const contentEl = document.getElementById('scenarioResultContent');
            if (!box || !contentEl) return;

            const phaseTitle = type === 'deposit' ? 'Deposits Phase Test' :
                               type === 'withdraw' ? 'Withdrawals Phase Test' :
                               type === 'transfer' ? 'Transfers Phase Test' :
                               'Test Scenario Results';
            titleEl.innerHTML = '🧪 ' + phaseTitle;
            contentEl.innerHTML = '<div class="text-secondary small d-flex align-items-center gap-2"><div class="spinner-border spinner-border-sm text-info" role="status"></div> Executing scenario across all banks...</div>';
            box.classList.remove('d-none');

            try {
                const res = await fetch('/swift/simulator/scenario/' + type, { method: 'POST' });
                const data = await res.json();
                if (data.results && data.results.length > 0) {
                    const allPassed = data.results.every(r => r.passed);
                    let html = '<div class="mb-2 small ' + (allPassed ? 'text-success' : 'text-warning') + ' fw-semibold">' +
                        (allPassed ? '✅ All tests passed successfully!' : '⚠️ Some tests encountered issues:') +
                        '</div><div class="list-group list-group-flush rounded bg-transparent">';
                    const phaseKey = type === 'deposit' ? 'deposits' : (type === 'withdraw' ? 'withdrawals' : (type === 'transfer' ? 'transfers' : null));
                    data.results.forEach(r => {
                        const badgeClass = r.passed ? 'bg-success' : 'bg-danger';
                        const badgeText = r.passed ? 'PASSED' : 'FAILED';
                        const displayScenario = getScenarioDisplayName(r.scenario, data.results, phaseKey);
                        html += `
                            <div class="list-group-item bg-dark bg-opacity-75 text-light border-secondary px-3 py-2 mb-2 rounded">
                                <div class="d-flex justify-content-between align-items-center mb-1">
                                    <div>
                                        <span class="fw-bold">${'$'}{escapeHtml(r.bankName)}</span>
                                        <code class="text-info ms-1">${'$'}{escapeHtml(r.bic)}</code>
                                        <span class="badge bg-secondary ms-2">${'$'}{escapeHtml(displayScenario)}</span>
                                    </div>
                                    <span class="badge ${'$'}{badgeClass}">${'$'}{badgeText}</span>
                                </div>
                                <div class="small text-secondary mb-2">${'$'}{escapeHtml(r.message)}</div>
                                ${'$'}{r.expected || r.actual ? `
                                <div class="bg-black bg-opacity-50 p-2 rounded small font-monospace border border-secondary">
                                    <div class="text-success"><span class="text-secondary fw-semibold">Expected:</span> ${'$'}{escapeHtml(r.expected || 'N/A')}</div>
                                    <div class="${'$'}{r.passed ? 'text-info' : 'text-danger'}"><span class="text-secondary fw-semibold">Actual:&nbsp;&nbsp;</span> ${'$'}{escapeHtml(r.actual || 'N/A')}</div>
                                </div>
                                ` : ''}
                            </div>
                        `;
                    });
                    html += '</div>';
                    contentEl.innerHTML = html;
                } else {
                    contentEl.innerHTML = '<div class="text-warning small">⚠️ No registered banks found to run scenario. Connect a bank node first.</div>';
                }
                pollState();
            } catch (e) {
                console.error('Failed to run deposit scenario:', e);
                contentEl.innerHTML = '<div class="text-danger small">❌ Failed to execute scenario: ' + e + '</div>';
            }
        }

        async function pingSingleBank(bic) {
            try {
                await fetch('/swift/banks/' + encodeURIComponent(bic) + '/ping', { method: 'POST' });
                await pollState();
            } catch (e) {
                console.warn('Ping failed for bank', bic, e);
            }
        }

        async function pingAllBanks() {
            try {
                await fetch('/swift/ping', { method: 'POST' });
                await pollState();
            } catch (e) {
                console.warn('Ping all failed', e);
            }
        }

        let currentEditBic = null;
        let editTunnelModalInstance = null;

        function openEditTunnelModal(bic) {
            const bank = (lastBanks || []).find(b => b.bic === bic);
            if (!bank) return;
            currentEditBic = bic;
            document.getElementById('modalBankName').innerText = bank.name;
            document.getElementById('modalBankBic').innerText = bank.bic;
            const input = document.getElementById('modalTunnelUrlInput');
            input.value = bank.webhookUrl || '';
            const alertBox = document.getElementById('modalEditAlert');
            alertBox.classList.add('d-none');
            alertBox.innerText = '';

            const modalEl = document.getElementById('editTunnelModal');
            if (typeof bootstrap !== 'undefined' && bootstrap.Modal) {
                if (!editTunnelModalInstance) {
                    editTunnelModalInstance = new bootstrap.Modal(modalEl);
                }
                editTunnelModalInstance.show();
            } else {
                const newUrl = prompt('Enter new tunnel URL for ' + bank.name + ' (' + bic + '):', bank.webhookUrl || '');
                if (newUrl !== null && newUrl.trim()) {
                    updateTunnelUrl(bic, newUrl.trim());
                }
            }
        }

        async function testModalConnection() {
            if (!currentEditBic) return;
            const input = document.getElementById('modalTunnelUrlInput');
            const alertBox = document.getElementById('modalEditAlert');
            const testBtn = document.getElementById('btnTestTunnelUrl');
            const newUrl = input.value.trim();
            if (!newUrl) {
                alertBox.className = 'alert alert-danger py-2 small mb-0';
                alertBox.innerText = 'Please enter a URL first.';
                alertBox.classList.remove('d-none');
                return;
            }
            if (testBtn) { testBtn.disabled = true; testBtn.innerText = 'Testing...'; }
            alertBox.className = 'alert alert-info py-2 small mb-0';
            alertBox.innerText = 'Pinging ' + newUrl + '...';
            alertBox.classList.remove('d-none');

            try {
                // First update URL so ping hits the new address
                const res = await fetch('/swift/banks/' + encodeURIComponent(currentEditBic) + '/url', {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ webhookUrl: newUrl })
                });
                const data = await res.json();
                if (data.status === 'HEALTHY') {
                    alertBox.className = 'alert alert-success py-2 small mb-0';
                    alertBox.innerText = '✅ Connected successfully! (Latency: ' + (data.pingLatencyMs ? data.pingLatencyMs + 'ms' : 'OK') + ')';
                } else {
                    alertBox.className = 'alert alert-danger py-2 small mb-0';
                    alertBox.innerText = '❌ Failed to connect: ' + (data.statusMessage || data.status);
                }
                await pollState();
            } catch (err) {
                alertBox.className = 'alert alert-danger py-2 small mb-0';
                alertBox.innerText = '❌ Error testing connection: ' + err.message;
            } finally {
                if (testBtn) { testBtn.disabled = false; testBtn.innerText = '⚡ Test Connection'; }
            }
        }

        async function saveTunnelUrl() {
            if (!currentEditBic) return;
            const input = document.getElementById('modalTunnelUrlInput');
            const newUrl = input.value.trim();
            const alertBox = document.getElementById('modalEditAlert');
            if (!newUrl) {
                alertBox.className = 'alert alert-danger py-2 small mb-0';
                alertBox.innerText = 'Tunnel URL cannot be empty.';
                alertBox.classList.remove('d-none');
                return;
            }
            const saveBtn = document.getElementById('btnSaveTunnelUrl');
            saveBtn.disabled = true;
            saveBtn.innerText = 'Saving...';
            try {
                await updateTunnelUrl(currentEditBic, newUrl);
                if (editTunnelModalInstance) {
                    editTunnelModalInstance.hide();
                }
            } catch (err) {
                alertBox.className = 'alert alert-danger py-2 small mb-0';
                alertBox.innerText = 'Failed to update URL: ' + err.message;
                alertBox.classList.remove('d-none');
            } finally {
                saveBtn.disabled = false;
                saveBtn.innerText = 'Save URL';
            }
        }

        async function updateTunnelUrl(bic, webhookUrl) {
            const res = await fetch('/swift/banks/' + encodeURIComponent(bic) + '/url', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ webhookUrl: webhookUrl })
            });
            if (!res.ok) {
                const errorData = await res.json().catch(() => ({}));
                throw new Error(errorData.error || ('HTTP ' + res.status));
            }
            await pollState();
        }
    </script>

    <!-- Edit Tunnel URL Modal -->
    <div class="modal fade" id="editTunnelModal" tabindex="-1" aria-labelledby="editTunnelModalLabel" aria-hidden="true">
        <div class="modal-dialog modal-dialog-centered">
            <div class="modal-content bg-dark text-light border-secondary">
                <div class="modal-header border-secondary">
                    <h5 class="modal-title" id="editTunnelModalLabel">✏️ Edit Bank Tunnel URL</h5>
                    <button type="button" class="btn-close btn-close-white" data-bs-dismiss="modal" aria-label="Close"></button>
                </div>
                <div class="modal-body">
                    <div class="mb-3">
                        <label class="form-label text-secondary small">Bank Node</label>
                        <div class="d-flex align-items-center gap-2">
                            <span id="modalBankName" class="fw-bold text-white"></span>
                            <code id="modalBankBic" class="text-info"></code>
                        </div>
                    </div>
                    <div class="mb-3">
                        <label for="modalTunnelUrlInput" class="form-label text-light">Tunnel / Webhook URL</label>
                        <input type="url" class="form-control bg-black text-light border-secondary" id="modalTunnelUrlInput" placeholder="https://xxx.loca.lt" required>
                        <div class="form-text text-secondary small">Update this URL if localtunnel/ngrok reconnected with a new address while scenarios are running.</div>
                    </div>
                    <div id="modalEditAlert" class="alert alert-danger d-none py-2 small mb-0"></div>
                </div>
                <div class="modal-footer border-secondary d-flex justify-content-between">
                    <button type="button" class="btn btn-sm btn-outline-info" id="btnTestTunnelUrl" onclick="testModalConnection()">⚡ Test Connection</button>
                    <div class="d-flex gap-2">
                        <button type="button" class="btn btn-sm btn-outline-secondary" data-bs-dismiss="modal">Cancel</button>
                        <button type="button" class="btn btn-sm btn-primary" id="btnSaveTunnelUrl" onclick="saveTunnelUrl()">Save URL</button>
                    </div>
                </div>
            </div>
        </div>
    </div>
</body>
</html>
""".trimIndent()
}
