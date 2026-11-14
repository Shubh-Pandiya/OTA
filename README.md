# OTA Business

Android app (plus a [Windows desktop app](#windows-desktop-app-desktop)) for **Onkar Textile Agency**: take shirting/suiting orders, share them as PDF,
track bills against orders, prepare **payment advices** (shared as PDF) against those bills, and keep a
buyer/supplier catalog.

Staff must sign in before they can use the app (the sign-in screen is the first thing a new install
shows). After that everything is stored on the phone first and works offline; a phone only returns
to the sign-in screen after **Sign out** or when the cloud rejects its session (e.g. the account was
removed). The **buyer and supplier catalog**, **orders**, **bills** and **payment advices** are shared between phones through
[Supabase](https://supabase.com). PDFs are never uploaded; each phone regenerates them from the
order data.

## Build

Requirements: Android Studio (or JDK 21 + Android SDK), minSdk 35 (Android 15+).

```bash
./gradlew assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # JVM unit tests
./gradlew connectedDebugAndroidTest   # device/emulator tests
./gradlew assembleRelease      # R8-minified, resource-shrunk APK (~3 MB); sign it before installing
```

The letterhead logo and the agency stamp are vector path data (`res/values/pdf_marks.xml`,
`res/raw/stamp_path.txt`) drawn straight onto the PDF canvas, so each order or advice PDF is about
100 KB. PDFs saved by older versions are re-rendered once, in place, on the first launch after the update.
Release builds trust only system CAs; debug builds also trust user-installed CAs (for office TLS proxies).

A fresh clone builds an app that already connects to the agency's cloud: the project URL and the
public *publishable* key are committed in [`supabase.properties`](supabase.properties). The
publishable key is meant to be public; row-level security lets only signed-in staff read or write.

`local.properties` is **not** committed: it holds `sdk.dir`, which differs per machine, and Android
Studio creates it by itself. To point one machine at a different Supabase project, put
`SUPABASE_URL` / `SUPABASE_PUBLISHABLE_KEY` in `local.properties`; those win over
`supabase.properties`.

## Phone app: updates, lock and encryption

**Two apps, one move (November 2026).** Phones up to 1.1.x run `com.example.otaapplication`, signed
with an Android Studio debug key. From 2.0.0 the app is `com.ota.business`, signed with the OTA
release key. Android can't update an app in place across keys, so the move is a one-time step:

1. The old app updates itself to **1.2.0** (`android-v1.2.0`, still the old key). It asks *Move to the
   new app?*. **Move now** syncs and refuses to continue while anything is still unsynced. It then downloads
   `phone-v2.0.0`, checks its SHA-256 and that it is signed with the release key, and installs it **next to** the old app.
2. It packs the orders, bills, advices, to-dos, cheque/transaction pictures and settings into an
   encrypted move file (AES-256-GCM, key from a 20-character recovery code). The file goes straight to the new
   app, with a safety copy in `Download/OTA Business/OTA-move-<time>.otamove`.
3. The new app restores everything and asks the user to sign in once (sign-in tokens are not copied).
   The old app then only shows *Moved to the new app*, the recovery code, and **Remove this old app**.

If the hand-over is interrupted, open the new app's sign-in screen, choose *Moving from the old app? Restore
from the move file*, pick the `.otamove` file and type the recovery code shown in the old app.

**Updates.** Each app follows only its own GitHub releases: the old app `android-v*`, the new app `phone-v*`
(the desktop's `desktop-v*` releases are ignored). It checks on start and every 6 hours, and *Sync
Settings → App updates → Check* checks at once. *Update now* asks once to allow *Install unknown apps*.
It then syncs (offline, unsynced rows stay queued) and downloads `OTA-Business-<version>.apk`. Before
handing the APK to Android's installer, it checks the size and SHA-256 against `SHA256SUMS.txt`, the same
package, a newer version (no downgrades) and, for the new app, the release certificate
`860a5773…8f55c3f0`. It is an in-place update, so everything on the phone stays.

**App lock.** *Sync Settings → Security → App lock* sets a 4–8 digit PIN (salted PBKDF2), with optional
fingerprint unlock. The app locks on a cold start and after being away for the chosen time (30 s,
1, 5 or 15 min). After 5 wrong PINs the wait grows (30 s, doubling up to 1 hour); *Forgot PIN?* checks the account password online.

**Encryption.** The phone's databases (orders/bills/advices and to-dos) are SQLCipher-encrypted, and
pictures are sealed with AES-GCM, using keys kept in the Android Keystore. Phones upgrading from an
older version are converted once on first start.

**Releasing.**
- *New app:* set `otaAndroidVersion` in `gradle.properties` (versionCode = major×10000 + minor×100 +
  patch), add `app/release-notes/<version>.md`, push tag `phone-v<version>`. Signed with the release key
  from the secrets `OTA_RELEASE_KEYSTORE_BASE64`, `OTA_RELEASE_KEYSTORE_PASSWORD`, `OTA_RELEASE_KEY_ALIAS`
  and `OTA_RELEASE_KEY_PASSWORD`.
- *Old app (only if the move bridge ever needs a fix):* push `android-v<version>`. CI builds with
  `-PotaLegacyBridge=true` and the old key in `ANDROID_KEYSTORE_BASE64`.
- The [Android workflow](.github/workflows/android.yml) runs the unit tests and builds the R8-shrunk APK (about
  8 MB; SQLCipher adds its native library). It refuses an APK signed with the wrong key, then publishes the
  APK, `SHA256SUMS.txt` and the certificate SHA-256.

**The release key is the only way to ship updates.** It is never committed. Keep the offline backup copy
safe: if the key and its passwords are lost, phones can't be updated again without another move.

**Keeping the cloud awake.** Supabase's free tier pauses after 7 idle days.
[keep-supabase-awake](.github/workflows/keep-supabase-awake.yml) makes one tiny read each day (a few seconds of Actions time).

## Cloud (Supabase) setup

Done once by the owner.

1. **Create the project.** Sign in at [supabase.com](https://supabase.com/dashboard) → **New project**
   → pick a name (e.g. `ota-business`), a strong database password (keep it to yourself; the app
   never needs it) and region **South Asia (Mumbai)** → **Create new project**.
2. **Turn off public sign-ups.** **Authentication → Sign In / Providers** → disable
   **Allow new users to sign up** → **Save**. Keep the **Email** provider enabled.
3. **Create the tables.** **SQL Editor → New query** → paste the whole of
   [`supabase/migrations/20261107000000_catalog.sql`](supabase/migrations/20261107000000_catalog.sql)
   → **Run**. Then do the same with
   [`supabase/migrations/20261108000000_orders.sql`](supabase/migrations/20261108000000_orders.sql)
   and then
   [`supabase/migrations/20261109000000_payment_advice.sql`](supabase/migrations/20261109000000_payment_advice.sql).
   All are safe to run again. (Until the latest file has been run, order sync shows
   "run the latest migration" and keeps changes on the phone.)
4. **Add staff accounts.** **Authentication → Users → Add user → Create new user** → email + password,
   tick **Auto Confirm User** → **Create user**. Repeat for each staff member. Deleting a user here
   signs that phone out within the hour (when its sign-in token next refreshes).
   Then give each person their order-number digit (1–9, unique) in the **SQL Editor**. The admins
   who may change cloud retention and archive/purge (desktop app) are fixed by email in
   `ota_is_admin()`: varun@ota.com and shubham@ota.com. `is_admin` is kept in step for display only:

   ```sql
   insert into public.staff (user_id, code, name, is_admin)
   select id, v.code, v.name, v.is_admin from auth.users
   join (values ('varun@example.com', 1, 'Varun', false),
                ('pawan@example.com', 2, 'Pawan', false),
                ('shubham@example.com', 3, 'Shubham', true)) as v(email, code, name, is_admin)
     using (email)
   on conflict (user_id) do update set code = excluded.code, name = excluded.name, is_admin = excluded.is_admin;
   ```
5. **Put the keys in `supabase.properties`** (committed). **Project Settings → Data API** for the Project URL and
   **Project Settings → API Keys** for the *publishable* key (`sb_publishable_…`, or the legacy
   `anon` key):

   ```properties
   SUPABASE_URL=https://<project-ref>.supabase.co
   SUPABASE_PUBLISHABLE_KEY=sb_publishable_...
   ```

   Commit it and rebuild the app. **Never** put the `service_role` / secret key or the database
   password in the app or in this repository; if one leaks, rotate it in the dashboard.

Staff then open the app, sign in on the first screen, and everything syncs.

### How sync works

- The phone's local database is always the source for the screens; everything works offline.
- Sync is automatic while signed in:
  - whenever the app comes to the foreground, then every 30 seconds while it stays open (a pull with
    nothing new is two small requests);
  - every 15 minutes in the background via WorkManager (unique periodic work `catalog-sync`, only
    with a network connection, exponential backoff on failure; cancelled on sign-out);
  - a moment after any edit, right after sign-in, and when the phone comes back online.
- **Sync Settings → Sync now** still syncs on demand and shows last sync time, pending changes and
  errors. Manual, automatic and background rounds share one lock, so they never run at the same time.
- Each round pulls rows changed on the server since the last pull (using the server's `updated_at`,
  so phone clocks don't matter for this), then uploads rows changed on the phone.
- Conflicts: the most recent edit wins (`client_updated_at`). The server trigger refuses to replace a
  row with an older edit, so a phone that was offline cannot overwrite newer data.
- Deleting is permanent. Both apps ask for confirmation, then the row is removed from the phone,
  the cloud, the other phones and the laptops. The cloud keeps only a tiny tombstone (pruned after
  400 days), so a phone that was offline can't bring the deleted row back. A newer edit made
  elsewhere before the delete wins over it. See *Deleting* in `docs/desktop-sync-contract.md`.
- Security: row-level security allows only signed-in staff to read, add and edit; anonymous requests
  with just the publishable key get nothing. Sign-in tokens are kept in app-private storage and
  excluded from Android backups.
- Known limitation: if two phones add the *same* new name while both are offline, both entries are
  kept (as two catalog rows) and one can be deleted afterwards.

### Orders, bills and order numbers

- **New order numbers** come from one agency-wide cloud counter per financial year (e.g. `26270001`,
  see [the sync contract](docs/desktop-sync-contract.md#order-numbers)). A number is never reused,
  even after its order is deleted: the counter only moves forward. Orders saved offline wait as
  "Pending number" until the next sync.
- **Older order numbers** were per person, not per phone: `{staff digit}{YYYYMMDD}{serial of the day}`,
  e.g. Varun (1) → `120261107001`, `120261107002`, … The serial is 3 digits (001–999, it keeps
  growing past 999 if ever needed) and restarts every day.
  The digit is read at sign-in and remembered, so orders can be made offline afterwards. The day's counter is matched with
  the cloud on every sync, and if two phones of the same person used the same number offline, the
  later upload is renumbered and the user is told. Older orders keep their old numbers.
- Reopening an order from history and saving updates that order in place (same number, bills kept,
  new PDF).
- Uploaded: one compact row per order (items as a JSON array), bills and order–bill links (many to
  many). Existing orders upload once after the first sign-in.
- Size: about 1 KB per order including a bill link, so the 500 MB free database holds hundreds of
  thousands of orders; with the default 30-day retention the cloud only ever holds about a month.

### Bills and payment advices

- A **bill** has its number, date, amount, LR (bilty) number and cash discount (% and ₹), and is
  linked to one or more orders. Its **balance** is the amount minus everything settled against it
  (paid + cash discount + goods return + rate difference over all advices), shown as Unpaid /
  Partly paid / Paid. Tap a bill on an order to see its advices and edit its details.
- A **payment advice** (`PA{staff digit}{YYYYMMDD}{001…}`, own daily counter, same rules as order
  numbers) records one buyer paying one supplier: payment date, mode (cheque no / online txn id /
  cash), one line per bill (paid, CD, goods return, rate difference) and the Dr/Cr balance. The app
  suggests the outstanding total for that buyer → supplier as a tap-to-use Dr/Cr hint; what you
  enter is stored. A bill can be paid across several advices.
- Only bills whose linked orders are for that buyer and supplier can be added. Bills whose orders
  are for more than one buyer/supplier pair are still offered but flagged "mixed parties"; bills
  with no linked order are not offered.
- The advice PDF is saved in `Documents/OTA Orders/{year}/{Month}/Payment Advices/` and shared from
  the advice screen; it is never uploaded.

### Admins and the test account

- **Admins** are varun@ota.com and shubham@ota.com. Only they may archive or purge the cloud or
  change retention, and the cloud itself refuses anyone else. On the desktop only an admin sees cloud
  clean-up and the retention settings. **Accounts** is shown to the admins and to the test account.
- **Test account** testuser1@ota.com uses the same cloud, but its rows are tagged `is_test` and hidden
  by RLS. Nobody else sees them in any list, search, total, Insights, Accounts or export, and it sees
  only its own test data. Test orders and advices use their own number series.
  - Signed in as the test user, *Sync Settings* (phone) and *Sync & Cloud* (desktop) show
    **Clear all my test data**. It deletes every test row in the cloud and on that device.
  - The desktop accepts the test login on a laptop that holds real data (desktop 1.7.0). The test data
    sits beside the real data in its own local schema, so while the test user is signed in only test
    rows show, and signing in with a staff login brings the real data back. Switching never deletes or
    mixes anything. The test user gets its own Accounts books, built from test bills only.
  - The phone keeps one kind of data and refuses a test login while it holds real data.

See [docs/desktop-sync-contract.md](docs/desktop-sync-contract.md#test-account) for the details.

### Cloud retention (desktop app)

The cloud keeps orders and payment advices only for `retention_days` (default 30). The desktop app keeps everything: it
pulls all orders, marks the stored ones archived, and then purges archived orders older than the
retention period. Rows that were never archived, or changed after archiving, are never purged.
Phones keep all their own orders; a purge never removes anything from a phone. Buyers and
suppliers are never purged.

Each sync reads the cloud's database size. Above 80 % of the limit (500 MB, the Free plan database
quota; the 1 GB is file storage, which the app does not use) the phone shows a notification and a
banner on Sync Settings asking to archive from the desktop app. If the cloud refuses writes because
it is full, changes stay on the phone and upload automatically once there is space.

See [docs/desktop-sync-contract.md](docs/desktop-sync-contract.md) for the tables, cursors and
archive/purge RPCs the desktop app uses.

## Desktop app (`desktop/`, Windows and Mac)

The laptop is the agency's main store; the cloud only carries recent data between phones and the laptop.

**Install:** download `OTA-Business-<version>.msi` (or `OTA-Business-Setup-<version>.exe`) from the
[latest desktop release](https://github.com/Shubh-Pandiya/OTA/releases?q=desktop-v) and run it. It installs
for the current user (no admin prompt) and needs nothing else: the installer bundles its own Java
runtime and PostgreSQL 17. On first launch the app creates a private database under
`%LOCALAPPDATA%\OTA Business Data\pgdata` (it listens on localhost only, on a free port, and runs
only while the app is open), applies the schema, and asks for a one-time staff sign-in. After that it opens offline.
Uninstalling or upgrading never touches the data folder. The installer is not code-signed, so
Windows SmartScreen may ask for *More info → Run anyway* the first time.

**Install on a Mac (Apple silicon, 1.5.0 and later):** download `OTA-Business-<version>-mac-arm64.dmg`,
open it and drag **OTA Business** to **Applications**. Start it from Applications, not from the disk
image: it can only update itself there. The app is not signed by Apple, so macOS blocks the first
start ("cannot be opened" / "Apple could not verify…"). Choose **Done**, open **System Settings →
Privacy & Security**, scroll to *Security*, click **Open Anyway** next to OTA Business, and confirm with
your password. This is needed only once. The data lives in `~/Library/Application Support/OTA Business Data`
(database, backups and logs), and PDFs go to `~/Documents/OTA Orders`. Intel Macs aren't supported.
On some networks with IPv6 the Mac app 1.5.0–1.6.1 can't download its update ("HTTP connect timed out").
1.7.0 fixes this (IPv4, resumable download with retries), so install the 1.7.0 DMG by hand once.

**Updates (1.3.0 and later):** the app checks the public GitHub Releases (`desktop-v*` tags, no token)
a few seconds after start and every 6 hours, and asks *Update now / Later* with the release notes
(*Later* waits a day; *Sync & Cloud → App version → Check for updates* checks at once). *Update now*:
1. downloads the MSI and verifies its size and SHA-256 (`SHA256SUMS.txt` in the release, and GitHub's asset digest); nothing is changed if it does not match;
2. sends unsynced changes to the cloud; when offline they simply stay in the local database (and upload after the update);
3. stops the local PostgreSQL cleanly and zips the whole `pgdata` folder to `%LOCALAPPDATA%\OTA Business Data\backups` (verified, the last 5 kept);
4. runs the installer and restarts on the new version, which reports the result.

On every start the app also refuses data last opened by a newer version (no downgrades: the installer itself cannot block an older MSI, so the app does), takes a backup
whenever the version changed (if the updater has not just made one), and applies database migrations in
one transaction, so a failed migration leaves the database exactly as it was and says which backup to use.
To put a backup back (with the app closed): `"OTA Business.exe" --restore-backup "<backup .zip>" [--password "<backup password>"]`; the
current database is kept beside it as `pgdata-before-restore-<time>`.

**Automatic backups (Sync & Cloud → Backups):**
- **What and when.** The app makes a full copy of the local database: real data, the test account's data and the Accounts books, which exist only on this laptop.
  - It backs up once a day, and again when you close the app if anything changed and there was no backup in the last 10 minutes.
  - Backups run on a low-priority background thread while you keep working. A backup taken on close shows *Saving a backup before closing…* and waits at most 90 s; *Close now* skips it.
- **Where.** Backups go to `Documents/OTA Backups` by default. *Change folder…* picks a USB drive or a Google Drive / OneDrive folder, which keeps copies off the laptop.
  - Files are named `OTA-Business-backup_<date>_<computer>_<daily|on-exit|manual|before-restore>.zip`.
  - The last 14 are kept (configurable). Rotation deletes only this computer's files with that exact name, so a shared folder can also hold other files and other laptops' backups.
- **Encryption.** Each backup is a standard zip with its data encrypted with AES-256 (WinZip AES).
  - On first use the app generates a backup password (`XXXX-XXXX-XXXX-XXXX-XXXX`). *Show* / *Copy* display it; write it down. *Change…* sets your own.
  - The password is stored only on this laptop, in `security.properties` in the data folder, readable by your user only, and never in the backup.
  - Older passwords are remembered, so older backups restore without asking.
  - A short README and a manifest inside the zip are not encrypted.
- **Recovery without the app.** Open the zip with 7-Zip, WinZip, Keka or The Unarchiver and enter the backup password. Windows' built-in zip viewer can't open it. Inside is a PostgreSQL 17 data folder.
- **Restore…** Pick a backup, then enter its password if it came from another laptop or an older password, then confirm that it **replaces all of this laptop's local data**. The app then:
  1. saves a safety backup of the current data to the backup folder (if that fails, nothing changes);
  2. stops the database;
  3. puts the backup in place, keeping the old folder as `pgdata-before-restore-<time>`;
  4. reopens.
- Pre-update copies in the data folder's `backups` folder stay unencrypted, as before. They sit beside the live, unencrypted database in the same private folder, and keeping them plain keeps the update rollback simple.

**PIN lock (Sync & Cloud → Security, optional, per laptop):** set a 4–8 digit PIN, stored only as a salted PBKDF2 hash.
- The app locks on start (once signed in) and after 5/15/30/60 minutes without keyboard or mouse input, or *Lock now*. Only the screens lock; sync and backups keep running.
- After 5 wrong PINs, entry pauses for 30 s, doubling up to 15 min, even across restarts.
- *Forgot PIN?* checks your OTA Business account password online, then lets you set a new PIN or turn the lock off.
**Coming from 1.2.1 or older:** install 1.3.0 once by hand (download and run the MSI); later versions update themselves.
On a Mac the update downloads the DMG and verifies it the same way. After the app closes, it swaps
`OTA Business.app` in place and reopens it, and the data folder is never touched. The first Mac version
(1.5.0) is installed by hand.

**What it does:**
- A Home screen: today's orders, bills still to receive, outstanding and this month's receipts, dues by age, the oldest dues, recent orders and advices, and quick actions.
- Everything the phone does: orders with units and PDFs, bills (with their own item lines, added from an order or on their own), payment advices with claim / defect deductions and PDFs, the buyer/supplier catalog, search, sync status and *Sync now*. Order numbers come from the shared financial-year counter (`26270001`, …); an order saved offline waits for its number until the next sync. Advice numbers use the signed-in staff digit. Advice images stay on the phone.
- Dashboards: orders over time, quantity per unit, top buyers and suppliers, outstanding bills with ageing buckets, payments over time, adjustments (C.D./G.R./R.D./claims), per-staff activity and cloud storage. Filters: date range, buyer, supplier and staff.
- **Accounts** (admins varun@ota.com and shubham@ota.com; the test account gets separate test books): the agency's own double-entry books.
  - A default chart of accounts you can edit, and a ledger for every catalog buyer and supplier.
  - Journal, payment, receipt and contra vouchers with balanced Dr/Cr lines, and an audit log of every add, edit and delete.
  - Commission is set as a % per supplier and posted automatically from every bill (Dr supplier, Cr Commission income), at most one voucher per bill. It is re-posted when a bill is edited and removed when the bill is deleted.
  - Changing a supplier's % re-posts all of that supplier's bills, because the rate has no effective date.
  - Ledgers with a date range, opening and running balance, and drill-down to the voucher or bill. For a party there is also a bills & advices statement.
  - Reports in tables: day book, trial balance, profit & loss, balance sheet, commission by supplier and month (earned vs received), party ageing, trade outstanding (bills) and cash/bank book. Export them to XLSX or PDF (₹ headers, 2 decimals).
  - **Export…** (Accounts header) opens one dialog for every Accounts table: ledgers, bills & advices statements, vouchers, day book, trial balance, P&L, balance sheet, commission, ageing, trade outstanding, cash/bank book, buyers, suppliers, chart of accounts and audit log.
    - Format: Excel (`.xlsx`), CSV or PDF. Layout: one workbook with a sheet per report, a file per report, or a file per party.
    - Filters: period (this/last FY, quarter, month, all dates or custom), buyers/suppliers/accounts (multi-select), account groups, voucher types (JV, PV, RV, CV, CM), amount range, Dr or Cr side, narration text, bill status (due, partly paid, paid) and hide zero balances.
    - Pick the columns to include, and whether to add opening/closing balances, subtotals and the running balance.
    - Excel files have a frozen header with a filter row, and amounts are real numbers shown as ₹ with 2 decimals and Indian grouping.
    - The test login exports only its test books; real exports never include test data.
  - Accounts never sync and are never archived or purged. They live in the local database, so they are in every update backup.
- Excel export (`.xlsx`): all data (one sheet per table), the dashboard numbers, the current filtered view of any list, and per-area workbooks.
- Cloud upkeep: syncs in the background every 5 minutes (and a few seconds after each edit). On the admin's laptop it also archives every row it has stored and purges archived rows older than the retention period, on connect and every 6 hours. The admin can change the retention days and storage limit under *Sync & Cloud*. Nothing is purged until this laptop holds it.

PDFs are saved to `Documents\OTA Orders`. Logs are in `%LOCALAPPDATA%\OTA Business Data\logs`.

**Build / test** (JDK 21):

```bash
cd desktop
./gradlew test                 # unit + embedded-PostgreSQL tests (no secrets needed)
./gradlew run                  # run the app
./gradlew screenshots          # render every screen to build/screenshots (headless)
```

Windows installers are built by [`.github/workflows/desktop.yml`](.github/workflows/desktop.yml) on
`windows-latest`. It runs the tests, builds the MSI and EXE, installs the MSI silently, and runs
`OTA Business.exe --self-test`, which boots the bundled PostgreSQL and migrates the schema. It then runs the
update test ([`desktop/ci/update-e2e.ps1`](desktop/ci/update-e2e.ps1)): it installs the previous public release
and fills it with realistic and unsynced data. Next it installs this build over it, then updates in-app to a
newer build served from a local feed. Every table's row count and checksum must be unchanged, with no
duplicate ids or numbers and two backups taken. The accounts books seeded after the first install must come through the in-app update unchanged too. An older 1.3.0 app installed over it must refuse to open the data, and uninstalling must keep it. A `macos-latest` job does the same for the Mac. It runs the tests, builds the DMG,
installs it into `~/Applications` and self-tests it twice. Then [`desktop/ci/update-e2e-mac.sh`](desktop/ci/update-e2e-mac.sh) seeds
realistic, unsynced and accounts data and updates in-app to a newer DMG from a local feed. It
checks that every table is identical, with no duplicates and exactly one new backup, and that an older
app put back refuses the data. Pushing a
tag `desktop-v<version>` (version from `desktop/gradle.properties`) publishes the MSI, EXE, Mac DMG and `SHA256SUMS.txt`
as a GitHub Release, using `desktop/release-notes/<version>.md` as the notes (shown in the app's update prompt).
