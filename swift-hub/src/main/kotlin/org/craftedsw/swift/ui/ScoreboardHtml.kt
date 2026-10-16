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
                                        <th>Total Money</th>
                                        <th>Score</th>
                                        <th>Processed</th>
                                        <th>Success Rate</th>
                                        <th>Status</th>
                                        <th></th>
                                    </tr>
                                </thead>
                                <tbody id="leaderboardBody">
                                    <tr><td colspan="9" class="text-center py-4 text-secondary">No banks registered yet. Run <code>./gradlew :bank-starter:run</code> to connect.</td></tr>
                                </tbody>
                            </table>
                        </div>
                    </div>
                </div>

                <!-- 3 Test Phases Control Center -->
                <div class="card mb-4 border-info">
                    <div class="card-header bg-dark bg-gradient d-flex justify-content-between align-items-center">
                        <div>
                            <span class="fs-5 fw-bold text-white">🧪 Test Simulator Phase Controllers</span>
                            <span class="text-secondary small ms-2">• Evaluates registered banks every 10s • 1 point per scenario</span>
                        </div>
                        <div class="d-flex gap-2 align-items-center">
                            <span id="simStatus" class="badge bg-secondary">Traffic: Idle</span>
                            <button id="btnToggleSim" class="btn btn-sm btn-outline-secondary" onclick="toggleSim()">⚡ Background Traffic</button>
                        </div>
                    </div>
                    <div class="card-body">
                        <div class="row g-3">
                            <!-- Phase 1: Deposits Column -->
                            <div class="col-md-4">
                                <div class="card h-100 bg-black bg-opacity-25 border-secondary">
                                    <div class="card-header d-flex justify-content-between align-items-center py-2">
                                        <div>
                                            <span class="fw-bold text-info">1. Deposits</span>
                                            <div class="small text-secondary" style="font-size: 0.75rem;">+50€ &amp; -50€ Rejection</div>
                                        </div>
                                        <span id="phaseTimerDeposits" class="badge bg-dark border border-info font-monospace text-info">00:00</span>
                                    </div>
                                    <div class="card-body d-flex flex-column p-3">
                                        <div class="d-flex justify-content-between align-items-center mb-2">
                                            <button id="btnPhaseDeposits" class="btn btn-sm btn-success fw-bold px-3" onclick="togglePhase('DEPOSITS')">▶ Play</button>
                                            <button class="btn btn-sm btn-outline-info" onclick="runDepositScenario('deposit')">⚡ Run Now</button>
                                        </div>
                                        <div class="small text-secondary mb-2" id="phaseDescDeposits">
                                            • Deposit 50 EUR (Assert 50€ &amp; statement)<br>
                                            • Deposit -50 EUR (Assert error &amp; no 0€)
                                        </div>
                                        <div id="phaseResultsDeposits" class="mt-auto border-top border-secondary pt-2 small text-secondary">
                                            <em>Awaiting first evaluation...</em>
                                        </div>
                                    </div>
                                </div>
                            </div>

                            <!-- Phase 2: Withdrawals Column -->
                            <div class="col-md-4">
                                <div class="card h-100 bg-black bg-opacity-25 border-secondary">
                                    <div class="card-header d-flex justify-content-between align-items-center py-2">
                                        <div>
                                            <span class="fw-bold text-warning">2. Withdrawals</span>
                                            <div class="small text-secondary" style="font-size: 0.75rem;">Happy, Negative, Overdraft</div>
                                        </div>
                                        <span id="phaseTimerWithdrawals" class="badge bg-dark border border-warning font-monospace text-warning">00:00</span>
                                    </div>
                                    <div class="card-body d-flex flex-column p-3">
                                        <div class="d-flex justify-content-between align-items-center mb-2">
                                            <button id="btnPhaseWithdrawals" class="btn btn-sm btn-success fw-bold px-3" onclick="togglePhase('WITHDRAWALS')">▶ Play</button>
                                            <button class="btn btn-sm btn-outline-warning" onclick="runDepositScenario('withdraw')">⚡ Run Now</button>
                                        </div>
                                        <div class="small text-secondary mb-2" id="phaseDescWithdrawals">
                                            • Deposit 50€ + Withdraw 20€ (Happy)<br>
                                            • Withdraw -50 EUR (Assert rejection)<br>
                                            • Withdraw > balance (Assert rejection)
                                        </div>
                                        <div id="phaseResultsWithdrawals" class="mt-auto border-top border-secondary pt-2 small text-secondary">
                                            <em>Awaiting first evaluation...</em>
                                        </div>
                                    </div>
                                </div>
                            </div>

                            <!-- Phase 3: Transfers Column -->
                            <div class="col-md-4">
                                <div class="card h-100 bg-black bg-opacity-25 border-secondary">
                                    <div class="card-header d-flex justify-content-between align-items-center py-2">
                                        <div>
                                            <span class="fw-bold text-primary">3. Transfers</span>
                                            <div class="small text-secondary" style="font-size: 0.75rem;">Intra-Bank &amp; Cross-Bank</div>
                                        </div>
                                        <span id="phaseTimerTransfers" class="badge bg-dark border border-primary font-monospace text-primary">00:00</span>
                                    </div>
                                    <div class="card-body d-flex flex-column p-3">
                                        <div class="d-flex justify-content-between align-items-center mb-2">
                                            <button id="btnPhaseTransfers" class="btn btn-sm btn-success fw-bold px-3" onclick="togglePhase('TRANSFERS')">▶ Play</button>
                                            <button class="btn btn-sm btn-outline-primary" onclick="runDepositScenario('transfer')">⚡ Run Now</button>
                                        </div>
                                        <div class="small text-secondary mb-2" id="phaseDescTransfers">
                                            • Intra-Bank transfer within bank<br>
                                            • Cross-Bank transfer via SWIFT Hub
                                        </div>
                                        <div id="phaseResultsTransfers" class="mt-auto border-top border-secondary pt-2 small text-secondary">
                                            <em>Awaiting first evaluation...</em>
                                        </div>
                                    </div>
                                </div>
                            </div>
                        </div>

                        <!-- Manual Scenario Detailed Results Box -->
                        <div id="scenarioResultBox" class="mt-3 d-none">
                            <div class="alert alert-dark border-secondary mb-0 p-3" role="alert">
                                <div class="d-flex justify-content-between align-items-center mb-2 pb-1 border-bottom border-secondary">
                                    <strong class="text-info" id="scenarioResultTitle">🧪 Test Scenario Results</strong>
                                    <button type="button" class="btn-close btn-close-white btn-sm" onclick="dismissScenarioResults()" aria-label="Close"></button>
                                </div>
                                <div id="scenarioResultContent"></div>
                            </div>
                        </div>
                    </div>
                </div>

                <!-- Inter-Bank Transfer Network Graph (Nodes & Edges) -->
                <div class="card mb-4 border-primary">
                    <div class="card-header bg-dark bg-gradient d-flex justify-content-between align-items-center">
                        <div class="d-flex align-items-center gap-2">
                            <span class="fs-5 fw-bold text-white">🌐 Inter-Bank Transfer Network Graph</span>
                            <span class="badge bg-secondary" id="graphNodesBadge">0 nodes</span>
                            <span class="badge bg-info text-dark" id="graphEdgesBadge">0 edges</span>
                        </div>
                        <div class="d-flex gap-2 align-items-center">
                            <span class="small text-secondary me-2" id="graphVolumeSummary">Settled: € 0.00</span>
                            <button class="btn btn-sm btn-outline-secondary" onclick="resetGraphLayout()" title="Rearrange nodes in a circular layout">🔄 Reset Layout</button>
                        </div>
                    </div>
                    <div class="card-body p-2">
                        <div class="graph-container" id="graphContainer">
                            <svg id="networkGraphSvg" class="graph-svg" viewBox="0 0 800 420" preserveAspectRatio="xMidYMid meet">
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
                                <div class="fs-4 mb-2">🕸️</div>
                                <div>Awaiting registered banks &amp; inter-bank transactions...</div>
                            </div>
                            <div id="graphTooltip" class="position-absolute p-2 bg-black bg-opacity-75 border border-secondary rounded shadow small text-light d-none" style="pointer-events: none; z-index: 100; font-size: 0.75rem;"></div>
                        </div>
                    </div>
                </div>

                <!-- Total Money History (Line Chart) -->
                <div class="card">
                    <div class="card-header d-flex justify-content-between align-items-center">
                        <span>📈 Bank Money Timeline (Relative Scale)</span>
                        <span class="badge bg-secondary" id="lineChartPointsBadge">Live History</span>
                    </div>
                    <div class="card-body">
                        <div id="lineChartContainer" style="position: relative; height: 260px;">
                            <canvas id="moneyLineChart"></canvas>
                        </div>
                        <div id="lineChartEmptyNotice" class="text-center py-4 text-secondary small d-none">
                            Awaiting registered banks to plot timeline...
                        </div>
                    </div>
                </div>
            </div>

            <!-- Right Column: Registration, Pie Chart & Live Feed -->
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

                <!-- Total Money per Bank (Pie Chart) -->
                <div class="card mb-3">
                    <div class="card-header d-flex justify-content-between align-items-center">
                        <span>💰 Total Money per Bank</span>
                        <span class="badge bg-info text-dark" id="chartTotalBadge">€ 0.00</span>
                    </div>
                    <div class="card-body">
                        <div id="chartContainer" style="position: relative; height: 260px;">
                            <canvas id="moneyPieChart"></canvas>
                        </div>
                        <div id="chartEmptyNotice" class="text-center py-4 text-secondary small d-none">
                            Awaiting settled transactions...
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

        const palette = [
            '#58a6ff', '#3fb950', '#d29922', '#f85149', '#a371f7',
            '#39c5bb', '#f0883e', '#79c0ff', '#56d364', '#e3b341',
            '#db61a2', '#7ee787', '#1f6feb', '#238636', '#8957e5'
        ];

        let moneyChart = null;
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
            const radiusX = Math.min(width * 0.38, 300);
            const radiusY = Math.min(height * 0.36, 150);

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
            const edgesBadge = document.getElementById('graphEdgesBadge');
            const volumeSummary = document.getElementById('graphVolumeSummary');

            if (!svg || !edgesLayer || !nodesLayer) return;

            const currentNodes = (graph && graph.nodes && graph.nodes.length > 0) ? graph.nodes : (banks || []);
            const currentEdges = (graph && graph.edges) ? graph.edges : [];

            if (nodesBadge) nodesBadge.innerText = currentNodes.length + ' node' + (currentNodes.length === 1 ? '' : 's');
            if (edgesBadge) edgesBadge.innerText = currentEdges.length + ' edge' + (currentEdges.length === 1 ? '' : 's');

            const totalEdgeCents = currentEdges.reduce((sum, e) => sum + (e.totalAmountCents || 0), 0);
            if (volumeSummary) volumeSummary.innerText = 'Transferred: ' + formatCents(totalEdgeCents);

            if (currentNodes.length === 0) {
                edgesLayer.innerHTML = '';
                nodesLayer.innerHTML = '';
                if (emptyNotice) emptyNotice.classList.remove('d-none');
                return;
            }

            if (emptyNotice) emptyNotice.classList.add('d-none');

            const width = 800;
            const height = 420;

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
                const srcPos = graphPositions[edge.sourceBic] || defaultPos[edge.sourceBic] || { x: 200, y: 200 };
                const dstPos = graphPositions[edge.targetBic] || defaultPos[edge.targetBic] || { x: 600, y: 200 };

                const isSelf = edge.sourceBic === edge.targetBic;
                let pathD = '';
                let labelX = 0;
                let labelY = 0;

                if (isSelf) {
                    const sx = srcPos.x;
                    const sy = srcPos.y - 28;
                    pathD = `M ${'$'}{sx - 15} ${'$'}{sy} C ${'$'}{sx - 45} ${'$'}{sy - 55}, ${'$'}{sx + 45} ${'$'}{sy - 55}, ${'$'}{sx + 15} ${'$'}{sy}`;
                    labelX = sx;
                    labelY = sy - 44;
                } else {
                    const dx = dstPos.x - srcPos.x;
                    const dy = dstPos.y - srcPos.y;
                    const dist = Math.sqrt(dx * dx + dy * dy) || 1;
                    const nx = -dy / dist;
                    const ny = dx / dist;

                    const offset = 28;
                    const cx = (srcPos.x + dstPos.x) / 2 + nx * offset;
                    const cy = (srcPos.y + dstPos.y) / 2 + ny * offset;

                    pathD = `M ${'$'}{srcPos.x} ${'$'}{srcPos.y} Q ${'$'}{cx} ${'$'}{cy} ${'$'}{dstPos.x} ${'$'}{dstPos.y}`;
                    labelX = (srcPos.x + 2 * cx + dstPos.x) / 4;
                    labelY = (srcPos.y + 2 * cy + dstPos.y) / 4;
                }

                const strokeColor = palette[idx % palette.length];
                const strokeWidth = Math.min(6, Math.max(2, Math.log10(Math.max(100, (edge.totalAmountCents || 0) / 100))));
                const amountFormatted = formatCents(edge.totalAmountCents || 0);

                edgesHtml += `
                    <g class="graph-edge-group" data-edge-src="${'$'}{escapeHtml(edge.sourceBic)}" data-edge-dst="${'$'}{escapeHtml(edge.targetBic)}" data-edge-amount="${'$'}{escapeHtml(amountFormatted)}" data-edge-count="${'$'}{edge.transactionCount}" data-edge-success="${'$'}{edge.successfulCount}">
                        <path d="${'$'}{pathD}" class="graph-edge" stroke="${'$'}{strokeColor}" stroke-width="${'$'}{strokeWidth}" marker-end="url(#arrow-head)" opacity="0.85" />
                        <g transform="translate(${'$'}{labelX}, ${'$'}{labelY})">
                            <rect x="-42" y="-14" width="84" height="25" class="edge-label-bg" />
                            <text y="-2" class="edge-label-text">${'$'}{escapeHtml(amountFormatted)}</text>
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

                nodesHtml += `
                    <g class="graph-node" data-node-bic="${'$'}{escapeHtml(node.bic)}" data-node-name="${'$'}{escapeHtml(node.name)}" data-node-status="${'$'}{escapeHtml(node.status)}" data-node-money="${'$'}{escapeHtml(moneyFormatted)}" data-node-score="${'$'}{node.score || 0}" transform="translate(${'$'}{pos.x}, ${'$'}{pos.y})">
                        <circle r="30" fill="#161b22" stroke="${'$'}{statusColor}" stroke-width="3" filter="url(#node-glow)" />
                        <circle r="26" fill="#1f242c" stroke="${'$'}{color}" stroke-width="1.5" />
                        <text y="-38" fill="#c9d1d9" font-family="-apple-system, BlinkMacSystemFont, Segoe UI, Roboto, sans-serif" font-size="11" font-weight="600" text-anchor="middle">${'$'}{escapeHtml(node.name)}</text>
                        <text y="4" fill="#ffffff" font-family="monospace" font-size="11" font-weight="700" text-anchor="middle">${'$'}{escapeHtml(node.bic)}</text>
                        <g transform="translate(0, 42)">
                            <rect x="-38" y="-10" width="76" height="18" fill="#161b22" stroke="#30363d" rx="4" />
                            <text y="2" fill="#58a6ff" font-family="monospace" font-size="9.5" font-weight="600" text-anchor="middle">${'$'}{escapeHtml(moneyFormatted)}</text>
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

                nodeEl.onmousedown = (e) => startDrag(e, bic);
                nodeEl.ontouchstart = (e) => startDrag(e, bic);

                nodeEl.onmouseenter = (e) => {
                    if (tooltip) {
                        tooltip.innerHTML = `<strong>${'$'}{escapeHtml(name)}</strong> (<code>${'$'}{escapeHtml(bic)}</code>)<br>Status: <span class="badge ${'$'}{status === 'HEALTHY' ? 'bg-success' : 'bg-warning text-dark'}">${'$'}{escapeHtml(status)}</span><br>Balance: <span class="text-info font-monospace">${'$'}{escapeHtml(money)}</span><br>Score: <span class="text-primary font-monospace">${'$'}{escapeHtml(score)} pts</span>`;
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
            const currentPos = graphPositions[bic] || { x: 400, y: 200 };
            dragOffset = { x: pt.x - currentPos.x, y: pt.y - currentPos.y };

            const onMove = (moveEvt) => {
                if (!activeDraggedNode) return;
                const movePt = getSvgCoordinates(moveEvt, svg);
                const newX = Math.max(35, Math.min(765, movePt.x - dragOffset.x));
                const newY = Math.max(45, Math.min(375, movePt.y - dragOffset.y));
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

        function updateMoneyChart(banks) {
            const canvas = document.getElementById('moneyPieChart');
            const emptyNotice = document.getElementById('chartEmptyNotice');
            const totalBadge = document.getElementById('chartTotalBadge');
            if (!canvas) return;

            const registeredBanks = banks || [];
            const totalCents = registeredBanks.reduce((acc, b) => acc + (b.totalMoneyCents || 0), 0);
            if (totalBadge) {
                totalBadge.innerText = formatCents(totalCents);
            }

            const banksWithMoney = registeredBanks.filter(b => (b.totalMoneyCents || 0) > 0);

            if (banksWithMoney.length === 0) {
                if (emptyNotice) emptyNotice.classList.remove('d-none');
                if (canvas) canvas.style.display = 'none';
                if (moneyChart) {
                    moneyChart.destroy();
                    moneyChart = null;
                }
                return;
            }

            if (emptyNotice) emptyNotice.classList.add('d-none');
            if (canvas) canvas.style.display = 'block';

            const labels = banksWithMoney.map(b => b.name + ' (' + b.bic + ')');
            const dataValues = banksWithMoney.map(b => (b.totalMoneyCents / 100));
            const bgColors = labels.map((_, i) => palette[i % palette.length]);

            if (!moneyChart) {
                const ctx = canvas.getContext('2d');
                moneyChart = new Chart(ctx, {
                    type: 'pie',
                    data: {
                        labels: labels,
                        datasets: [{
                            data: dataValues,
                            backgroundColor: bgColors,
                            borderColor: '#161b22',
                            borderWidth: 2
                        }]
                    },
                    options: {
                        responsive: true,
                        maintainAspectRatio: false,
                        plugins: {
                            legend: {
                                position: 'bottom',
                                labels: {
                                    color: '#c9d1d9',
                                    boxWidth: 12,
                                    padding: 8,
                                    font: { size: 11 }
                                }
                            },
                            tooltip: {
                                callbacks: {
                                    label: function(context) {
                                        const val = context.raw || 0;
                                        const sum = dataValues.reduce((a, b) => a + b, 0);
                                        const pct = sum > 0 ? ((val / sum) * 100).toFixed(1) : 0;
                                        return ' ' + context.label + ': € ' + val.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' (' + pct + '%)';
                                    }
                                }
                            }
                        }
                    }
                });
            } else {
                moneyChart.data.labels = labels;
                moneyChart.data.datasets[0].data = dataValues;
                moneyChart.data.datasets[0].backgroundColor = bgColors;
                moneyChart.update();
            }
        }

        let moneyLineChart = null;
        const lineTimeLabels = [];
        const bankHistory = {};
        const MAX_HISTORY_POINTS = 60;

        function updateMoneyLineChart(banks) {
            const canvas = document.getElementById('moneyLineChart');
            const emptyNotice = document.getElementById('lineChartEmptyNotice');
            if (!canvas) return;

            const registeredBanks = banks || [];
            if (registeredBanks.length === 0) {
                if (emptyNotice) emptyNotice.classList.remove('d-none');
                if (canvas) canvas.style.display = 'none';
                if (moneyLineChart) {
                    moneyLineChart.destroy();
                    moneyLineChart = null;
                }
                return;
            }

            if (emptyNotice) emptyNotice.classList.add('d-none');
            if (canvas) canvas.style.display = 'block';

            const nowLabel = new Date().toLocaleTimeString();
            lineTimeLabels.push(nowLabel);
            if (lineTimeLabels.length > MAX_HISTORY_POINTS) {
                lineTimeLabels.shift();
            }

            registeredBanks.forEach((b, idx) => {
                const val = (b.totalMoneyCents || 0) / 100;
                if (!bankHistory[b.bic]) {
                    const initial = Array(Math.max(0, lineTimeLabels.length - 1)).fill(0);
                    bankHistory[b.bic] = {
                        name: b.name,
                        bic: b.bic,
                        color: palette[idx % palette.length],
                        data: initial
                    };
                }
                bankHistory[b.bic].name = b.name;
                bankHistory[b.bic].data.push(val);
                if (bankHistory[b.bic].data.length > MAX_HISTORY_POINTS) {
                    bankHistory[b.bic].data.shift();
                }
            });

            Object.keys(bankHistory).forEach(bic => {
                const isCurrentlyRegistered = registeredBanks.some(b => b.bic === bic);
                if (!isCurrentlyRegistered) {
                    const lastVal = bankHistory[bic].data.length > 0 ? bankHistory[bic].data[bankHistory[bic].data.length - 1] : 0;
                    bankHistory[bic].data.push(lastVal);
                    if (bankHistory[bic].data.length > MAX_HISTORY_POINTS) {
                        bankHistory[bic].data.shift();
                    }
                }
            });

            const datasets = Object.keys(bankHistory).map(bic => {
                const item = bankHistory[bic];
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

            if (!moneyLineChart) {
                const ctx = canvas.getContext('2d');
                moneyLineChart = new Chart(ctx, {
                    type: 'line',
                    data: {
                        labels: [...lineTimeLabels],
                        datasets: datasets
                    },
                    options: {
                        responsive: true,
                        maintainAspectRatio: false,
                        animation: false,
                        interaction: {
                            mode: 'index',
                            intersect: false
                        },
                        scales: {
                            x: {
                                grid: { color: '#21262d' },
                                ticks: {
                                    color: '#8b949e',
                                    maxTicksLimit: 8,
                                    font: { size: 10 }
                                }
                            },
                            y: {
                                beginAtZero: true,
                                grid: { color: '#21262d' },
                                ticks: {
                                    color: '#8b949e',
                                    font: { size: 10 },
                                    callback: function(value) {
                                        return '€ ' + value.toLocaleString('en-US');
                                    }
                                }
                            }
                        },
                        plugins: {
                            legend: {
                                position: 'top',
                                labels: {
                                    color: '#c9d1d9',
                                    boxWidth: 12,
                                    padding: 8,
                                    font: { size: 11 }
                                }
                            },
                            tooltip: {
                                callbacks: {
                                    label: function(context) {
                                        const val = context.raw || 0;
                                        return ' ' + context.dataset.label + ': € ' + val.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
                                    }
                                }
                            }
                        }
                    }
                });
            } else {
                moneyLineChart.data.labels = [...lineTimeLabels];
                moneyLineChart.data.datasets = datasets;
                moneyLineChart.update('none');
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
            updateMoneyChart(data.banks);
            updateMoneyLineChart(data.banks);
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
                const badgeClass = b.status === 'HEALTHY' ? 'badge-healthy' : (b.status === 'DEGRADED' ? 'badge-degraded' : 'badge-down');

                html += `
                    <tr>
                        <td class="ps-3 font-monospace text-secondary">${'$'}{index + 1}</td>
                        <td><span class="badge bg-dark border border-secondary font-monospace">${'$'}{b.bic}</span></td>
                        <td class="fw-bold text-white">${'$'}{b.name}</td>
                        <td class="text-info fw-bold font-monospace">${'$'}{formatCents(b.totalMoneyCents || 0)}</td>
                        <td class="text-primary fw-bold font-monospace">${'$'}{b.score} pts</td>
                        <td>${'$'}{b.totalTransactions}</td>
                        <td><span class="text-success">${'$'}{rate}</span></td>
                        <td><span class="badge ${'$'}{badgeClass}">${'$'}{b.status}</span></td>
                        <td class="text-end pe-3"><button class="btn btn-sm btn-outline-danger" title="Remove bank" onclick="removeBank('${'$'}{b.bic}', this)">🗑 Remove</button></td>
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
                delete bankHistory[bic];
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
                        ${'$'}{dep.results.map(r => `<div><span class="${'$'}{r.passed ? 'text-success' : 'text-danger'}">${'$'}{r.passed ? '✓' : '✗'}</span> ${'$'}{escapeHtml(r.bic)} - ${'$'}{escapeHtml(r.scenario)}</div>`).join('')}
                    </div>
                `;
            }

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
                        ${'$'}{withDr.results.map(r => `<div><span class="${'$'}{r.passed ? 'text-success' : 'text-danger'}">${'$'}{r.passed ? '✓' : '✗'}</span> ${'$'}{escapeHtml(r.bic)} - ${'$'}{escapeHtml(r.scenario)}</div>`).join('')}
                    </div>
                `;
            }

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
                        ${'$'}{trans.results.map(r => `<div><span class="${'$'}{r.passed ? 'text-success' : 'text-danger'}">${'$'}{r.passed ? '✓' : '✗'}</span> ${'$'}{escapeHtml(r.bic)} - ${'$'}{escapeHtml(r.scenario)}</div>`).join('')}
                    </div>
                `;
            }
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

            const scenarioName = type === 'deposit-positive' ? '+50.00 EUR Deposit Test' :
                                 type === 'deposit-negative' ? '-50.00 EUR Negative Deposit Test' :
                                 type === 'withdraw' ? 'Deposit & Withdraw Test' :
                                 'All Test Scenarios';
            titleEl.innerHTML = '🧪 ' + scenarioName;
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
                    data.results.forEach(r => {
                        const badgeClass = r.passed ? 'bg-success' : 'bg-danger';
                        const badgeText = r.passed ? 'PASSED' : 'FAILED';
                        html += `
                            <div class="list-group-item bg-dark bg-opacity-75 text-light border-secondary px-3 py-2 mb-2 rounded">
                                <div class="d-flex justify-content-between align-items-center mb-1">
                                    <div>
                                        <span class="fw-bold">${'$'}{escapeHtml(r.bankName)}</span>
                                        <code class="text-info ms-1">${'$'}{escapeHtml(r.bic)}</code>
                                        <span class="badge bg-secondary ms-2">${'$'}{escapeHtml(r.scenario)}</span>
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
    </script>
</body>
</html>
""".trimIndent()
}
