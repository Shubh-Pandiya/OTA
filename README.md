# OTA Business

Android app for **Onkar Textile Agency**: take shirting/suiting orders, share them as PDF,
track bills against orders, and keep a buyer/supplier catalog.

Orders, bills and PDFs are stored on the phone. The **buyer and supplier catalog** can also be
shared between staff phones through [Supabase](https://supabase.com) (optional: without it the
app works exactly the same, the catalog just stays on each phone).

## Build

Requirements: Android Studio (or JDK 21 + Android SDK), minSdk 35 (Android 15+).

```bash
./gradlew assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # JVM unit tests
./gradlew connectedDebugAndroidTest   # device/emulator tests
```

`local.properties` is **not** committed (it holds machine paths and the cloud keys). Android Studio
creates it with `sdk.dir`; add the cloud keys below yourself.

> Note: after pulling the commit that stopped tracking `local.properties`, git removes your old copy.
> Open the project in Android Studio once (it recreates `sdk.dir`) and add the two Supabase lines again.

## Cloud catalog (Supabase) setup

Done once by the owner.

1. **Create the project.** Sign in at [supabase.com](https://supabase.com/dashboard) → **New project**
   → pick a name (e.g. `ota-business`), a strong database password (keep it to yourself; the app
   never needs it) and region **South Asia (Mumbai)** → **Create new project**.
2. **Turn off public sign-ups.** **Authentication → Sign In / Providers** → disable
   **Allow new users to sign up** → **Save**. Keep the **Email** provider enabled.
3. **Create the tables.** **SQL Editor → New query** → paste the whole of
   [`supabase/migrations/20261107000000_catalog.sql`](supabase/migrations/20261107000000_catalog.sql)
   → **Run**. It is safe to run again.
4. **Add staff accounts.** **Authentication → Users → Add user → Create new user** → email + password,
   tick **Auto Confirm User** → **Create user**. Repeat for each staff member. Deleting a user here
   signs that phone out within the hour (when its sign-in token next refreshes).
5. **Copy the keys into `local.properties`.** **Project Settings → Data API** for the Project URL and
   **Project Settings → API Keys** for the *publishable* key (`sb_publishable_…`, or the legacy
   `anon` key):

   ```properties
   SUPABASE_URL=https://<project-ref>.supabase.co
   SUPABASE_PUBLISHABLE_KEY=sb_publishable_...
   ```

   Rebuild the app. **Never** put the `service_role` / secret key or the database password in the
   app or in this repository; if one leaks, rotate it in the dashboard.

Staff then open **Sync Settings** in the app, sign in, and the catalog syncs.

### How catalog sync works

- The phone's local database is always the source for the screens; everything works offline.
- Sync is automatic while signed in:
  - whenever the app comes to the foreground, then every 30 seconds while it stays open (a pull with
    nothing new is two small requests);
  - every 15 minutes in the background via WorkManager (unique periodic work `catalog-sync`, only
    with a network connection, exponential backoff on failure; cancelled on sign-out);
  - a moment after any catalog edit, right after sign-in, and when the phone comes back online.
- **Sync Settings → Sync now** still syncs on demand and shows last sync time, pending changes and
  errors. Manual, automatic and background rounds share one lock, so they never run at the same time.
- Each round pulls rows changed on the server since the last pull (using the server's `updated_at`,
  so phone clocks don't matter for this), then uploads rows changed on the phone.
- Conflicts: the most recent edit wins (`client_updated_at`). The server trigger refuses to replace a
  row with an older edit, so a phone that was offline cannot overwrite newer data.
- Deleting an entry is a soft delete (`deleted = true`) and syncs to all phones. Rows are never
  hard-deleted on the server.
- Security: row-level security allows only signed-in staff to read, add and edit; anonymous requests
  with just the publishable key get nothing. Sign-in tokens are kept in app-private storage and
  excluded from Android backups.
- Known limitation: if two phones add the *same* new name while both are offline, both entries are
  kept (as two catalog rows) and one can be deleted afterwards.

Only the catalog is in the cloud for now; orders and bills stay on the phone.
