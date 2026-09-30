param(
    [string]$Registry = $(if ($env:REGISTRY_HOST) { $env:REGISTRY_HOST } else { 'registry.shnea.kr' }),
    [ValidateSet('linux/amd64', 'linux/arm64')][string]$Platform = 'linux/amd64',
    [switch]$Plan
)
$ErrorActionPreference = 'Stop'
$Root = Split-Path $PSScriptRoot -Parent
if ($Registry -notmatch '^[a-zA-Z0-9.-]+(:[0-9]+)?$') { throw 'Invalid registry host.' }
$Images = @(
    @{ Name = 'admin-web'; Context = '.'; Dockerfile = 'apps/admin-web/Dockerfile' },
    @{ Name = 'postgres'; Context = 'infra/postgres'; Dockerfile = 'infra/postgres/Dockerfile' },
    @{ Name = 'keycloak'; Context = 'infra/keycloak'; Dockerfile = 'infra/keycloak/Dockerfile' },
    @{ Name = 'project-service'; Context = '.'; Dockerfile = 'infra/java/Dockerfile'; Service = 'project-service' },
    @{ Name = 'file-service'; Context = '.'; Dockerfile = 'infra/java/Dockerfile'; Service = 'file-service' },
    @{ Name = 'notification-service'; Context = '.'; Dockerfile = 'infra/java/Dockerfile'; Service = 'notification-service' },
    @{ Name = 'nginx'; Context = 'infra/nginx'; Dockerfile = 'infra/nginx/Dockerfile' },
    @{ Name = 'tools'; Context = '.'; Dockerfile = 'infra/tools/Dockerfile' }
)
Push-Location $Root
try {
    $Commit = git rev-parse --verify HEAD
    if ($LASTEXITCODE -ne 0 -or $Commit -notmatch '^[a-f0-9]{40,64}$') { throw 'Cannot resolve source commit.' }
    $Tag = $Commit.Substring(0, 12)
    if ($Plan) {
        $Images | ForEach-Object { Write-Output "$Registry/platform-$($_.Name):$Tag [$Platform] $($_.Dockerfile)" }
        return
    }
    $Changes = git status --porcelain --untracked-files=all
    if ($LASTEXITCODE -ne 0 -or $Changes) { throw 'Commit release changes before publishing.' }
    foreach ($Entry in $Images) {
        $Ref = "$Registry/platform-$($Entry.Name):$Tag"
        # Windows PowerShell turns redirected native stderr into ErrorRecord objects.
        $ErrorActionPreference = 'Continue'
        try { $Probe = & docker buildx imagetools inspect $Ref 2>&1; $ProbeExit = $LASTEXITCODE }
        finally { $ErrorActionPreference = 'Stop' }
        if ($ProbeExit -eq 0) { throw "Tag already published; never overwrite: $Ref" }
        if (($Probe -join "`n") -notmatch 'not found|manifest unknown|MANIFEST_UNKNOWN') {
            throw "Cannot verify registry tag availability: $Ref. Check registry access before retrying."
        }
    }
    # Build every image before publishing any; runtime environment files are never loaded.
    foreach ($Entry in $Images) {
        $Ref = "$Registry/platform-$($Entry.Name):$Tag"
        $BuildArgs = @('buildx', 'build', '--platform', $Platform, '--load', '--label', "org.opencontainers.image.revision=$Commit", '--tag', $Ref, '--file', $Entry.Dockerfile)
        if ($Entry.Service) { $BuildArgs += @('--build-arg', "SERVICE=$($Entry.Service)") }
        & docker @BuildArgs $Entry.Context
        if ($LASTEXITCODE -ne 0) { throw "Build failed: $Ref. Nothing was published." }
    }
    $Published = @()
    foreach ($Entry in $Images) {
        $Ref = "$Registry/platform-$($Entry.Name):$Tag"
        & docker push $Ref
        if ($LASTEXITCODE -ne 0) { throw "Publish failed: $Ref. Partial release must not be deployed." }
        $Digest = & docker buildx imagetools inspect $Ref --format '{{json .Manifest}}'
        if ($LASTEXITCODE -ne 0) { throw "Cannot verify published digest: $Ref" }
        $Manifest = ($Digest -join "`n") | ConvertFrom-Json
        if ($Manifest.digest -notmatch '^sha256:[a-f0-9]{64}$') { throw "Invalid published digest: $Ref" }
        $Published += @{ image = $Ref; digest = $Manifest.digest }
    }
    $Output = Join-Path $Root "output/releases/$Tag"
    New-Item -ItemType Directory -Path $Output -Force | Out-Null
    @{ commit = $Commit; tag = $Tag; platform = $Platform; images = $Published; publishedAt = [DateTime]::UtcNow.ToString('o') } |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $Output 'release.json') -Encoding UTF8
    Write-Output "Published all eight images: $Tag. Deploy with: sh scripts/deploy.sh $Tag"
} finally { Pop-Location }
