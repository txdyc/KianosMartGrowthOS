# Runs kiano-worker natively on Windows — the GPU and ComfyUI stay outside
# Docker. Reads KIANO_WORKER_TOKEN (required) plus optional KIANO_WORKER_API /
# KIANO_COMFY_URL from the repo .env, builds the jar when there is none, then
# boots the worker profile from the same jar as the api.
#
# The api side only ever sees KIANO_WORKER_TOKEN_SHA256 (docker-compose.yml);
# the plain token lives in .env and in this process only.
param(
    [string]$ApiUrl = "",
    [string]$ComfyUrl = ""
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repo ".env"

if (-not (Test-Path $envFile)) {
    Write-Error "Missing $envFile — copy .env.example first and fill in KIANO_WORKER_TOKEN."
}

# KEY=VALUE lines; comments (#) and blanks are skipped.
$envVars = @{}
foreach ($line in Get-Content $envFile) {
    $trimmed = $line.Trim()
    if ($trimmed -eq "" -or $trimmed.StartsWith("#")) { continue }
    $parts = $trimmed -split "=", 2
    if ($parts.Length -eq 2) {
        $envVars[$parts[0].Trim()] = $parts[1].Trim()
    }
}

$token = $envVars["KIANO_WORKER_TOKEN"]
if ([string]::IsNullOrEmpty($token)) {
    Write-Error @'
KIANO_WORKER_TOKEN is empty in .env. Generate a pair with PowerShell:

    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $token = [Convert]::ToHexString($bytes).ToLower()
    $sha = [Security.Cryptography.SHA256]::Create()
    $hash = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($token))).Replace("-", "").ToLower()
    $token   # -> KIANO_WORKER_TOKEN
    $hash    # -> KIANO_WORKER_TOKEN_SHA256

Then recreate the api container so it picks up the hash:
    docker compose --profile app up -d --build api
'@
}

$env:KIANO_WORKER_TOKEN = $token
if ($ApiUrl -ne "") {
    $env:KIANO_WORKER_API = $ApiUrl
} elseif ($envVars.ContainsKey("KIANO_WORKER_API") -and $envVars["KIANO_WORKER_API"] -ne "") {
    $env:KIANO_WORKER_API = $envVars["KIANO_WORKER_API"]
}
if ($ComfyUrl -ne "") {
    $env:KIANO_COMFY_URL = $ComfyUrl
} elseif ($envVars.ContainsKey("KIANO_COMFY_URL") -and $envVars["KIANO_COMFY_URL"] -ne "") {
    $env:KIANO_COMFY_URL = $envVars["KIANO_COMFY_URL"]
}

$jar = Get-ChildItem -Path (Join-Path $repo "kiano-api\target") -Filter "kiano-api-*.jar" `
    -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($null -eq $jar) {
    Write-Host "No jar in kiano-api/target - building (tests skipped)..."
    Push-Location (Join-Path $repo "kiano-api")
    try {
        .\mvnw.cmd -q -DskipTests package
    } finally {
        Pop-Location
    }
    $jar = Get-ChildItem -Path (Join-Path $repo "kiano-api\target") -Filter "kiano-api-*.jar" |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($null -eq $jar) {
        Write-Error "Build produced no jar."
    }
}

$api = if ($env:KIANO_WORKER_API) { $env:KIANO_WORKER_API } else { "http://localhost:8081" }
$comfy = if ($env:KIANO_COMFY_URL) { $env:KIANO_COMFY_URL } else { "http://127.0.0.1:8188" }
Write-Host "worker: api=$api comfy=$comfy jar=$($jar.Name)"
Write-Host "worker: Ctrl+C to stop"

java "-Dloader.main=com.kiano.worker.KianoWorkerApplication" "-Dspring.profiles.active=worker" -jar $jar.FullName
