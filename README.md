# AI translator backend

Java 21 / Spring Boot 4.1.2 REST API with PostgreSQL, Liquibase migrations, JWT
authentication, configurable model aliases, an OpenAI Responses adapter, and
database-backed usage limits. No source text or translated text is stored in the
database. Local accounts and refresh-token hashes are persisted separately.
The service supports source-language detection and records estimated provider
costs for requests with known pricing and usage.

## Client applications

The repository includes a browser interface and native Windows and Android
clients. See [Browser frontend](FRONTEND.md) for the web interface and
[Windows and Android clients](clients/README.md) for builds and installation.

## Run with Docker

Requirements: Docker with Linux containers and Docker Compose v2.23.1 or newer
(including environment-backed secrets and `up --wait`). No host JDK or Maven is
needed: the multi-stage Dockerfile builds with JDK 21 and runs with a JRE as a
non-root user.

One-time setup:

1. Copy `.env.example` to `.env` (`Copy-Item .env.example .env` in PowerShell,
   or `cp .env.example .env` on macOS/Linux).
2. Set `DB_PASSWORD`, `ADMIN_PASSWORD`, and `OPENAI_API_KEY` in `.env`.
3. Generate a persistent PKCS#8 RSA signing key. For example, this Docker command
   prints a new key without installing OpenSSL locally:

   ```sh
   docker run --rm --entrypoint openssl eclipse-temurin:21-jre-jammy genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072
   ```

   Paste the entire PEM, including `BEGIN PRIVATE KEY` and `END PRIVATE KEY`, into
   the single-quoted multiline `JWT_PRIVATE_KEY` value in `.env`. Keep the same key
   across restarts and updates; changing it invalidates existing access tokens.
   An existing unencrypted PKCS#8 RSA key of at least 2048 bits also works.

Start or update the application and PostgreSQL with one command from the repository
root:

```sh
docker compose up -d --build --wait
```

The API is available at `http://localhost:8080`. Compose waits for PostgreSQL,
then application startup and its HTTP health check. The check expects the normal
`401` from `/v1/models` without credentials and does not call OpenAI. Liquibase
applies migrations automatically, then the service creates the `admin` account
using `ADMIN_PASSWORD` if it has not already been provisioned.

