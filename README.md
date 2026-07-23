# Workshop Vault

Steam Workshop downloader with a Kotlin/Spring Boot backend and Vue frontend.

## Scope

- Resolves public Workshop entries through Steam's `GetPublishedFileDetails` API.
- Streams publicly exposed `file_url` content through the application with HTTP Range support.
- Identifies content that needs Steam CM/CDN authorization instead of pretending it is downloadable anonymously.
- Provides a Steam OpenID administrator sign-in flow. Access is restricted to configured Steam IDs.

Steam credentials, refresh tokens, and Steam web cookies are never accepted or stored by this application.

## Configuration

Copy `.env.example` values into the process environment before starting the backend:

| Variable | Required | Description |
| --- | --- | --- |
| `APP_BASE_URL` | Yes for Steam sign-in | Public HTTPS origin, such as `https://workshop.example.com` |
| `ADMIN_STEAM_IDS` | Yes for administration | Comma-separated SteamID64 allowlist |
| `SESSION_SECRET` | Yes for production | Random secret used to sign admin sessions |
| `UPSTREAM_HTTP_PROXY` | No | HTTP proxy used only for Steam upstream requests |
| `DOWNLOAD_ROOT` | No | Local directory for cached downloads; defaults to `./data/downloads` |

For local development, use `APP_BASE_URL=http://localhost:8080`. Steam's OpenID provider must be able to reach the callback URL in your deployment environment.

## Run

```bash
cd frontend
npm install
npm run build

cd ../backend
gradle bootRun
```

The backend requires JDK 21 and Gradle 8.12 or newer. Its Gradle toolchain declaration will use JDK 21 when it is available locally.

The frontend development server proxies `/api` to `http://localhost:8080`:

```bash
cd frontend
npm run dev
```

## Baseline

The public download resolution and resumable direct-file behavior are adapted from [WorkshopAndroidDownloader](https://github.com/Apricityx/WorkshopAndroidDownloader), specifically its `PublishedFileResolver` and `DirectWorkshopDownloader` paths. Entries without a public `file_url` require the baseline project's authenticated Steam CM and CDN flow and are intentionally not bypassed here.
