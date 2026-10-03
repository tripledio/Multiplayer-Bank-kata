package org.craftedsw.swift.ui

object RegisterHtml {

    fun render(): String = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Register Your Bank - SWIFT Hub</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css" rel="stylesheet">
    <style>
        body { background-color: #0d1117; color: #c9d1d9; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
        .card { background-color: #161b22; border: 1px solid #30363d; border-radius: 8px; }
        .card-header { background-color: #21262d; border-bottom: 1px solid #30363d; font-weight: 600; }
        .form-control { background-color: #0d1117; border: 1px solid #30363d; color: #c9d1d9; }
        .form-control:focus { background-color: #0d1117; border-color: #58a6ff; color: #ffffff; box-shadow: 0 0 0 0.25rem rgba(88, 166, 255, 0.25); }
        .form-label { font-weight: 500; color: #8b949e; }
        .bic-display { font-size: 2.2rem; font-weight: 700; letter-spacing: 2px; color: #58a6ff; font-family: monospace; }
        .code-box { background-color: #0d1117; border: 1px solid #30363d; border-radius: 6px; padding: 12px; font-family: monospace; word-break: break-all; }
    </style>
</head>
<body class="p-3 p-md-5">
    <div class="container" style="max-width: 680px;">
        <!-- Header -->
        <div class="text-center mb-4">
            <h1 class="h2 text-white mb-2">Register Your Bank</h1>
            <p class="text-secondary">Join the live SWIFT network. Enter your details below to generate your 8-character BIC code.</p>
        </div>

        <!-- Registration Form Card -->
        <div class="card shadow-sm mb-4" id="formCard">
            <div class="card-header d-flex justify-content-between align-items-center">
                <span>🏦 Bank Node Details</span>
                <a href="/" class="btn btn-sm btn-outline-secondary">Scoreboard</a>
            </div>
            <div class="card-body p-4">
                <form id="registerForm">
                    <div class="mb-3">
                        <label for="bankName" class="form-label">Bank Name</label>
                        <input type="text" class="form-control form-control-lg" id="bankName" placeholder="e.g. Bank Alpha" required>
                        <div class="form-text text-secondary">The public display name for your bank on the live scoreboard.</div>
                    </div>

                    <div class="mb-4">
                        <label for="webhookUrl" class="form-label">Webhook URL (Tunnel / LAN)</label>
                        <input type="url" class="form-control form-control-lg" id="webhookUrl" placeholder="https://brave-fox-42.loca.lt" required>
                        <div class="form-text text-secondary">Your localtunnel, ngrok, or LAN URL where SWIFT transfers will be routed.</div>
                    </div>

                    <div id="errorAlert" class="alert alert-danger d-none mb-3" role="alert"></div>

                    <button type="submit" id="btnSubmit" class="btn btn-primary btn-lg w-100 fw-bold">
                        Register Bank
                    </button>
                </form>
            </div>
        </div>

        <!-- Success Result Card -->
        <div class="card shadow-sm border-success d-none" id="resultCard">
            <div class="card-header bg-success text-white fw-bold">
                🎉 Registration Successful!
            </div>
            <div class="card-body p-4 text-center">
                <p class="text-secondary mb-1">Your Generated SWIFT BIC Code</p>
                <div class="bic-display mb-3" id="resBic">--------</div>

                <div class="text-start mb-3">
                    <label class="form-label">Run your bank app with this command:</label>
                    <div class="code-box text-warning position-relative mb-2" id="resCommand">
                        PORT=8080 BIC=... BANK_NAME="..." ./gradlew :bank-starter:run
                    </div>
                    <button class="btn btn-sm btn-outline-secondary w-100" id="btnCopyCmd" onclick="copyCommand()">
                        📋 Copy Command to Clipboard
                    </button>
                </div>

                <div class="d-flex gap-2 justify-content-center mt-4">
                    <a href="/" class="btn btn-success px-4">View Live Scoreboard</a>
                    <button class="btn btn-outline-secondary" onclick="resetForm()">Register Another Bank</button>
                </div>
            </div>
        </div>

        <!-- Footer -->
        <div class="text-center mt-4 text-secondary small">
            SWIFT Hub • Multiplayer Banking Kata
        </div>
    </div>

    <script>
        let currentCommand = '';

        document.getElementById('registerForm').addEventListener('submit', async (e) => {
            e.preventDefault();
            const bankName = document.getElementById('bankName').value.trim();
            const webhookUrl = document.getElementById('webhookUrl').value.trim();
            const errorAlert = document.getElementById('errorAlert');
            const btnSubmit = document.getElementById('btnSubmit');

            errorAlert.classList.add('d-none');
            errorAlert.innerText = '';
            btnSubmit.disabled = true;
            btnSubmit.innerText = 'Registering...';

            try {
                const response = await fetch('/swift/register', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ name: bankName, webhookUrl: webhookUrl })
                });

                const data = await response.json();

                if (!response.ok) {
                    throw new Error(data.error || data.message || 'Failed to register bank');
                }

                document.getElementById('resBic').innerText = data.bic;
                currentCommand = `PORT=8080 BIC=${'$'}{data.bic} BANK_NAME="${'$'}{bankName}" ./gradlew :bank-starter:run`;
                document.getElementById('resCommand').innerText = currentCommand;

                document.getElementById('formCard').classList.add('d-none');
                document.getElementById('resultCard').classList.remove('d-none');
            } catch (err) {
                errorAlert.innerText = err.message;
                errorAlert.classList.remove('d-none');
            } finally {
                btnSubmit.disabled = false;
                btnSubmit.innerText = 'Register Bank';
            }
        });

        function copyCommand() {
            if (!currentCommand) return;
            navigator.clipboard.writeText(currentCommand).then(() => {
                const btn = document.getElementById('btnCopyCmd');
                const orig = btn.innerText;
                btn.innerText = '✅ Copied!';
                setTimeout(() => { btn.innerText = orig; }, 2000);
            }).catch(() => {
                alert('Copied: ' + currentCommand);
            });
        }

        function resetForm() {
            document.getElementById('registerForm').reset();
            document.getElementById('formCard').classList.remove('d-none');
            document.getElementById('resultCard').classList.add('d-none');
        }
    </script>
</body>
</html>
""".trimIndent()
}