Compose passes the database password, administrator password, and provider key at runtime;
the signing key is mounted read-only at `/run/secrets/jwt-private-key` using a
[Compose environment-backed secret](https://docs.docker.com/reference/compose-file/secrets/).
The build context contains only the wrapper, POM, and application sources, keeping
`.env`, signing keys, Git data, and local caches out of image layers. `.env` is
ignored by Git; restrict access to it and back it up securely. Keep secrets in
single quotes so Compose preserves literal `$` and `#` characters. Shell variables
override `.env` values. Avoid sharing `docker compose config` output because it
can contain secrets. This setup targets Docker Compose, not `docker stack deploy`.

Both ports bind to loopback by default. `APP_PORT` and `POSTGRES_PORT` change host
ports; `APP_BIND_ADDRESS` controls the API bind address. The app always connects
to the Compose PostgreSQL service internally. Put a trusted HTTPS reverse proxy
in front of the API for remote access.

Useful commands:

```sh
docker compose ps
docker compose logs -f app
docker compose down
```

`down` retains the `postgres-data` named volume. Keep the same Compose project name
and `.env` to reuse accounts, refresh tokens, usage data, and signing key after an
update. Do not add `--volumes` to `down` unless you intend to delete the database.
Changing `DB_PASSWORD` in `.env` does not change an already initialized PostgreSQL
role's password; update that role separately before switching the configured
password. The container build skips tests; run the validation suite below before
deploying changes.

## Run locally with Maven

Requirements: JDK 21+, Docker with Linux containers, and an RSA private key for
self-issued RS256 access tokens. Maven Wrapper downloads Maven 3.9.16 on first use.

Generate a persistent PKCS#8 RSA key once (OpenSSL example):

```sh
mkdir secrets
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out secrets/jwt-private.pem
```

`secrets/`, `*.pem`, and `*.key` are ignored by Git. Restrict private-key file
permissions to the service account and mount the same key on all application
instances. The service requires an unencrypted PKCS#8 PEM RSA key of at least
2048 bits and fails startup if it cannot load it. Production secrets should be
mounted from your deployment's secret store. A restart must retain the signing
key; replacing it invalidates existing access tokens.

Set environment variables in the shell that runs the application. For example,
in PowerShell:

```powershell
$env:DB_PASSWORD = 'choose-a-local-development-password'
$env:ADMIN_PASSWORD = 'choose-a-unique-administrator-password'
$env:JWT_ISSUER = 'translator'
$env:JWT_PRIVATE_KEY_LOCATION = 'file:./secrets/jwt-private.pem'
$env:JWT_AUDIENCE = 'translator-api'
$env:OPENAI_API_KEY = 'your-server-side-api-key'
docker compose up -d --wait postgres
.\mvnw.cmd spring-boot:run
```

On macOS/Linux use `export NAME=value` and `./mvnw spring-boot:run`. Alternatively,
`./mvnw package` produces `target/translator-0.0.1-SNAPSHOT.jar` which runs with
`java -jar target/translator-0.0.1-SNAPSHOT.jar`.

Compose binds PostgreSQL to loopback only and uses a named volume. You may use the
same `.env` prepared above for Compose, but a Java process started outside Docker
does **not** automatically load `.env`. Set its environment variables in the shell,
including `ADMIN_PASSWORD` and `JWT_PRIVATE_KEY_LOCATION` pointing to the local
PEM file. The local database defaults to `localhost:5432`; override `DB_URL` if you changed
`POSTGRES_PORT`. Use the same `DB_PASSWORD` as the database. No live model API call
is made at startup.

## Authentication

The service owns accounts and issues RS256 JWTs; no external identity provider or
JWKS endpoint is required. External-provider tokens are no longer accepted.

On first startup, the service creates `admin` using `ADMIN_PASSWORD`. This
bootstrap password must be nonblank, contain 12–72 characters, and fit in 72 UTF-8
bytes. The service keeps the stored password on subsequent starts. An existing
account named `admin` from an earlier version causes startup to fail if it was
created through public registration. Rename that account in the database before upgrading.

The administrator can create users from the browser or by sending an access token
with the `users:create` scope to `POST /v1/auth/register`. The service returns
`201 Created` with an empty body. The new user signs in with their own credentials.
Requests without a valid access token receive `401`, and signed-in users without
`users:create` receive `403`.

```sh
curl http://localhost:8080/v1/auth/register \
  -H 'Authorization: Bearer ADMIN_ACCESS_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"a-long-unique-password"}'

curl http://localhost:8080/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"a-long-unique-password"}'
```

Login returns HTTP 200 and
`{accessToken, tokenType, expiresIn, refreshToken, refreshExpiresIn}`. Durations are
in seconds and `tokenType` is `Bearer`. Usernames contain 3–64 ASCII letters,
digits, or underscores, are case insensitive, and are stored lowercase. Passwords
are 12–72 characters and at most 72 UTF-8 bytes; they are hashed using BCrypt
with cost 12. Invalid login credentials return the same generic 401 response for
an unknown account and an incorrect password. Duplicate usernames return 409.
Issued tokens receive `translate:write`. Login and refresh for `admin` also grant
`users:create`. Clients cannot request scopes. Additional model-specific scopes
still deny access unless a trusted token grants them.

Access tokens expire after 15 minutes by default (`JWT_ACCESS_TOKEN_TTL`, allowed
1 minute–1 hour). Verification uses the local public key and requires RS256,
exact issuer, configured audience, nonblank subject, and expiration. Expiration
and `nbf` are checked without clock skew; synchronize instance clocks. The JWT
subject is the account's stable UUID, so login/refresh retain the same quota owner.
Changing `JWT_ISSUER` also changes quota identity; keep it stable.

Refresh tokens are opaque 256-bit secrets; only SHA-256 hashes are persisted.
They are single use and rotate on every successful refresh:

```sh
curl http://localhost:8080/v1/auth/refresh \
  -H 'Content-Type: application/json' \
  -d '{"refreshToken":"YOUR_CURRENT_REFRESH_TOKEN"}'

curl http://localhost:8080/v1/auth/logout \
  -H 'Content-Type: application/json' \
  -d '{"refreshToken":"YOUR_CURRENT_REFRESH_TOKEN"}'
```

Refresh and logout authenticate using the refresh token in the JSON body; send
these requests without an Authorization header, including after an access token
expires. Refresh returns a new token pair. Replace both client tokens atomically
and serialize refresh requests: reusing a consumed token (including a concurrent
refresh retry) revokes that entire login session's refresh-token family and
returns 401. Other login sessions remain active. Rotation does not extend the
family's absolute lifetime: 30 days by default (`JWT_REFRESH_TOKEN_TTL`, allowed
1 hour–90 days and longer than the access TTL). Expired/revoked/unknown refresh
tokens return 401. Refresh tokens cannot be used as API bearer tokens.

