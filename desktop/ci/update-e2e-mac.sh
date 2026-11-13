#!/bin/bash
# CI: the desktop update path end to end on a real Mac, with the real DMGs (mirrors update-e2e.ps1).
#
#  1. Install this build's DMG into ~/Applications the way a user drags it, start it once and fill
#     its database with realistic work (part still waiting to upload, an order without its number,
#     accounts). Snapshot every table.
#  2. Publish a newer build of this commit on a local feed in GitHub's release format and run the
#     app's own in-app update (--update-e2e): download, verify, last sync, stop, backup, mount the
#     DMG, swap the bundle, restart. The restarted app's self-test must show the new version,
#     "update ok" and identical row counts and checksums, with no duplicates and one new backup.
#  3. The older app put back refuses data last opened by the newer one (start-up guard); putting the
#     newer one back finds every row intact. Deleting the app keeps the data folder and its backups.
#
# usage: update-e2e-mac.sh <this build's .dmg> <next build's .dmg> <next version>
set -euo pipefail
NEW_DMG=$1 NEXT_DMG=$2 NEXT_VERSION=$3
t="${RUNNER_TEMP:-/tmp}/update-e2e"
mkdir -p "$t"
data="$HOME/Library/Application Support/OTA Business Data"
apps="$HOME/Applications"
app="$apps/OTA Business.app"
bin="$app/Contents/MacOS/OTA Business"
failed=()

fail() { echo "::error::$1"; failed+=("$1"); }

# Copies the app out of a DMG like a user dragging it to Applications.
install_dmg() {
    local mnt; mnt=$(mktemp -d /tmp/ota-dmg.XXXXXX)
    hdiutil attach -nobrowse -readonly -noautoopen -mountpoint "$mnt" "$1" >/dev/null
    mkdir -p "$apps"; rm -rf "$app"
    ditto "$mnt/OTA Business.app" "$app"
    hdiutil detach "$mnt" -quiet || hdiutil detach "$mnt" -force -quiet
    echo "Installed $(basename "$1") at $app"
}

self_test() {
    local report="$t/self-test-$1.txt" code=0
    "$bin" --self-test "$report" || code=$?
    echo "---- self-test $1"; grep -v '^table ' "$report" || true
    if [ "$code" -ne 0 ] || ! grep -qx OK "$report"; then echo "Self-test $1 failed ($code)"; exit 1; fi
}

# kv holds timestamps and schema_version gains a row per new migration; every other table must match.
tables() { grep '^table ' "$1" | grep -Ev '^table (kv|schema_version) ' | sort; }

# Compares the tables in the baseline; a newer version may add tables of its own.
same_data() {
    local report=$1 label=$2 baseline=${3:-$t/installed.txt}
    tables "$baseline" > "$t/a.txt"
    tables "$report" | while read -r _ name rest; do
        grep -q "^table $name " "$t/a.txt" && echo "table $name $rest"
    done > "$t/b.txt" || true
    if ! diff "$t/a.txt" "$t/b.txt" || [ "$(wc -l < "$t/a.txt")" -lt 5 ]; then fail "$label: data differs from before the update"
    else echo "$label: all $(wc -l < "$t/a.txt" | tr -d ' ') tables identical (row counts and checksums)"; fi
}

e2e_data() { ./gradlew e2eData "--args=$*" -PpgPlatform=darwin-arm64v8 --console=plain -q; }

backups() { { find "$data/backups" -name 'ota-backup-*.zip' 2>/dev/null || true; } | wc -l | tr -d ' '; }

rm -rf "$data"

# ---- 1. This build in daily use.
install_dmg "$NEW_DMG"
cp -R "$app" "$t/older.app"
self_test first
e2e_data seed
e2e_data seed-accounts
e2e_data snapshot "$t/installed.txt"
echo "---- data before the update"; cat "$t/installed.txt"
before_backups=$(backups)

