<#
.SYNOPSIS
    Builds the Repossify dashboard and installs it into the backend's static resources.

.DESCRIPTION
    Runs the Next.js static export in the client repository and copies the result into
    src/main/resources/static.

    Everything previously generated is removed first, so hashed bundles from earlier builds
    do not pile up in the jar. The `public/` directory is never touched: it holds assets
    referenced by page.json (the avatar, the favicon) that are not part of the export.

.EXAMPLE
    ./scripts/sync-client.ps1
    ./scripts/sync-client.ps1 -ClientPath ../repossify-client -SkipBuild
#>
[CmdletBinding()]
param(
    [string] $ClientPath = "../repossify-client",
    [switch] $SkipBuild
)

$ErrorActionPreference = "Stop"

$backendRoot = Split-Path -Parent $PSScriptRoot
$client      = Resolve-Path (Join-Path $backendRoot $ClientPath)
$static      = Join-Path $backendRoot "src/main/resources/static"
$export      = Join-Path $client "out"

if (-not (Test-Path $client)) { throw "Client repository not found at $client" }

if (-not $SkipBuild) {
    Write-Host "Building the dashboard in $client ..." -ForegroundColor Cyan
    Push-Location $client
    try {
        $packageManager = if (Test-Path (Join-Path $client "pnpm-lock.yaml")) { "pnpm" } else { "npm" }
        & $packageManager run build
        if ($LASTEXITCODE -ne 0) { throw "Dashboard build failed" }
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path (Join-Path $export "index.html"))) {
    throw "No static export found at $export. Ensure next.config.ts sets output: 'export'."
}

$preserved = Join-Path $static "public"
if (-not (Test-Path $preserved)) {
    throw "$preserved is missing. Refusing to sync, as it would be lost."
}

Write-Host "Replacing generated assets in $static ..." -ForegroundColor Cyan
Get-ChildItem $static -Force | Where-Object { $_.Name -ne "public" } | ForEach-Object {
    Remove-Item $_.FullName -Recurse -Force
}

Copy-Item (Join-Path $export "*") -Destination $static -Recurse -Force

Write-Host "Done. static/ now contains:" -ForegroundColor Green
Get-ChildItem $static -Force | Select-Object -ExpandProperty Name | ForEach-Object { Write-Host "  $_" }