Logout returns 204 and revokes the entire family, including when given an older
consumed token. Unknown or already revoked well-formed tokens also return 204.
Already issued access JWTs remain valid until expiration after logout or replay
revocation. Token-bearing responses use `Cache-Control: no-store`. Keep tokens
out of URLs and logs, and store refresh tokens securely on the client. Cookies,
password reset/recovery, email verification, MFA, and immediate access-token
revocation are not implemented.

Any signed-in local account, including `admin`, can change its own password in
the browser or through `POST /v1/auth/change-password`.

```sh
curl http://localhost:8080/v1/auth/change-password \
  -H 'Authorization: Bearer ACCESS_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{"currentPassword":"a-long-unique-password","newPassword":"another-long-unique-password"}'
```

The endpoint returns `204 No Content` and revokes all refresh sessions for the
account. Sign in again with the new password. Already issued access JWTs remain
valid until they expire. An incorrect current password returns
`403 INVALID_CURRENT_PASSWORD`. The replacement must differ from the current
password, or the service returns `400 PASSWORD_UNCHANGED`. Password-change attempts
use a separate per-account rate limit. The administrator's updated password persists
across restarts.

Authentication uses separate PostgreSQL-backed buckets, each allowing 30
attempts/minute (`AUTH_REQUESTS_PER_MINUTE`). Existing-account login attempts are
keyed by the account's stable UUID, independent of the client address and username
case. Unknown usernames share a separate bucket per direct client address, so
arbitrary names do not create arbitrary database buckets. Successful refresh
admission is keyed by the verified session
family, while invalid refresh probes use a separate address bucket. Replay
revocation is checked before session throttling. Logout remains available for
revocation even when other authentication buckets are exhausted.

Excess requests return `429 AUTH_RATE_LIMIT`. Unrelated failed logins or invalid
refreshes cannot consume another account's login/session allowance, including
behind a proxy or NAT. Forwarded headers are not trusted. Unknown-name failures
still group by a proxy's address; configure trusted per-client ingress
throttling as an additional deployment defense. Attempts against the same known
account share its login allowance across addresses; refresh/logout allow an
existing client to retain or terminate its session during a login attack.
The initial auth implementation retains account/family/token rows for replay
detection; an operational retention policy may remove expired families and their
tokens, but must retain unexpired families and consumed members until expiry.

Terminate HTTPS at a trusted reverse proxy in production, configure ingress body
and slow-client timeouts, and keep the application and database off the public
network. The API uses bearer tokens in the Authorization header, not cookies.
CORS is not enabled; serve a browser client from the same origin or explicitly
configure permitted origins for your deployment.

## API

The full contract is in [openapi.yaml](openapi.yaml). Example using curl:

