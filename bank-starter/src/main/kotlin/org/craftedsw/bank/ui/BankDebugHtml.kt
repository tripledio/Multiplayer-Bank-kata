package org.craftedsw.bank.ui

object BankDebugHtml {

    fun render(bankName: String, bic: String, swiftHubUrl: String?): String = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Bank Debug Dashboard - $bankName ($bic)</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css" rel="stylesheet">
    <script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
    <style>
        body { background-color: #0d1117; color: #c9d1d9; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
        .card { background-color: #161b22; border: 1px solid #30363d; border-radius: 8px; margin-bottom: 20px; }
        .card-header { background-color: #21262d; border-bottom: 1px solid #30363d; font-weight: 600; }
        .kpi-val { font-size: 2rem; font-weight: 700; color: #58a6ff; }
        .pulse { animation: pulse-animation 2s infinite; }
        @keyframes pulse-animation { 0% { opacity: 1; } 50% { opacity: 0.4; } 100% { opacity: 1; } }
        pre.http-block { background-color: #0d1117; border: 1px solid #30363d; border-radius: 6px; padding: 12px; color: #79c0ff; font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace; font-size: 0.88rem; overflow-x: auto; white-space: pre-wrap; word-break: break-word; }
        pre.response-block { background-color: #0d1117; border: 1px solid #30363d; border-radius: 6px; padding: 10px; color: #e6edf3; font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace; font-size: 0.85rem; overflow-x: auto; white-space: pre-wrap; word-break: break-word; }
        .badge-scenario { font-family: ui-monospace, monospace; font-size: 0.85rem; letter-spacing: 0.5px; }
        .btn-copy { font-size: 0.75rem; padding: 2px 8px; }
        .scenario-group { background: #161b22; border: 1px solid #30363d; border-radius: 8px; margin-bottom: 24px; overflow: hidden; }
        .scenario-group-header { background: #21262d; border-bottom: 1px solid #30363d; padding: 12px 16px; font-weight: 600; display: flex; justify-content: space-between; align-items: center; }
        .report-item { border-left: 4px solid #f85149; background: #0d1117; margin-bottom: 12px; border-radius: 4px; border: 1px solid #21262d; border-left-width: 4px; }
        .report-item:last-child { margin-bottom: 0; }
        .report-item.passed { border-left-color: #3fb950; }
        .copy-toast { position: fixed; bottom: 20px; right: 20px; z-index: 9999; display: none; }
    </style>
</head>
<body class="p-3 p-md-4">
    <div class="container-fluid" style="max-width: 1400px;">
        <!-- Header -->
        <div class="d-flex flex-wrap justify-content-between align-items-center mb-4 pb-3 border-bottom border-secondary gap-3">
            <div>
                <div class="d-flex align-items-center gap-2">
                    <h1 class="h3 text-white mb-0">☕ $bankName</h1>
                    <span class="badge bg-primary font-monospace fs-6">$bic</span>
                </div>
                <p class="text-secondary small mb-0 mt-1">Participant Bank Node Debug Dashboard • Live Scenario Feedback & IntelliJ HTTP Suite</p>
            </div>
            <div class="d-flex align-items-center gap-3">
                <div class="form-check form-switch text-secondary small">
                    <input class="form-check-input" type="checkbox" id="autoRefreshSwitch" checked>
                    <label class="form-check-label" for="autoRefreshSwitch">Auto-refresh (2s)</label>
                </div>
                <button class="btn btn-sm btn-outline-info" onclick="fetchDebugReports()">🔄 Refresh</button>
                <button class="btn btn-sm btn-outline-danger" onclick="clearDebugReports()">🗑️ Clear Logs</button>
                <span id="liveBadge" class="badge bg-success pulse px-3 py-2">LIVE</span>
            </div>
        </div>

        <!-- Summary KPIs -->
        <div class="row g-3 mb-4">
            <div class="col-md-4">
                <div class="card p-3 text-center">
                    <div class="text-secondary small text-uppercase">Recorded Failed Requests</div>
                    <div id="kpiFailureCount" class="kpi-val text-danger">0</div>
                </div>
            </div>
            <div class="col-md-4">
                <div class="card p-3 text-center">
                    <div class="text-secondary small text-uppercase">Latest Failing Scenario</div>
                    <div id="kpiLatestScenario" class="kpi-val text-warning text-truncate" style="font-size: 1.25rem; line-height: 2.5rem;">None</div>
                </div>
            </div>
            <div class="col-md-4">
                <div class="card p-3 text-center">
                    <div class="text-secondary small text-uppercase">SWIFT Hub Connection</div>
                    <div id="kpiHubStatus" class="kpi-val text-info text-truncate" style="font-size: 1.1rem; line-height: 2.5rem;">
                        ${swiftHubUrl ?: "Not configured"}
                    </div>
                </div>
            </div>
        </div>

        <!-- Instructions Alert -->
        <div class="alert alert-dark border-secondary d-flex align-items-center justify-content-between p-3 mb-4" role="alert">
            <div>
                <strong>💡 How to use these debug testcases:</strong>
                <span class="text-secondary ms-2">
                    When the central SWIFT simulator runs scenarios against your bank node, failed requests are captured below in <strong>IntelliJ HTTP Client</strong> format. Click <strong>"📋 Copy HTTP Request"</strong> and paste into <code>requests/bank-api.http</code> or a scratchpad in IntelliJ IDEA, then run it with the green play arrow (▶) to reproduce and debug locally!
                </span>
            </div>
        </div>

        <!-- Main Content: Debug Reports Feed -->
        <div class="d-flex justify-content-between align-items-center mb-3">
            <h5 class="text-white mb-0">🔍 Failing Testcases Grouped by Scenario</h5>
            <span id="reportCountBadge" class="badge bg-secondary">0 captured</span>
        </div>
        <div id="reportsContainer">
            <div class="card p-5 text-center text-secondary" id="emptyState">
                <h5>✨ No failing requests captured yet!</h5>
                <p class="small mb-0">Whenever SWIFT Hub runs a test scenario that fails against your bank, the exact request and expected vs received response will appear here automatically grouped per scenario.</p>
            </div>
        </div>
    </div>

    <!-- Toast for copy notification -->
    <div id="copyToast" class="toast align-items-center text-bg-success border-0 copy-toast" role="alert" aria-live="assertive" aria-atomic="true">
        <div class="d-flex">
            <div class="toast-body">
                📋 IntelliJ HTTP request copied to clipboard!
            </div>
        </div>
    </div>

    <script>
        let previousReportsJson = "";

        async function fetchDebugReports() {
            try {
                const res = await fetch('/api/debug');
                if (!res.ok) return;
                const reports = await res.json();
                const jsonStr = JSON.stringify(reports);
                if (jsonStr === previousReportsJson) return;
                previousReportsJson = jsonStr;
                renderReports(reports);
            } catch (e) {
                console.error("Error fetching debug reports:", e);
            }
        }

        async function clearDebugReports() {
            try {
                await fetch('/api/debug', { method: 'DELETE' });
                fetchDebugReports();
            } catch (e) {
                console.error("Error clearing debug reports:", e);
            }
        }

        function copyToClipboard(text) {
            navigator.clipboard.writeText(text).then(() => {
                const toastEl = document.getElementById('copyToast');
                toastEl.style.display = 'block';
                setTimeout(() => { toastEl.style.display = 'none'; }, 2000);
            });
        }

        function escapeHtml(str) {
            if (!str) return '';
            return str
                .replace(/&/g, "&amp;")
                .replace(/</g, "&lt;")
                .replace(/>/g, "&gt;")
                .replace(/"/g, "&quot;")
                .replace(/'/g, "&#039;");
        }

        function renderReports(reports) {
            const count = reports.length;
            document.getElementById('kpiFailureCount').textContent = count;
            document.getElementById('reportCountBadge').textContent = count + ' captured';

            if (count > 0) {
                document.getElementById('kpiLatestScenario').textContent = reports[0].scenario || "Unknown";
            } else {
                document.getElementById('kpiLatestScenario').textContent = "None";
            }

            const container = document.getElementById('reportsContainer');
            if (count === 0) {
                container.innerHTML = `
                    <div class="card p-5 text-center text-secondary" id="emptyState">
                        <h5>✨ No failing requests captured yet!</h5>
                        <p class="small mb-0">Whenever SWIFT Hub runs a test scenario that fails against your bank, the exact request and expected vs received response will appear here automatically grouped per scenario.</p>
                    </div>
                `;
                return;
            }

            // Group reports by scenario
            const groups = {};
            reports.forEach((report) => {
                const scenario = report.scenario || 'General';
                if (!groups[scenario]) {
                    groups[scenario] = [];
                }
                groups[scenario].push(report);
            });

            let html = '';
            for (const [scenario, scenarioReports] of Object.entries(groups)) {
                const failedCount = scenarioReports.filter(r => !r.passed).length;
                html += `
                    <div class="scenario-group">
                        <div class="scenario-group-header">
                            <div class="d-flex align-items-center gap-2">
                                <span class="badge bg-danger badge-scenario">${'$'}{escapeHtml(scenario)}</span>
                                <span class="text-white fw-bold small">${'$'}{scenarioReports.length} ${'$'}{scenarioReports.length === 1 ? 'failure' : 'failures'}</span>
                            </div>
                            <div class="text-secondary small">
                                Latest: ${'$'}{scenarioReports[0].timestamp ? new Date(scenarioReports[0].timestamp).toLocaleTimeString() : 'N/A'}
                            </div>
                        </div>
                        <div class="p-3">
                `;

                scenarioReports.forEach((report) => {
                    const dateStr = report.timestamp ? new Date(report.timestamp).toLocaleTimeString() : '';
                    const escapedHttp = escapeHtml(report.httpRequest);
                    const rawHttpJson = JSON.stringify(report.httpRequest);

                    html += `
                        <div class="card report-item p-3 ${'$'}{report.passed ? 'passed' : ''}">
                            <div class="d-flex flex-wrap justify-content-between align-items-center mb-2 gap-2">
                                <div class="d-flex align-items-center gap-2">
                                    <span class="text-white fw-bold">${'$'}{escapeHtml(report.message)}</span>
                                </div>
                                <div class="d-flex align-items-center gap-2">
                                    <span class="text-secondary small font-monospace">${'$'}{dateStr}</span>
                                    <button class="btn btn-outline-primary btn-sm btn-copy" onclick='copyToClipboard(${'$'}{rawHttpJson})'>
                                        📋 Copy HTTP Request
                                    </button>
                                </div>
                            </div>

                            <!-- HTTP Request (IntelliJ Format) -->
                            <div class="mt-2">
                                <div class="d-flex justify-content-between align-items-center mb-1">
                                    <span class="text-secondary small text-uppercase fw-semibold" style="font-size: 0.75rem;">IntelliJ HTTP Client Snippet:</span>
                                </div>
                                <pre class="http-block mb-2"><code>${'$'}{escapedHttp}</code></pre>
                            </div>

                            <!-- Expected vs Actual Response Grid -->
                            <div class="row g-2 mt-1">
                                <div class="col-md-6">
                                    <div class="text-secondary small text-uppercase fw-semibold mb-1 text-success" style="font-size: 0.75rem;">Expected Response / State:</div>
                                    <pre class="response-block border-success border-opacity-25" style="color: #7ee787;"><code>${'$'}{escapeHtml(report.expectedResponse)}</code></pre>
                                </div>
                                <div class="col-md-6">
                                    <div class="text-secondary small text-uppercase fw-semibold mb-1 text-danger" style="font-size: 0.75rem;">Actual Received Response / Error:</div>
                                    <pre class="response-block border-danger border-opacity-25" style="color: #ff7b72;"><code>${'$'}{escapeHtml(report.actualResponse)}</code></pre>
                                </div>
                            </div>
                        </div>
                    `;
                });

                html += `
                        </div>
                    </div>
                `;
            }

            container.innerHTML = html;
        }

        // Auto-refresh interval
        setInterval(() => {
            if (document.getElementById('autoRefreshSwitch').checked) {
                fetchDebugReports();
            }
        }, 2000);

        // Initial fetch
        fetchDebugReports();
    </script>
</body>
</html>
    """.trimIndent()
}
