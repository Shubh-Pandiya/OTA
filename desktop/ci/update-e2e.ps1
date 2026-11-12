# CI: the desktop update path end to end on a real Windows machine, with the real installers.
#
#  1. Install the previous public release and fill its database with realistic work, part of it
#     still waiting to upload (plus an order without its number). Snapshot every table.
#  2. Install this build by hand over it (what v1.2.1 users do once): the data must be identical
#     and a backup must have been taken on the first start.
#  3. Publish a newer build of this commit on a local feed in GitHub's release format and run the
#     app's own in-app update (--update-e2e): download, verify, last sync, stop, backup, msiexec,
#     restart. The restarted app's self-test must show the new version, "update ok" and identical
#     row counts and checksums, with no duplicates.
#  4. An MSI downgrade is refused, and uninstalling keeps the data folder and its backups.
param(
    [Parameter(Mandatory)] [string] $NewMsi,
    [Parameter(Mandatory)] [string] $NextMsi,
    [Parameter(Mandatory)] [string] $NextVersion,
    [string] $PrevVersion = "1.2.1",
    [string] $PrevSha256 = "2a40e78f65bb8629d6f4126d35f6a6d1d279efe199244f035b2625ab8250b66f"
)
$ErrorActionPreference = "Stop"
$t = Join-Path $env:RUNNER_TEMP "update-e2e"
New-Item -ItemType Directory -Force $t | Out-Null
$data = Join-Path $env:LOCALAPPDATA "OTA Business Data"
$failed = @()

function Fail($msg) { Write-Host "::error::$msg"; $script:failed += $msg }

function Install($msi, $label) {
    $p = Start-Process msiexec.exe -ArgumentList "/i `"$msi`" /qn /norestart /l*v `"$t\install-$label.log`"" -Wait -PassThru
    if ($p.ExitCode -ne 0) { Get-Content "$t\install-$label.log" -Tail 40; throw "msiexec ($label) failed: $($p.ExitCode)" }
}

function AppExe { (Get-ChildItem "$env:LOCALAPPDATA\OTA Business" -Recurse -Filter "OTA Business.exe" | Select-Object -First 1).FullName }

function SelfTest($label) {
    $report = "$t\self-test-$label.txt"
    $run = Start-Process (AppExe) -ArgumentList "--self-test", "`"$report`"" -Wait -PassThru
    Write-Host "---- self-test $label"; Get-Content $report | Where-Object { $_ -notmatch '^table ' } | Out-String | Write-Host
    if ($run.ExitCode -ne 0 -or -not (Select-String -Path $report -Pattern '^OK$' -Quiet)) { throw "Self-test $label failed ($($run.ExitCode))" }
    $report
}

function Tables($file) { @(Get-Content $file | Where-Object { $_ -match '^table ' -and $_ -notmatch '^table kv ' }) }

function SameData($report, $label) {
    $before = Tables "$t\before.txt"
    $diff = Compare-Object $before (Tables $report)
    if ($diff -or $before.Count -lt 5) { $diff | Format-Table | Out-String | Write-Host; Fail "$label`: data differs from before the update" }
    else { Write-Host "$label`: all $($before.Count) tables identical (row counts and checksums)" }
}

# The data folder comes from the installed app's default location (AppPaths.home).
function E2eData([string] $argsLine) {
    ./gradlew.bat e2eData "--args=$argsLine" -PpgPlatform=windows-amd64 --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw "e2eData $argsLine failed" }
}

