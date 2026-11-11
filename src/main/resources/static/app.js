"use strict";

(() => {
    const element = (id) => document.getElementById(id);
    const ui = {
        authPanel: element("auth-panel"), authForm: element("auth-form"),
        authSubmit: element("auth-submit"),
        authMessage: element("auth-message"), username: element("username"),
        password: element("password"), logout: element("logout"),
        createUserPanel: element("create-user-panel"), createUserForm: element("create-user-form"),
        createUserSubmit: element("create-user-submit"), createUserMessage: element("create-user-message"),
        newUsername: element("new-username"), newPassword: element("new-password"),
        changePassword: element("change-password"), changePasswordPanel: element("change-password-panel"),
        changePasswordForm: element("change-password-form"), changePasswordSubmit: element("change-password-submit"),
        changePasswordMessage: element("change-password-message"), currentPassword: element("current-password"),
        replacementPassword: element("replacement-password"), confirmPassword: element("confirm-password"),
        model: element("model"), reloadModels: element("reload-models"),
        totalCost: element("total-cost"), requestCost: element("request-cost"),
        sourceLanguage: element("source-language"), targetLanguage: element("target-language"),
        manualSource: element("manual-source"), detectSource: element("detect-source-language"),
        customSource: element("custom-source-language"), customTarget: element("custom-target-language"),
        detectedLanguage: element("detected-language"),
        source: element("source-text"), result: element("result-text"),
        includeUsage: element("include-usage"), details: element("translation-details"),
        alternatives: element("translation-alternatives"), examples: element("usage-examples"),
        sourceCount: element("source-count"), inputLimit: element("input-limit"),
        resultStatus: element("result-status"), translate: element("translate"),
        swap: element("swap"), clear: element("clear"), copy: element("copy"),
        message: element("translation-message")
    };
    const languages = [
        ["en", "Английский"], ["ru", "Русский"], ["kk", "Казахский"], ["de", "Немецкий"],
        ["fr", "Французский"], ["es", "Испанский"], ["it", "Итальянский"], ["pt", "Португальский"],
        ["zh", "Китайский"], ["ja", "Японский"], ["ko", "Корейский"], ["ar", "Арабский"],
        ["hi", "Хинди"], ["uk", "Украинский"], ["tr", "Турецкий"]
    ];
    const messages = {
        USERNAME_UNAVAILABLE: "Это имя пользователя уже занято. Выберите другое.",
        INVALID_CREDENTIALS: "Неверное имя пользователя или пароль.",
        INVALID_CURRENT_PASSWORD: "Текущий пароль неверен.",
        PASSWORD_UNCHANGED: "Новый пароль должен отличаться от текущего.",
        INVALID_REQUEST: "Проверьте введённые данные и ограничения полей.",
        UNSUPPORTED_LANGUAGE: "Проверьте название языка или выберите его из списка.",
        UNKNOWN_MODEL: "Модель больше недоступна. Загрузите список моделей снова.",
        MODEL_FORBIDDEN: "У вашего аккаунта нет доступа к этой модели.",
        TEXT_TOO_LARGE: "Текст превышает лимит выбранной модели. Сократите его.",
        REQUEST_TOO_LARGE: "Запрос слишком большой. Сократите исходный текст.",
        RATE_LIMIT: "Слишком много запросов. Подождите минуту и попробуйте снова.",
        AUTH_RATE_LIMIT: "Слишком много попыток. Подождите минуту и попробуйте снова.",
        CONCURRENCY_LIMIT: "Предыдущие переводы ещё выполняются. Попробуйте немного позже.",
        GLOBAL_CONCURRENCY_LIMIT: "Сервис занят. Попробуйте немного позже.",
        DAILY_QUOTA: "Дневной лимит переводов вашего аккаунта исчерпан.",
        GLOBAL_DAILY_QUOTA: "Дневной бюджет сервиса исчерпан. Попробуйте позже.",
        UPLOAD_CAPACITY: "Сервис занят. Попробуйте немного позже.",
        PROVIDER_BUSY: "Сервис перевода временно занят. Попробуйте позже.",
        PROVIDER_TIMEOUT: "Сервис перевода не успел ответить. Повторите запрос вручную, когда будете готовы.",
        PROVIDER_ERROR: "Сервис перевода временно недоступен. Попробуйте позже.",
        INVALID_PROVIDER_RESPONSE: "Не удалось получить корректный перевод. Попробуйте позже."
    };
    const state = {
        session: null, models: [], authBusy: false, creatingUser: false,
        translating: false, loadingWorkspace: false, refresh: null, detectedSource: null, costRevision: 0
    };
    const sessionStore = new BrowserSessionStore();
    const customLanguage = "__custom__";
    const includeUsagePreferenceKey = "translator.includeUsage";
    const languageNames = new Map(languages);
    const localizedLanguages = new Intl.DisplayNames(["ru"], { type: "language" });
    const usd = new Intl.NumberFormat("ru-RU", { maximumFractionDigits: 12 });
    const passwordFields = [ui.currentPassword, ui.replacementPassword, ui.confirmPassword];
    const bytes = (value) => new TextEncoder().encode(value).length;
    const number = (value) => value.toLocaleString("ru-RU");

    class RequestError extends Error {
        constructor(message, status = 0) {
            super(message);
            this.status = status;
        }
    }

    function showMessage(target, text = "", success = false) {
        target.textContent = text;
        target.hidden = !text;
        target.classList.toggle("success", success);
    }

    function selectedModel() {
        return state.models.find((model) => model.id === ui.model.value);
    }

    function languageValue(select, input) {
        return select.value === customLanguage ? input.value.trim() : select.value;
    }

    function setLanguage(select, input, value) {
        select.value = languageNames.has(value) ? value : customLanguage;
        input.value = select.value === customLanguage ? value : "";
    }

    function detectedLanguageLabel(code) {
        if (code === "und") {
            return "Не удалось определить язык";
        }
        const name = languageNames.get(code) ?? (/^[a-z]{2,3}$/.test(code)
            ? localizedLanguages.of(code) : code);
        return `Определённый язык: ${name}`;
    }

    function validCost(value) {
        return Number.isFinite(value) && value >= 0;
    }

    function inputAllowed() {
        const model = selectedModel();
        return Boolean(ui.source.value.trim()) && ui.source.value.length <= 10000
            && Boolean(model) && bytes(ui.source.value) <= model.maxInputBytes
            && (ui.detectSource.checked || Boolean(languageValue(ui.sourceLanguage, ui.customSource)))
            && Boolean(languageValue(ui.targetLanguage, ui.customTarget));
    }

    function updateLanguageControls() {
        const auto = ui.detectSource.checked;
        const sourceStatus = state.detectedSource
            ? detectedLanguageLabel(state.detectedSource) : "Исходный язык определится при переводе";
        ui.manualSource.hidden = auto;
        ui.detectedLanguage.hidden = !auto;
        if (ui.detectedLanguage.textContent !== sourceStatus) {
            ui.detectedLanguage.textContent = sourceStatus;
        }
        ui.detectSource.disabled = state.translating;
        ui.sourceLanguage.disabled = state.translating || auto;
        ui.targetLanguage.disabled = state.translating;
        ui.customSource.hidden = ui.sourceLanguage.value !== customLanguage;
        ui.customTarget.hidden = ui.targetLanguage.value !== customLanguage;
        ui.customSource.disabled = ui.sourceLanguage.disabled || ui.customSource.hidden;
        ui.customTarget.disabled = ui.targetLanguage.disabled || ui.customTarget.hidden;
        const source = auto ? state.detectedSource : languageValue(ui.sourceLanguage, ui.customSource);
        const target = languageValue(ui.targetLanguage, ui.customTarget);
        ui.swap.disabled = state.translating || !source || (auto && source === "und") || !target;
    }

    function updateControls() {
        const busy = state.authBusy || state.creatingUser || state.loadingWorkspace || state.translating;
        const signedIn = Boolean(state.session);
        ui.authPanel.hidden = signedIn;
        ui.logout.hidden = !signedIn;
        ui.changePassword.hidden = !signedIn;
        ui.totalCost.hidden = !signedIn;
        ui.logout.disabled = busy;
        ui.changePassword.disabled = busy;
        ui.changePasswordSubmit.disabled = busy;
        for (const input of passwordFields) {
            input.disabled = busy;
        }
        ui.authSubmit.disabled = busy;
        ui.username.disabled = busy;
        ui.password.disabled = busy;
        ui.createUserPanel.hidden = !state.session?.canCreateUsers;
        ui.createUserSubmit.disabled = busy;
        ui.newUsername.disabled = busy;
        ui.newPassword.disabled = busy;
        ui.model.disabled = busy || !signedIn || state.models.length === 0;
        ui.reloadModels.hidden = !signedIn;
        ui.reloadModels.disabled = busy;
        ui.source.disabled = state.translating;
        ui.includeUsage.disabled = state.translating;
        updateLanguageControls();
        ui.clear.disabled = state.translating || !ui.source.value;
        ui.copy.disabled = state.translating || !ui.result.value;
        ui.translate.disabled = busy || !signedIn || !inputAllowed();
        ui.translate.textContent = state.translating ? "Переводим…" : "Перевести →";
        ui.sourceCount.textContent = `${number(ui.source.value.length)} / 10 000 символов`;
        const model = selectedModel();
        ui.inputLimit.textContent = model
            ? `${number(bytes(ui.source.value))} / ${number(model.maxInputBytes)} байт · до 10 000 символов`
            : "До 10 000 символов. Лимит в байтах зависит от модели.";
        ui.source.setCustomValidity(model && bytes(ui.source.value) > model.maxInputBytes
            ? "Текст превышает лимит модели в байтах." : "");
        ui.result.setAttribute("aria-busy", String(state.translating));
    }

    async function request(path, { body, token } = {}) {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), 45000);
        const headers = { Accept: "application/json" };
        if (body !== undefined) {
            headers["Content-Type"] = "application/json";
        }
        if (token) {
            headers.Authorization = `Bearer ${token}`;
        }
        try {
            const response = await fetch(path, {
                method: body === undefined ? "GET" : "POST", headers,
                body: body === undefined ? undefined : JSON.stringify(body),
                cache: "no-store", credentials: "omit", signal: controller.signal
            });
            const payload = response.status === 204 ? null : await response.json().catch(() => null);
            if (!response.ok) {
                const fallback = response.status === 429
                    ? "Слишком много запросов. Подождите и попробуйте снова."
                    : response.status === 401 ? "Войдите в аккаунт снова."
                    : response.status === 403 ? "Доступ запрещён. Проверьте права аккаунта."
                    : `Запрос не выполнен (HTTP ${response.status}). Попробуйте позже.`;
                throw new RequestError(messages[payload?.code] || fallback, response.status);
            }
            return payload;
        } catch (error) {
            if (error instanceof RequestError) {
                throw error;
            }
            throw new RequestError(error.name === "AbortError"
                ? "Время ожидания истекло. Результат запроса неизвестен; повторите его вручную."
                : "Нет связи с сервером. Проверьте подключение. Запрос не повторён автоматически.");
        } finally {
            clearTimeout(timer);
        }
    }

    function canCreateUsers(accessToken) {
        try {
            const payload = accessToken.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
            const { scope } = JSON.parse(atob(payload));
            return typeof scope === "string" && scope.split(" ").includes("users:create");
        } catch {
            return false;
        }
    }

    function saveSession(tokens) {
        if (!tokens?.accessToken || !tokens?.refreshToken || !Number.isFinite(tokens.expiresIn)) {
            throw new RequestError("Сервер вернул некорректную сессию. Войдите снова.");
        }
        sessionStore.save(tokens.refreshToken, ui.username.value);
        state.session = {
            ...tokens,
            expiresAt: Date.now() + tokens.expiresIn * 1000,
            canCreateUsers: canCreateUsers(tokens.accessToken)
        };
    }

    function clearSession() {
        let cleanupWarning = "";
        try {
            sessionStore.clear();
        } catch {
            cleanupWarning = "Не удалось удалить сохранённый вход из хранилища браузера.";
        }
        state.session = null;
        state.costRevision++;
        state.models = [];
        ui.createUserForm.reset();
        ui.newPassword.setCustomValidity("");
        showMessage(ui.createUserMessage);
        closePasswordChange();
        ui.model.replaceChildren(new Option("Войдите для выбора модели", ""));
        ui.totalCost.textContent = "";
        invalidateResult();
        return cleanupWarning;
    }

    async function refreshSession() {
        if (!state.session) {
            throw new RequestError("Войдите в аккаунт, чтобы продолжить.", 401);
        }
        if (!state.refresh) {
            const refreshToken = state.session.refreshToken;
            state.refresh = (async () => {
                try {
                    sessionStore.clear();
                    saveSession(await request("/v1/auth/refresh", { body: { refreshToken } }));
                } catch (error) {
                    // A lost response may have consumed the single-use token. Never replay it.
                    const cleanupWarning = clearSession();
                    showMessage(ui.authMessage, "Не удалось обновить сессию. Войдите снова. "
                        + error.message + " " + cleanupWarning);
                    throw error;
                }
            })().finally(() => { state.refresh = null; });
        }
        return state.refresh;
    }

    async function authenticatedRequest(path, body) {
        if (!state.session) {
            throw new RequestError("Войдите в аккаунт, чтобы продолжить.", 401);
        }
        let refreshed = false;
        if (Date.now() >= state.session.expiresAt - 5000) {
            await refreshSession();
            refreshed = true;
        }
        try {
            return await request(path, { body, token: state.session.accessToken });
        } catch (error) {
            if (error.status !== 401) {
                throw error;
            }
            if (!refreshed) {
                await refreshSession();
                try {
                    return await request(path, { body, token: state.session.accessToken });
                } catch (retryError) {
                    if (retryError.status !== 401) {
                        throw retryError;
                    }
                }
            }
            clearSession();
            showMessage(ui.authMessage, "Сессия завершена. Войдите снова.");
            throw new RequestError("Сессия завершена. Войдите снова.", 401);
        }
    }

    async function loadModels() {
        const models = await authenticatedRequest("/v1/models");
        if (!Array.isArray(models) || models.some((model) => !model?.id || !model.name
            || !Number.isInteger(model.maxInputBytes) || model.maxInputBytes <= 0)) {
            throw new RequestError("Сервер вернул некорректный список моделей.");
        }
        const previous = ui.model.value;
        state.models = models;
        ui.model.replaceChildren(...models.map((model) => new Option(model.name, model.id)));
        if (models.some((model) => model.id === previous)) {
            ui.model.value = previous;
        }
        if (!models.length) {
            ui.model.add(new Option("Нет доступных моделей", ""));
            throw new RequestError("Для аккаунта нет доступных моделей. Обратитесь к администратору.");
        }
    }

    async function loadCost() {
        const token = state.session?.accessToken;
        if (!token) {
            return;
        }
        const revision = ++state.costRevision;
        let label;
        try {
            const summary = await request("/v1/translation-cost", { token });
            if (!validCost(summary?.totalCostUsd) || !Number.isInteger(summary.unpricedRequests)
                || summary.unpricedRequests < 0) {
                throw new RequestError("Сервер вернул некорректную стоимость.");
            }
            const unpriced = summary.unpricedRequests
                ? ` · без расчёта: ${number(summary.unpricedRequests)}` : "";
            label = `Учтено по аккаунту: ${usd.format(summary.totalCostUsd)} USD${unpriced}`;
        } catch {
            label = "Общая стоимость недоступна";
        }
        if (revision === state.costRevision) {
            ui.totalCost.textContent = label;
        }
    }

    async function loadWorkspace() {
        state.loadingWorkspace = true;
        ui.totalCost.textContent = "Загрузка стоимости…";
        updateControls();
        try {
            await loadModels();
        } finally {
            state.loadingWorkspace = false;
            updateControls();
            void loadCost();
        }
    }

    function invalidateResult() {
        state.detectedSource = null;
        ui.requestCost.hidden = true;
        ui.requestCost.textContent = "";
        ui.result.value = "";
        renderUsage();
        ui.resultStatus.textContent = "Готов к работе";
        showMessage(ui.message);
        updateControls();
    }

    function usageText(tag, text) {
        const line = document.createElement(tag);
        line.dir = "auto";
        line.textContent = text;
        return line;
    }

    function renderUsage({ alternatives = [], examples = [] } = {}) {
        ui.alternatives.replaceChildren(...alternatives.map((text) => usageText("li", text)));
        ui.examples.replaceChildren(...examples.map(({ sourceText, translatedText }) => {
            const example = document.createElement("li");
            example.append(usageText("span", sourceText), usageText("span", translatedText));
            return example;
        }));
        ui.alternatives.parentElement.hidden = alternatives.length === 0;
        ui.examples.parentElement.hidden = examples.length === 0;
        ui.details.hidden = alternatives.length === 0 && examples.length === 0;
    }

    function validatePassword(input) {
        if (bytes(input.value) <= 72) {
            return true;
        }
        input.setCustomValidity("Пароль не должен превышать 72 байта UTF-8.");
        input.reportValidity();
        return false;
    }

    function closePasswordChange() {
        ui.changePasswordForm.reset();
        for (const input of passwordFields) {
            input.setCustomValidity("");
        }
        showMessage(ui.changePasswordMessage);
        ui.changePasswordPanel.hidden = true;
        ui.changePassword.setAttribute("aria-expanded", "false");
    }

    function validatePasswordChange() {
        if (!validatePassword(ui.currentPassword) || !validatePassword(ui.replacementPassword)) {
            return false;
        }
        if (ui.replacementPassword.value !== ui.confirmPassword.value) {
            ui.confirmPassword.setCustomValidity("Новые пароли не совпадают.");
            ui.confirmPassword.reportValidity();
            return false;
        }
        return true;
    }

    ui.password.addEventListener("input", () => ui.password.setCustomValidity(""));
    ui.authForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        if (ui.authSubmit.disabled || !validatePassword(ui.password)) {
            return;
        }
        state.authBusy = true;
        showMessage(ui.authMessage);
        showMessage(ui.message);
        const body = { username: ui.username.value, password: ui.password.value };
        updateControls();
        ui.authSubmit.textContent = "Подождите…";
        try {
            saveSession(await request("/v1/auth/login", { body }));
            ui.password.value = "";
            await loadWorkspace();
            ui.source.focus();
        } catch (error) {
            showMessage(state.session ? ui.message : ui.authMessage, error.message);
        } finally {
            body.password = "";
            state.authBusy = false;
            ui.authSubmit.textContent = "Войти ↗";
            updateControls();
        }
    });

    ui.newPassword.addEventListener("input", () => ui.newPassword.setCustomValidity(""));
    ui.createUserForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        if (!state.session?.canCreateUsers || ui.createUserSubmit.disabled || !validatePassword(ui.newPassword)) {
            return;
        }
        state.creatingUser = true;
        showMessage(ui.createUserMessage);
        updateControls();
        const body = { username: ui.newUsername.value, password: ui.newPassword.value };
        try {
            await authenticatedRequest("/v1/auth/register", body);
            ui.createUserForm.reset();
            showMessage(ui.createUserMessage, `Пользователь ${body.username} создан.`, true);
        } catch (error) {
            if (state.session) {
                showMessage(ui.createUserMessage, error.message);
            }
        } finally {
            body.password = "";
            ui.newPassword.value = "";
            state.creatingUser = false;
            updateControls();
        }
    });

    ui.changePassword.addEventListener("click", () => {
        if (ui.changePasswordPanel.hidden) {
            ui.changePasswordPanel.hidden = false;
            ui.changePassword.setAttribute("aria-expanded", "true");
            ui.currentPassword.focus();
        } else {
            closePasswordChange();
        }
    });

    ui.currentPassword.addEventListener("input", () => ui.currentPassword.setCustomValidity(""));
    ui.replacementPassword.addEventListener("input", () => {
        ui.replacementPassword.setCustomValidity("");
        ui.confirmPassword.setCustomValidity("");
    });
    ui.confirmPassword.addEventListener("input", () => ui.confirmPassword.setCustomValidity(""));
    ui.changePasswordForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        if (!state.session || ui.changePasswordSubmit.disabled || !validatePasswordChange()) {
            return;
        }
        state.authBusy = true;
        showMessage(ui.changePasswordMessage);
        updateControls();
        const body = {
            currentPassword: ui.currentPassword.value,
            newPassword: ui.replacementPassword.value
        };
        try {
            await authenticatedRequest("/v1/auth/change-password", body);
            clearSession();
            showMessage(ui.authMessage, "Пароль изменён. Войдите с новым паролем.", true);
        } catch (error) {
            if (state.session) {
                showMessage(ui.changePasswordMessage, error.message);
            }
        } finally {
            body.currentPassword = "";
            body.newPassword = "";
            ui.changePasswordForm.reset();
            state.authBusy = false;
            updateControls();
            if (!state.session) {
                ui.password.focus();
            }
        }
    });

    ui.reloadModels.addEventListener("click", async () => {
        showMessage(ui.message);
        try {
            await loadWorkspace();
        } catch (error) {
            showMessage(ui.message, error.message);
        }
    });

    ui.logout.addEventListener("click", async () => {
        if (ui.logout.disabled || !state.session) {
            return;
        }
        const refreshToken = state.session.refreshToken;
        state.authBusy = true;
        updateControls();
        const cleanupWarning = clearSession();
        let warning = "";
        try {
            await request("/v1/auth/logout", { body: { refreshToken } });
        } catch (error) {
            warning = "Вход в этой вкладке завершён, но сервер не подтвердил отзыв сессии. " + error.message;
        } finally {
            state.authBusy = false;
            warning = [warning, cleanupWarning].filter(Boolean).join(" ");
            showMessage(ui.authMessage, warning || "Вы вышли из аккаунта.", !warning);
            ui.username.focus();
            updateControls();
        }
    });

    ui.translate.addEventListener("click", async () => {
        if (ui.translate.disabled) {
            return;
        }
        const detectSourceLanguage = ui.detectSource.checked;
        const body = {
            text: ui.source.value, detectSourceLanguage,
            includeUsage: ui.includeUsage.checked || undefined,
            sourceLanguage: detectSourceLanguage ? undefined : languageValue(ui.sourceLanguage, ui.customSource),
            targetLanguage: languageValue(ui.targetLanguage, ui.customTarget), model: ui.model.value
        };
        state.translating = true;
        invalidateResult();
        ui.resultStatus.textContent = "Переводим текст…";
        try {
            const result = await authenticatedRequest("/v1/translations", body);
            if (typeof result?.translatedText !== "string" || (detectSourceLanguage
                && (typeof result.sourceLanguage !== "string" || !result.sourceLanguage.trim()))) {
                throw new RequestError("Сервер вернул некорректный перевод. Повторите запрос вручную.");
            }
            ui.result.value = result.translatedText;
            if (body.includeUsage) {
                renderUsage(result);
            }
            ui.resultStatus.textContent = "Перевод готов";
            state.detectedSource = detectSourceLanguage ? result.sourceLanguage : null;
            ui.requestCost.textContent = validCost(result.costUsd)
                ? `Стоимость перевода: ${usd.format(result.costUsd)} USD`
                : "Стоимость перевода недоступна";
            ui.requestCost.hidden = false;
        } catch (error) {
            ui.resultStatus.textContent = "Перевод не выполнен";
            showMessage(ui.message, error.message);
        } finally {
            state.translating = false;
            updateControls();
            void loadCost();
        }
    });

    ui.copy.addEventListener("click", async () => {
        try {
            await navigator.clipboard.writeText(ui.result.value);
            ui.resultStatus.textContent = "Перевод скопирован";
        } catch {
            ui.result.focus();
            ui.result.select();
            ui.resultStatus.textContent = "Текст выделен. Нажмите Ctrl + C для копирования.";
        }
    });
    ui.clear.addEventListener("click", () => {
        ui.source.value = "";
        invalidateResult();
        ui.source.focus();
    });
    ui.swap.addEventListener("click", () => {
        const previousSource = ui.detectSource.checked ? state.detectedSource
            : languageValue(ui.sourceLanguage, ui.customSource);
        const previousTarget = languageValue(ui.targetLanguage, ui.customTarget);
        ui.detectSource.checked = false;
        setLanguage(ui.sourceLanguage, ui.customSource, previousTarget);
        setLanguage(ui.targetLanguage, ui.customTarget, previousSource);
        if (ui.result.value && ui.result.value.length <= 10000) {
            ui.source.value = ui.result.value;
        }
        invalidateResult();
    });
    ui.source.addEventListener("input", invalidateResult);
    ui.detectSource.addEventListener("change", invalidateResult);
    ui.includeUsage.addEventListener("change", () => {
        try {
            localStorage.setItem(includeUsagePreferenceKey, String(ui.includeUsage.checked));
        } catch {
            // The checkbox still applies to translations on this page.
        }
        invalidateResult();
    });
    for (const [select, input] of [[ui.sourceLanguage, ui.customSource], [ui.targetLanguage, ui.customTarget]]) {
        select.addEventListener("change", () => {
            invalidateResult();
            if (select.value === customLanguage) {
                input.focus();
            }
        });
        input.addEventListener("input", invalidateResult);
    }
    ui.model.addEventListener("change", invalidateResult);
    ui.source.addEventListener("keydown", (event) => {
        if ((event.ctrlKey || event.metaKey) && event.key === "Enter") {
            event.preventDefault();
            ui.translate.click();
        }
    });

    for (const [code, name] of languages) {
        ui.sourceLanguage.add(new Option(name, code));
        ui.targetLanguage.add(new Option(name, code));
    }
    ui.sourceLanguage.add(new Option("Другой язык…", customLanguage));
    ui.targetLanguage.add(new Option("Другой язык…", customLanguage));
    ui.sourceLanguage.value = "en";
    ui.targetLanguage.value = "ru";
    try {
        ui.includeUsage.checked = localStorage.getItem(includeUsagePreferenceKey) === "true";
    } catch {
        ui.includeUsage.checked = false;
    }
    async function restoreSession() {
        state.authBusy = true;
        updateControls();
        try {
            if (!await sessionStore.open()) {
                element("session-status").textContent =
                    "Сохранение входа недоступно. Используйте HTTPS и разрешите локальное хранилище.";
            }
            const saved = sessionStore.load();
            if (saved) {
                ui.username.value = saved.username;
                showMessage(ui.authMessage, "Восстановление сессии…");
                sessionStore.clear();
                saveSession(await request("/v1/auth/refresh", {
                    body: { refreshToken: saved.refreshToken }
                }));
                showMessage(ui.authMessage);
                await loadWorkspace();
            }
        } catch (error) {
            if (!state.session) {
                showMessage(ui.authMessage, "Не удалось восстановить сессию. Войдите снова. " + error.message);
            } else {
                showMessage(ui.message, error.message);
            }
        } finally {
            state.authBusy = false;
            updateControls();
        }
    }

    restoreSession();
})();
