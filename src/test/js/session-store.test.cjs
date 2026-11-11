const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { randomUUID } = require("node:crypto");
const { test } = require("node:test");

const assets = path.join(__dirname, "../../main/resources/static");
const storeSource = fs.readFileSync(path.join(assets, "session-store.js"), "utf8");
const appSource = fs.readFileSync(path.join(assets, "app.js"), "utf8");

function storage() {
    const values = {};
    Object.defineProperties(values, {
        getItem: { value(key) { return values[key] ?? null; } },
        setItem: { value(key, value) { values[key] = value; } },
        removeItem: { writable: true, value(key) { delete values[key]; } }
    });
    return values;
}

class Locks {
    held = new Set();

    async request(key, options, action) {
        if (this.held.has(key)) {
            return action(null);
        }
        this.held.add(key);
        try {
            return await action({ name: key });
        } finally {
            this.held.delete(key);
        }
    }
}

function tab(localStorage, locks = new Locks(), fetch = async () => assert.fail("Unexpected HTTP call")) {
    const elements = new Map();
    const document = {
        getElementById(id) {
            if (!elements.has(id)) {
                elements.set(id, {
                    value: "", checked: false, disabled: false, hidden: true, textContent: "",
                    classList: { toggle() {} },
                    parentElement: {}, events: {},
                    addEventListener(type, action) { this.events[type] = action; },
                    setAttribute() {}, setCustomValidity() {},
                    add() {}, reset() {}, replaceChildren() {}, focus() {}
                });
            }
            return elements.get(id);
        }
    };
    const context = vm.createContext({
        localStorage, navigator: { locks }, crypto: { randomUUID }, document,
        Intl, TextEncoder, AbortController, setTimeout, clearTimeout, fetch,
        Option: function(text, value) { this.text = text; this.value = value; },
        atob: (text) => Buffer.from(text, "base64").toString("binary")
    });
    vm.runInContext(storeSource, context);
    context.store = vm.runInContext("new BrowserSessionStore()", context);
    context.startApp = () => {
        const exposed = appSource.replace("    restoreSession();\n})();", `
            globalThis.app = { state, sessionStore, saveSession, clearSession, refreshSession };
            globalThis.startup = restoreSession();
        })();`);
        vm.runInContext(exposed, context);
        return context.startup;
    };
    return context;
}

test("parallel tabs keep separate durable sessions and restore an available session after closing", async () => {
    const saved = storage();
    const locks = new Locks();
    const first = tab(saved, locks).store;
    assert.equal(await first.open(), true);
    first.save("refresh-one", "alice");
    const second = tab(saved, locks).store;
    assert.equal(await second.open(), true);
    assert.equal(second.load(), null);
    second.save("refresh-two", "alice");
    assert.notEqual(first.key, second.key);

    first.release();
    await new Promise(setImmediate);
    const restarted = tab(saved, locks).store;
    assert.equal(await restarted.open(), true);
    assert.equal(restarted.load().refreshToken, "refresh-one");
    restarted.clear();
    assert.equal(second.load().refreshToken, "refresh-two");
    restarted.release();
    second.release();
});

test("unsupported locking and blocked storage fall back to an in-memory login", async () => {
    const noLocks = tab(storage(), undefined);
    noLocks.navigator.locks = undefined;
    assert.equal(await noLocks.store.open(), false);
    noLocks.store.save("refresh-one", "alice");
    assert.equal(noLocks.store.load(), null);

    const blocked = new Proxy({}, { ownKeys() { throw new Error("Blocked"); } });
    assert.equal(await tab(blocked).store.open(), false);
});

test("startup rotates a stored token and saves the replacement before loading the workspace", async () => {
    const saved = storage();
    saved["translator.session.existing"] = JSON.stringify({ refreshToken: "refresh-old", username: "alice" });
    const requests = [];
    const context = tab(saved, new Locks(), async (url, options) => {
        requests.push({ url, options });
        if (url === "/v1/auth/refresh") {
            assert.equal(saved["translator.session.existing"], undefined);
            return response({ accessToken: "access-new", refreshToken: "refresh-new", expiresIn: 900 });
        }
        assert.equal(JSON.parse(saved["translator.session.existing"]).refreshToken, "refresh-new");
        return response(url === "/v1/models"
            ? [{ id: "fast", name: "Fast", maxInputBytes: 20000 }]
            : { totalCostUsd: 0, unpricedRequests: 0 });
    });
    await context.startApp();
    assert.equal(context.app.state.session.refreshToken, "refresh-new");
    assert.equal(requests[0].url, "/v1/auth/refresh");
    assert.equal(requests[0].options.headers.Authorization, undefined);
    assert.equal(JSON.parse(requests[0].options.body).refreshToken, "refresh-old");
    assert.equal(requests[1].url, "/v1/models");
    assert.equal(requests[1].options.headers.Authorization, "Bearer access-new");
    context.app.sessionStore.release();
});

test("an uncertain refresh outcome clears the persisted credential without replaying it", async () => {
    const saved = storage();
    let calls = 0;
    const context = tab(saved, new Locks(), async () => {
        calls++;
        assert.equal(Object.keys(saved).filter((key) => key.startsWith("translator.session.")).length, 0);
        throw new Error("Connection lost");
    });
    await context.startApp();
    context.app.saveSession({ accessToken: "access-one", refreshToken: "refresh-one", expiresIn: 900 });
    await assert.rejects(context.app.refreshSession());
    assert.equal(context.app.state.session, null);
    assert.equal(context.app.sessionStore.load(), null);
    await assert.rejects(context.app.refreshSession());
    assert.equal(calls, 1);
    assert.match(context.document.getElementById("auth-message").textContent, /Не удалось обновить сессию/);
    context.app.sessionStore.release();
});

test("logout revokes the server session and clears memory when local storage deletion fails", async () => {
    const saved = storage();
    const requests = [];
    const context = tab(saved, new Locks(), async (url, options) => {
        requests.push({ url, options });
        return { status: 204, ok: true };
    });
    await context.startApp();
    context.app.saveSession({ accessToken: "access-one", refreshToken: "refresh-one", expiresIn: 900 });
    saved.removeItem = () => { throw new Error("Storage unavailable"); };
    const logout = context.document.getElementById("logout");
    logout.disabled = false;
    await logout.events.click();

    assert.equal(context.app.state.session, null);
    assert.equal(context.app.state.authBusy, false);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].url, "/v1/auth/logout");
    assert.equal(JSON.parse(requests[0].options.body).refreshToken, "refresh-one");
    assert.match(context.document.getElementById("auth-message").textContent, /Не удалось удалить/);
    context.app.sessionStore.release();
});

function response(body) {
    return { status: 200, ok: true, async json() { return body; } };
}
