# Browser frontend

The application serves a Russian-language translation interface at
`http://localhost:8080/` (or the configured host and port). Start the service using
the existing instructions in [README.md](README.md), then open that address.
No Node.js installation, frontend build, or separate web server is needed.
The Maven build packages the HTML, CSS, and JavaScript with the application.

Sign in with an existing account and its current password. `ADMIN_PASSWORD` sets
the initial password for `admin`, who can use **Создать пользователя** to provision
an account. The new user signs in separately. Usernames and passwords follow the
API constraints, including the password limit of 72 UTF-8 bytes.

While signed in, use **Сменить пароль** in the toolbar. Enter the current
password, then enter the new password twice. A successful change signs you out
in this tab. Sign in again with the new password.

Choose source and target languages from the list or select **Другой язык…** and
enter a name. Use **Определить автоматически** to have the model identify the
source language during translation. The result shows the detected language.

Select a model by name and use **Перевести** or Ctrl+Enter (Command+Enter on
macOS). The result can be copied. The arrow swaps the languages and, when the
result fits the character limit, uses it as the next source text. In automatic
mode, swapping is available after a language is detected.

Enable **Варианты и примеры** to see alternate meanings for ambiguous words,
short expressions, and short standalone sentences. Usage examples appear for words
and short expressions. The browser remembers this setting when the page is closed
and reopened. The copy button copies the main translation.

The model row shows the account's recorded costs in USD. The bottom of the
workspace shows the cost of the current result. Costs use the provider's token
counts and the configured rates. The interface marks unavailable costs separately.
The server validates languages, model limits, quotas, and permissions.

The browser saves the refresh token and username in local storage and restores
the login after reloading or reopening the page. Passwords and access tokens stay
in page memory. Each tab exclusively owns its saved session using Web Locks.
New tabs restore an available saved session or accept a separate login when all
saved sessions are in use. Accounts can have any number of independent sessions.
**Выйти** removes and revokes only the current tab's session.

Persistence requires HTTPS or a loopback origin, Web Locks support, and writable
local storage. When these are unavailable, the footer reports that login lasts
only until the page is closed. Saved sessions belong to the browser profile and
origin. Use a trusted browser profile, and sign out to remove the saved login.
The server session lifetime applies, 30 days by default. Expired or revoked
sessions require sign-in. The saved credential is removed before refreshing so
single-use refresh tokens are never replayed after an uncertain outcome or page
closure during refresh. Failed translation
requests are not retried automatically, except after an explicit authentication
rejection and successful token refresh. **Выйти** calls the existing logout API
and clears the local session; a server failure is reported to the user.

The frontend uses only same-origin API requests and self-hosted assets. No API
key is exposed in browser code. Translation requires the running service and its
configured provider; opening the page and signing in do not invoke the provider.

The browser assets are publicly available. Account creation requires a bearer
token with the `users:create` scope, which the server grants to `admin`.
Use `./mvnw verify` or `.\mvnw.cmd verify` with JDK 21 and Docker for validation;
`-Dtest=FrontendAccessTest test` checks public assets and protected routes without
requiring Docker or a provider.

Run `node --test src/test/js/session-store.test.cjs` to exercise browser session
restoration, parallel tabs, logout isolation, and storage/transport failures.