# ---- 2. The in-app update to the next version from a local feed.
feed="$t/feed"; mkdir -p "$feed"
name="OTA-Business-$NEXT_VERSION-mac-arm64.dmg"
cp "$NEXT_DMG" "$feed/$name"
sha=$(shasum -a 256 "$feed/$name" | cut -d' ' -f1)
echo "$sha  $name" > "$feed/SHA256SUMS.txt"
base="http://127.0.0.1:8765"
python3 - "$feed" "$name" "$sha" "$NEXT_VERSION" "$base" <<'PY'
import json, os, sys
feed, name, sha, version, base = sys.argv[1:]
size = lambda f: os.path.getsize(os.path.join(feed, f))
json.dump([{
    "tag_name": f"desktop-v{version}", "draft": False, "prerelease": False, "html_url": f"{base}/",
    "body": "## What's new\n- The CI update test release.",
    "assets": [
        # A Windows installer in the same release: the Mac must pick its own DMG.
        {"name": f"OTA-Business-{version}.msi", "size": 1, "browser_download_url": f"{base}/missing.msi"},
        {"name": name, "size": size(name), "digest": f"sha256:{sha}", "browser_download_url": f"{base}/{name}"},
        {"name": "SHA256SUMS.txt", "size": size("SHA256SUMS.txt"), "browser_download_url": f"{base}/SHA256SUMS.txt"},
    ],
}], open(os.path.join(feed, "releases.json"), "w"), indent=2)
PY
cat "$feed/releases.json"
python3 -m http.server 8765 --bind 127.0.0.1 --directory "$feed" >"$t/http.log" 2>&1 &
server=$!
trap 'kill $server 2>/dev/null || true' EXIT
sleep 3
report="$t/self-test-after-update.txt"
OTA_UPDATE_FEED="$base/releases.json" "$bin" --update-e2e "$report" || echo "updater exited with $?"
deadline=$((SECONDS + 1200))
until { [ -f "$report" ] && grep -Eq '^(OK|FAILED)' "$report"; } || [ $SECONDS -ge $deadline ]; do sleep 5; done
echo "---- updater"; cat "$report.updater.txt" "$data/logs/update.log" 2>/dev/null || true
cat "$data/updates/install-$NEXT_VERSION.log" "$data/updates/install-update.out.txt" "$data/updates/"*.result 2>/dev/null || true
if [ ! -f "$report" ]; then ls -la "$data/updates" || true; echo "The updated app did not restart within 20 minutes"; exit 1; fi
kill $server 2>/dev/null || true
sleep 5
echo "---- self-test after the in-app update"; grep -v '^table ' "$report"
grep -qx OK "$report" || fail "The updated app's self-test failed"
grep -qx "app $NEXT_VERSION" "$report" || fail "The app was not updated to $NEXT_VERSION"
grep -q '^update ok' "$report" || fail "The app did not report a successful update"
[ ! -e "$app.old" ] && [ ! -e "$app.new" ] || fail "The update left a spare app bundle behind"
same_data "$report" "In-app update"
after_backups=$(backups)
ls -la "$data/backups" || true
[ "$after_backups" -eq $((before_backups + 1)) ] || fail "Expected one new backup from the update, found $before_backups -> $after_backups"
e2e_data duplicates

# ---- 3. Downgrades: the start-up guard refuses data last opened by a newer version.
cp -R "$app" "$t/newer.app"
rm -rf "$app"; ditto "$t/older.app" "$app"
code=0; "$bin" --self-test "$t/self-test-downgrade.txt" || code=$?
echo "---- older build started on $NEXT_VERSION data (exit $code)"; head -8 "$t/self-test-downgrade.txt" || true
if [ "$code" -eq 0 ] || ! grep -q 'newer than this version' "$t/self-test-downgrade.txt"; then fail "An older app opened data last used by $NEXT_VERSION"
else echo "Downgrade refused: the older app did not open the data."; fi
rm -rf "$app"; ditto "$t/newer.app" "$app"
self_test reinstalled
grep -qx "app $NEXT_VERSION" "$t/self-test-reinstalled.txt" || fail "Putting $NEXT_VERSION back did not bring it back"
same_data "$t/self-test-reinstalled.txt" "After the downgrade attempt"
[ "$(backups)" -eq "$after_backups" ] || fail "A refused downgrade should not add backups"
rm -rf "$app"
[ -f "$data/pgdata/PG_VERSION" ] || fail "Removing the app removed the database"
[ "$(backups)" -ge 1 ] || fail "Removing the app removed the backups"

if [ ${#failed[@]} -gt 0 ]; then printf 'Update test failed:\n'; printf '  %s\n' "${failed[@]}"; exit 1; fi
echo "Update test passed: this build -> (in-app update) $NEXT_VERSION kept every row exactly once."