if (Test-Path $data) { Remove-Item $data -Recurse -Force }
$slash = $t.Replace('\', '/')

# ---- 1. The previous release in daily use.
$prev = "$t\OTA-Business-$PrevVersion.msi"
curl.exe -fsSL --retry 5 --retry-all-errors --connect-timeout 20 -o $prev "https://github.com/Shubh-Pandiya/OTA/releases/download/desktop-v$PrevVersion/OTA-Business-$PrevVersion.msi"
if ((Get-FileHash $prev -Algorithm SHA256).Hash -ine $PrevSha256) { throw "Downloaded $PrevVersion installer does not match its checksum" }
Install $prev "prev"
SelfTest "prev" | Out-Null
E2eData "seed"
E2eData "snapshot $slash/before.txt"
Write-Host "---- data written by $PrevVersion"; Get-Content "$t\before.txt"

# ---- 2. One manual install of this version over it.
Install $NewMsi "new"
$r = SelfTest "manual"
SameData $r "Manual install"
if (-not (Select-String -Path $r -Pattern '^backup .+before-' -Quiet)) { Fail "No backup was taken on the first start of the new version" }

# ---- 3. The in-app update to the next version from a local feed.
$feed = "$t\feed"
New-Item -ItemType Directory -Force $feed | Out-Null
$name = "OTA-Business-$NextVersion.msi"
Copy-Item $NextMsi "$feed\$name"
$file = Get-Item "$feed\$name"
$sha = (Get-FileHash $file -Algorithm SHA256).Hash.ToLower()
"$sha  $name" | Set-Content "$feed\SHA256SUMS.txt" -Encoding ascii
$base = "http://127.0.0.1:8765"
ConvertTo-Json -Depth 5 -InputObject @(
    [ordered]@{
        tag_name = "desktop-v$NextVersion"; draft = $false; prerelease = $false; html_url = "$base/"
        body = "## What's new`n- The CI update test release."
        assets = @(
            [ordered]@{ name = $name; size = $file.Length; digest = "sha256:$sha"; browser_download_url = "$base/$name" },
            [ordered]@{ name = "SHA256SUMS.txt"; size = (Get-Item "$feed\SHA256SUMS.txt").Length; browser_download_url = "$base/SHA256SUMS.txt" }
        )
    }
) | Set-Content "$feed\releases.json" -Encoding utf8
Get-Content "$feed\releases.json"
$server = Start-Process python -ArgumentList "-m", "http.server", "8765", "--bind", "127.0.0.1", "--directory", "`"$feed`"" -PassThru -WindowStyle Hidden
Start-Sleep -Seconds 3
$report = "$t\self-test-after-update.txt"
try {
    $env:OTA_UPDATE_FEED = "$base/releases.json"
    Start-Process (AppExe) -ArgumentList "--update-e2e", "`"$report`"" | Out-Null
    $deadline = (Get-Date).AddMinutes(20)
    while (-not ((Test-Path $report) -and (Select-String -Path $report -Pattern '^(OK|FAILED)' -Quiet)) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 5 }
    Write-Host "---- updater"; Get-Content "$report.updater.txt", "$data\logs\update.log" -ErrorAction SilentlyContinue
    Get-Content "$data\updates\install-update.out.txt", "$data\updates\install-$NextVersion.result" -ErrorAction SilentlyContinue
    if (-not (Test-Path $report)) {
        Get-ChildItem "$data\updates" -ErrorAction SilentlyContinue | Format-Table Name, Length | Out-String | Write-Host
        Get-Content "$data\updates\install-$NextVersion.log" -Tail 60 -ErrorAction SilentlyContinue
        throw "The updated app did not restart within 20 minutes"
    }
} finally {
    Remove-Item Env:OTA_UPDATE_FEED -ErrorAction SilentlyContinue
    Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
}
Start-Sleep -Seconds 5
Write-Host "---- self-test after the in-app update"; Get-Content $report | Where-Object { $_ -notmatch '^table ' }
if (-not (Select-String -Path $report -Pattern '^OK$' -Quiet)) { Fail "The updated app's self-test failed" }
if (-not (Select-String -Path $report -Pattern "^app $([regex]::Escape($NextVersion))$" -Quiet)) { Fail "The app was not updated to $NextVersion" }
if (-not (Select-String -Path $report -Pattern '^update ok' -Quiet)) { Fail "The app did not report a successful update" }
SameData $report "In-app update"
$backups = @(Get-ChildItem "$data\backups" -Filter "ota-backup-*.zip")
$backups | Format-Table Name, Length | Out-String | Write-Host
if ($backups.Count -ne 2) { Fail "Expected 2 backups (manual install + in-app update), found $($backups.Count)" }
E2eData "duplicates"

# ---- 4. Downgrades are refused; uninstalling keeps the data.
$p = Start-Process msiexec.exe -ArgumentList "/i `"$prev`" /qn /norestart" -Wait -PassThru
$after = SelfTest "after-downgrade-attempt"
if (-not (Select-String -Path $after -Pattern "^app $([regex]::Escape($NextVersion))$" -Quiet)) { Fail "Installing $PrevVersion over $NextVersion replaced the app (msiexec $($p.ExitCode))" }
else { Write-Host "Downgrade refused by the installer (msiexec $($p.ExitCode)); $NextVersion is still installed." }
SameData $after "After the downgrade attempt"
Start-Process msiexec.exe -ArgumentList "/x `"$feed\$name`" /qn" -Wait | Out-Null
if (-not (Test-Path "$data\pgdata\PG_VERSION")) { Fail "Uninstall removed the database" }
if (@(Get-ChildItem "$data\backups" -Filter "*.zip").Count -lt 2) { Fail "Uninstall removed the backups" }

if ($failed.Count) { throw "Update test failed:`n$($failed -join "`n")" }
Write-Host "Update test passed: $PrevVersion -> (manual install) this build -> (in-app update) $NextVersion kept every row exactly once."
