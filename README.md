# AI translator backend

Java 21 / Spring Boot 4.1.2 REST API with PostgreSQL, Flyway migrations, JWT
authentication, configurable model aliases, an OpenAI Responses adapter, and
database-backed usage limits. No source text or translated text is stored in the
database. Translation history, signup/login, billing, streaming, automatic language
detection, and automatic provider fallback are outside this MVP.

## Run locally

Requirements: JDK 21+, Docker with Linux containers, and an OIDC provider issuing
RS256 access tokens. Maven Wrapper downloads Maven 3.9.16 on first use.

Set environment variables in the shell that runs the application. For example,
in PowerShell:

```powershell
$env:DB_PASSWORD = 'choose-a-local-development-password'
$env:JWT_ISSUER = 'https://your-idp.example/realms/translator'
$env:JWT_JWK_SET_URI = 'https://your-idp.example/realms/translator/protocol/openid-connect/certs'
$env:JWT_AUDIENCE = 'translator-api'
$env:OPENAI_API_KEY = 'your-server-side-api-key'
docker compose up -d --wait
.\mvnw.cmd spring-boot:run
```

On macOS/Linux use `export NAME=value` and `./mvnw spring-boot:run`. Alternatively,
`./mvnw package` produces `target/translator-0.0.1-SNAPSHOT.jar` which runs with
`java -jar target/translator-0.0.1-SNAPSHOT.jar`.

Compose binds PostgreSQL to loopback only and uses a named volume. `.env.example`
lists settings; copying it to `.env` supplies Compose settings only. Spring Boot
does **not** automatically load `.env` into the Java process. Keep actual secrets
out of source control. No live model API call is made at startup.

## Identity provider

Configure an API audience of `translator-api`, expose the provider's JWKS URL, and
grant callers the `translate:write` scope. Both API endpoints require this scope.
Use an OAuth2/OIDC flow appropriate to your client (for example authorization code
with PKCE for a public client). Obtain an **access token**, not an ID token.

The backend accepts RS256 only and requires a valid signature, exact issuer,
expected audience, nonblank subject, and expiration. Spring also validates `nbf`
when present, with its default clock-skew allowance. Tokens with no expiry are
rejected. Authentication is stateless: use short-lived tokens; immediate session
revocation is not implemented here. Browser token storage, refresh tokens, account
recovery, and MFA belong to the identity provider/client.

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
```

```json
{
  "requestId": "90e1ae60-2ba7-42b1-895c-7f443ef5f3f6",
  "translatedText": "Доброе утро!",
  "sourceLanguage": "en",
  "targetLanguage": "ru",
  "model": "translation-fast"
}
```

Each translation is independent. Both source and target languages are explicit.
Supported tags by default: `en`, `ru`, `kk`, `de`, `fr`, `es`, `it`, `pt`, `zh`,
`ja`, `ko`, `ar`, `hi`, `uk`, `tr`. Matching is exact. Configure the list in
`translator.languages` for other language pairs. The service does not verify that
the source language selected by the caller matches the text.

Model aliases are `translation-fast` and `translation-quality`, mapped to
`gpt-4.1-mini` and `gpt-4.1` by default. Set `OPENAI_FAST_MODEL` and
`OPENAI_QUALITY_MODEL` for models available to your OpenAI project that support
Responses and strict JSON-schema output. These defaults are baselines, not a
claim that they are optimal for every language pair. Evaluate quality and latency
with representative texts before release.

The models endpoint returns an array of `{id, provider, maxInputBytes}` records.
Models can have a `required-scope` in application configuration; those models are
hidden from and denied to users without that extra scope. Clients cannot supply
raw provider model IDs, prompts, API keys, generation settings, or upstream URLs.

Default limits: 10,000 characters and 20,000 UTF-8 bytes of text, 65,536 bytes for
the entire JSON body, 4,096 maximum output tokens, 20 admitted requests/minute,
2 concurrent requests/user, and 200,000 reserved tokens/user/UTC day. Input
expansion beyond the output limit produces an error rather than partial success.

Errors use `{ "code": "...", "message": "..." }`, except framework-generated
401/403 responses (standard Spring Security bearer-token responses).

| Status | Typical code or meaning |
| --- | --- |
| 400 | `INVALID_REQUEST`, `UNKNOWN_MODEL`, `UNSUPPORTED_LANGUAGE` |
| 401 | Missing, expired, or invalid JWT |
| 403 | Missing scope; `MODEL_FORBIDDEN` for model-specific denial |
| 413 | `REQUEST_TOO_LARGE`, `TEXT_TOO_LARGE` |
| 415 | `UNSUPPORTED_MEDIA_TYPE` |
| 422 | `TRANSLATION_REFUSED` |
| 429 | `RATE_LIMIT`, `CONCURRENCY_LIMIT`, `DAILY_QUOTA` |
| 502 | `PROVIDER_ERROR`, `INVALID_PROVIDER_RESPONSE`, `INCOMPLETE_TRANSLATION` |
| 503 | `PROVIDER_BUSY`, `REQUEST_INTERRUPTED` |
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

Prompt `v1` directs the model to translate embedded questions and commands instead
of following them. **A prompt and a JSON schema cannot guarantee translation-only
behavior or correct meaning.** The tests establish request isolation and response
handling, not semantic robustness of a real model. Run language-pair and adversarial
evaluations against each selected model before release. Do not put secrets in
prompts, and render returned text as untrusted text in clients.

`store: false` disables Responses storage for that call; it does not by itself
establish zero data retention. Review provider data-handling settings and your
privacy requirements before sending sensitive documents.

## PostgreSQL usage accounting

Admission locks one account row, checks limits, and inserts a request reservation
in one transaction. This works across multiple application instances. The account
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

Only metadata is persisted: timestamps, hashed account identifier, model and prompt
versions, languages, state, usage, and error code. Logs contain request IDs,
model aliases, timing, usage, and outcomes. Metadata retention/archival is an
operational follow-up; the MVP retains it until an operator applies a retention
policy. Keep any cleanup policy long enough to preserve active daily/minute limits.

## Tests

```powershell
.\mvnw.cmd verify
```

Docker must be available. PostgreSQL integration tests use Testcontainers with
`postgres:17-alpine`, apply the production Flyway migration, and fail rather than
silently skip when Docker is unavailable. They exercise the real HTTP API,
cryptographically signed JWTs/JWKS, permissions, validation, concurrent quota
admission, lease expiry, daily budgets, rate limits, and metadata persistence.

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
