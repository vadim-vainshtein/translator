"use strict";

class BrowserSessionStore {
    constructor() {
        this.prefix = "translator.session.";
        this.key = null;
    }

    async open() {
        if (!navigator.locks) {
            return false;
        }
        let keys;
        try {
            const probe = "translator.storage-probe." + crypto.randomUUID();
            localStorage.setItem(probe, "null");
            localStorage.removeItem(probe);
            keys = Object.keys(localStorage).filter((key) => key.startsWith(this.prefix)).sort();
        } catch {
            return false;
        }
        for (const key of keys) {
            if (await this.claim(key)) {
                return true;
            }
        }
        return this.claim(this.prefix + crypto.randomUUID());
    }

    async claim(key) {
        return new Promise((resolve, reject) => {
            navigator.locks.request(key, { ifAvailable: true }, async (lock) => {
                if (!lock) {
                    resolve(false);
                    return;
                }
                this.key = key;
                resolve(true);
                // Hold ownership until the document is destroyed. Other tabs use their own slots.
                await new Promise((release) => { this.release = release; });
            }).catch(reject);
        });
    }

    load() {
        if (!this.key) {
            return null;
        }
        const encoded = localStorage.getItem(this.key);
        if (encoded === null) {
            return null;
        }
        try {
            const saved = JSON.parse(encoded);
            if (typeof saved.refreshToken === "string" && saved.refreshToken
                    && typeof saved.username === "string") {
                return saved;
            }
        } catch {
            // Discard malformed local data before returning to sign-in.
        }
        this.clear();
        return null;
    }

    save(refreshToken, username) {
        if (this.key) {
            localStorage.setItem(this.key, JSON.stringify({ refreshToken, username }));
        }
    }

    clear() {
        if (this.key) {
            localStorage.removeItem(this.key);
        }
    }
}
