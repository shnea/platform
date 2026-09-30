# Run release.ps1 against fake Git/Docker commands; no registry or engine access.
$ErrorActionPreference = 'Stop'
$Source = Join-Path $PSScriptRoot 'release.ps1'
$Root = Join-Path (Split-Path $PSScriptRoot -Parent) ('output/release-check-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path "$Root/scripts" -Force | Out-Null
Copy-Item -LiteralPath $Source -Destination "$Root/scripts/release.ps1"
$global:ReleaseCalls = [Collections.Generic.List[string]]::new()
$global:ReleaseCase = ''
function git {
    $global:LASTEXITCODE = 0
    if ($args[0] -eq 'rev-parse') { return '012345abcdef0000000000000000000000000000' }
    if ($global:ReleaseCase -eq 'dirty') { return ' M source.txt' }
}
function docker {
    $call = $args -join ' '
    $global:ReleaseCalls.Add($call)
    $global:LASTEXITCODE = 0
    if ($call -like 'buildx imagetools inspect*') {
        if ($call -like '*--format*') { return ('{"digest":"sha256:' + ('a' * 64) + '"}') }
        if ($global:ReleaseCase -eq 'existing') { return 'already published' }
        $global:LASTEXITCODE = 1
        if ($global:ReleaseCase -eq 'auth') { Write-Error 'unauthorized' -ErrorAction Continue }
        else { Write-Error 'manifest unknown' -ErrorAction Continue }
    } elseif ($call -like 'buildx build*' -and $global:ReleaseCase -eq 'build') {
        $global:LASTEXITCODE = 1
    } elseif ($call -like 'push*' -and $global:ReleaseCase -eq 'push') {
        $global:LASTEXITCODE = 1
    }
}
try {
    foreach ($scenario in @('dirty', 'existing', 'auth', 'build', 'push', 'success')) {
        $global:ReleaseCase = $scenario
        $global:ReleaseCalls.Clear()
        $failed = $false
        try { & "$Root/scripts/release.ps1" -Registry 'example.invalid' | Out-Null }
        catch { $failed = $true }
        if ($failed -ne ($scenario -ne 'success')) { throw "Unexpected result: $scenario" }
        $builds = @($global:ReleaseCalls | Where-Object { $_ -like 'buildx build*' })
        $pushes = @($global:ReleaseCalls | Where-Object { $_ -like 'push*' })
        if ($scenario -in @('dirty', 'existing', 'auth') -and ($builds.Count -or $pushes.Count)) {
            throw "Registry/working-tree guard failed: $scenario"
        }
        if ($scenario -eq 'build' -and $pushes.Count) { throw 'Published after failed build' }
        if ($scenario -in @('push', 'success') -and $builds.Count -ne 8) { throw 'Publish before all eight builds' }
        if ($scenario -eq 'success') {
            if ($pushes.Count -ne 8) { throw 'Missing image pushes' }
            $record = Get-Content -Raw -LiteralPath "$Root/output/releases/012345abcdef/release.json" | ConvertFrom-Json
            if ($record.images.Count -ne 8 -or $record.commit.Length -ne 40) { throw 'Incomplete release record' }
            foreach ($build in $builds) {
                if ($build -notlike '*example.invalid/platform-*:012345abcdef*' -or $build -notlike '*org.opencontainers.image.revision=*') { throw 'Source identity missing' }
            }
        }
    }
    Write-Output 'PASS release guards, build-before-push ordering, eight SHA images, revision labels and digest record'
} finally {
    Remove-Item Function:git, Function:docker
    Remove-Variable ReleaseCalls, ReleaseCase -Scope Global
}