```sh
curl http://localhost:8080/v1/models \
  -H "Authorization: Bearer $ACCESS_TOKEN"

curl http://localhost:8080/v1/translations \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"text":"Good morning!","sourceLanguage":"en","targetLanguage":"ru","model":"translation-fast"}'

curl http://localhost:8080/v1/translation-cost \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

```json
{
  "requestId": "90e1ae60-2ba7-42b1-895c-7f443ef5f3f6",
  "translatedText": "Доброе утро!",
  "sourceLanguage": "en",
  "targetLanguage": "ru",
  "model": "translation-fast",
  "costUsd": 0.000072
}
```

Each translation is independent. Use a language code or a name such as
`Нидерландский` for either language. To detect the source language, pass
`"detectSourceLanguage": true` and omit `sourceLanguage` from the request. The
response's `sourceLanguage` then contains the model's detected code or name.
The value `und` means the model could not identify the language.

Pass `"includeUsage": true` to request distinct translation alternatives for
ambiguous words, short expressions, and short standalone sentences. Words and
short expressions also get usage examples with `sourceText` and `translatedText`.
Nonempty `alternatives` and `examples` arrays are returned separately from the main
`translatedText`. Longer text returns only the main translation. The option
defaults to `false`.

Model aliases are `translation-fast` and `translation-quality`, mapped to
`gpt-4.1-mini` and `gpt-4.1` by default. Set `OPENAI_FAST_MODEL` and
`OPENAI_QUALITY_MODEL` for models available to your OpenAI project that support
Responses and strict JSON-schema output. These defaults are baselines, not a
claim that they are optimal for every language pair. Evaluate quality and latency
with representative texts before release.

The models endpoint returns an array of `{id, name, provider, maxInputBytes}` records.
Models can have a `required-scope` in application configuration; those models are
hidden from and denied to users without that extra scope. Use `name` for display
and send `id` as `model` in translation requests.

The service stores `costUsd` using the response's input, cached input, and output
token counts and the configured USD rates per million tokens. Configure rates
under `translator.pricing.models`, keyed by exact provider model name. Rates for
the default models are included in `application.yaml`. `costUsd` is `null` if
rates or token counts are unavailable. `/v1/translation-cost` returns the caller's
sum of retained known costs, including failed translations with reported usage,
and a count of terminal requests with unknown costs. That count includes older
requests without a cost record. Costs are estimates based on configured rates and
provider-reported usage.

Default limits: 10,000 characters and 20,000 UTF-8 bytes of text, 65,536 bytes for
the entire JSON body, 4,096 maximum output tokens, 20 admitted requests/minute,
2 concurrent requests/user, and 200,000 reserved tokens/user/UTC day. Input
expansion beyond the output limit produces an error rather than partial success.

Across all accounts and application instances, a shared database admission lock
also enforces 8 concurrent provider requests (`GLOBAL_CONCURRENT_REQUESTS`) and
2,000,000 tokens/UTC day (`GLOBAL_DAILY_TOKENS`). Account rotation cannot reset
these shared ceilings. Set both for the deployment's actual provider budget;
token limits are conservative usage bounds, not currency-denominated billing.

Request bodies use Servlet nonblocking reads, hold at most 16 admission slots per
instance (`CONCURRENT_REQUEST_BODIES`), and have a total 5-second upload deadline
(`REQUEST_BODY_TIMEOUT`, 100ms–30s, subject to the container's timeout-check
granularity). The deadline does not extend when another byte arrives and ends
before provider processing. Slots remain held through request completion to bound
queued body memory. Exhausted slots return `503 UPLOAD_CAPACITY`; expired uploads
return `408 REQUEST_TIMEOUT`. Protected translation routes authenticate before
reading a body. Declared oversized bodies are rejected immediately, and chunked
bodies are limited while streaming. Tomcat body swallowing is disabled so rejected
incomplete uploads do not tie up workers; rejected requests close the connection.

Errors use `{ "code": "...", "message": "..." }`, except framework-generated
401/403 responses (standard Spring Security bearer-token responses).

| Status | Typical code or meaning |
| --- | --- |
| 400 | `INVALID_REQUEST`, `UNKNOWN_MODEL`, `UNSUPPORTED_LANGUAGE` |
| 401 | Missing, expired, or invalid JWT |
| 403 | Missing scope; `MODEL_FORBIDDEN` for model-specific denial |
| 408 | `REQUEST_TIMEOUT` for an incomplete/slow request body |
| 409 | `USERNAME_UNAVAILABLE` when a username is taken or reserved |
| 413 | `REQUEST_TOO_LARGE`, `TEXT_TOO_LARGE` |
| 415 | `UNSUPPORTED_MEDIA_TYPE` |
| 422 | `TRANSLATION_REFUSED` |
| 429 | `RATE_LIMIT`, `CONCURRENCY_LIMIT`, `DAILY_QUOTA`, `GLOBAL_CONCURRENCY_LIMIT`, `GLOBAL_DAILY_QUOTA`, `AUTH_RATE_LIMIT` |
| 502 | `PROVIDER_ERROR`, `INVALID_PROVIDER_RESPONSE`, `INCOMPLETE_TRANSLATION` |
| 503 | `PROVIDER_BUSY`, `REQUEST_INTERRUPTED`, `UPLOAD_CAPACITY` |
| 504 | `PROVIDER_TIMEOUT` |

## Provider and prompt policy

`TranslationProvider` is the provider interface. `ModelRegistry` maps approved
aliases to provider configuration; `TranslationService` validates, reserves quota,
calls the adapter, and records metadata. Add a provider by implementing the port
and expanding configuration validation for the new provider/capabilities.

The OpenAI adapter sends trusted instructions separately from JSON-encoded source
text, uses strict structured output, sends `store: false`, and has no tools,
conversation state, or automatic retries. Redirects are disabled. The configured
deadline covers receipt of the entire response, which is also size limited.
Refusals, incomplete generations, malformed output, duplicate JSON properties,
and extra output fields are rejected. API responses do not expose upstream error
bodies, credentials, or provider exceptions.

Prompts `v2` (default) and `v3` (with usage) direct the model to translate embedded
questions and commands instead of following them. **A prompt and a JSON schema cannot guarantee translation-only
behavior or correct meaning.** The tests establish request isolation and response
handling, not semantic robustness of a real model. Run language-pair and adversarial
evaluations against each selected model before release. Do not put secrets in
prompts, and render returned text as untrusted text in clients.

`store: false` disables Responses storage for that call; it does not by itself
establish zero data retention. Review provider data-handling settings and your
privacy requirements before sending sensitive documents.

## PostgreSQL usage accounting

Admission locks the deployment-wide quota row, then the account row, checks both
sets of limits, and inserts a request reservation in one transaction. This works
across multiple application instances sharing the database. The account
key is SHA-256 of trusted issuer plus subject; no user-provided account ID is used.
Rejected requests do not invoke the provider or create usage rows.

The token reservation conservatively includes source UTF-8 bytes (with an escape
allowance), trusted instructions/schema overhead, and the maximum output budget.
On success, reported input/output usage replaces the reservation. If usage is
missing or a request fails, retain the reservation because the provider may have
charged for the attempt. Consequently, this quota is a conservative service
budget, not a bill or exact monetary spending limit. Configure provider-side
spending controls as well. Model pricing and user billing are not implemented.

Completion retains the admission timestamp so work spanning midnight is charged
to the day it started. Expired leases release concurrency slots after a crashed
process; they retain the charged reservation. Lease duration must exceed the
provider deadline by at least 10 seconds. Defaults are a 30-second deadline and
a 60-second lease. Minute rate limits count admitted calls, including failures.

Only translation metadata is persisted for translation calls: timestamps, hashed account identifier, model and prompt
versions, languages, state, usage, estimated cost, and error code. Logs contain request IDs,
model aliases, timing, usage, and outcomes. Metadata retention/archival is an
operational follow-up; the MVP retains it until an operator applies a retention
policy. Keep any cleanup policy long enough to preserve active daily/minute limits.

## Database migrations

Liquibase runs automatically on application startup using
`src/main/resources/db/changelog/db.changelog-master.xml`. The initial changeset
preserves the PostgreSQL tables, constraints, and indexes from the original Flyway
V1 migration. Add future changesets to new changelog files and include them in the
master; do not edit already applied changesets.

An empty database needs no preparation. An existing database initialized by Flyway
must be baselined once before starting this version. Stop application instances,
back up the database, and verify that Flyway V1 completed successfully and its
schema matches `changes/001-translation-metadata.xml`, including both indexes and
all constraints. Do not baseline an empty, partial, or different schema.

Using a Liquibase CLI version compatible with the application's managed
`liquibase-core` version and the PostgreSQL JDBC driver on its classpath, set
`LIQUIBASE_COMMAND_URL`, `LIQUIBASE_COMMAND_USERNAME`, and
`LIQUIBASE_COMMAND_PASSWORD` for that existing database. From the repository root,
run the baseline against **only the initial changelog**, so future changesets are
not accidentally marked as applied:

```powershell
liquibase --changelog-file=src/main/resources/db/changelog/changes/001-translation-metadata.xml changelog-sync
```

The initial changelog has a fixed logical path, so CLI baselining and application
startup identify the same changeset. `changelog-sync` records it in Liquibase's
`databasechangelog` table without executing the schema-creation SQL. Retain
`flyway_schema_history` for audit; the application no longer runs Flyway. Start
the application normally after the baseline. These steps are an operator action;
the application does not automatically adopt existing tables.

## Tests

```powershell
.\mvnw.cmd verify
```

Docker must be available. PostgreSQL integration tests use Testcontainers with
`postgres:17-alpine`, apply the production Liquibase changelog, and fail rather than
silently skip when Docker is unavailable. They exercise the real HTTP API,
local RSA signing and verification, registration/login, hashed credentials,
refresh rotation/replay/concurrency/expiry/logout, auth throttling, permissions,
validation, concurrent quota
admission across accounts, global daily budgets, lease expiry, rate limits, and
metadata persistence. Security regressions also use a two-worker HTTP server to
exercise incomplete/trickled uploads, bounded body admission, disconnect cleanup,
chunked/declared oversized bodies, and successful HTTP connection reuse. They
cover administrator-only account creation, login/session throttle isolation behind one client
address, and refresh replay revocation even when session admission is exhausted.

If your shell inherits `MAVEN_ARGS` that redirects dependency downloads to an
unavailable corporate repository, clear it for the current shell with
`$env:MAVEN_ARGS = ''` before invoking the wrapper. Set `JAVA_HOME` to your
installed JDK 21 if Maven selects a different JDK than `java` on `PATH`. These
are process-local overrides; no global Maven settings need to change. This
workspace was verified with those overrides and a project-local Maven cache:

```powershell
$env:MAVEN_ARGS = ''
# Set JAVA_HOME to your installed JDK 21 directory if needed.
$env:MAVEN_USER_HOME = Join-Path (Get-Location) '.m2'
.\mvnw.cmd -B -ntp "-Dmaven.repo.local=$env:MAVEN_USER_HOME" verify
```

Adapter tests use a local HTTP stand-in for OpenAI, including timeout, malformed
JSON, refusal, truncation, HTTP errors, oversized output, and outbound prompt/schema
checks. No real OpenAI key or paid API call is needed. To run only those adapter
tests without Docker: `./mvnw -Dtest=OpenAiProviderTest test`.

Relevant upstream documentation: [OpenAI text generation](https://developers.openai.com/api/docs/guides/text),
[structured output](https://developers.openai.com/api/docs/guides/structured-outputs),
and [OWASP prompt-injection prevention](https://cheatsheetseries.owasp.org/cheatsheets/LLM_Prompt_Injection_Prevention_Cheat_Sheet.html).
