/* Desktop plugin host contract, backed by the iOS sandbox. */
(() => {
  const call = (operation, ...values) => {
    const response = __host(operation, values);
    if (response.error) throw new Error(response.error);
    return response.value;
  };
  const pending = new Map();
  const request = event => new Promise((resolve, reject) => {
    const id = __request(event);
    event.requestID = id;
    pending.set(id, { resolve, reject });
  });
  globalThis.__settle = (id, success, value) => {
    const found = pending.get(id);
    if (!found) return;
    pending.delete(id);
    if (success) found.resolve(value);
    else {
      const error = new Error(value.message);
      if (value.code) error.code = value.code;
      found.reject(error);
    }
  };
  globalThis.host = Object.freeze({
    platform: "ios", arch: "arm64", dir: ".", dataDir: ".",
    capabilities: Object.freeze({ webSignInHTML: true, cencAudio: true }),
    storage: Object.freeze({ get: key => call("storage.get", String(key)), set: (key, value) => call("storage.set", String(key), value), remove: key => call("storage.remove", String(key)) }),
    language: () => call("language"), t: text => call("t", String(text)),
    setting: key => call("setting", String(key)),
    crypto: Object.freeze({ md5: text => call("md5", String(text)), aesEcbEncrypt: (key, text) => call("aes", String(key), String(text)) }),
    signInPrompt: prompt => call("prompt", prompt),
    openURL: url => request({ kind: "openURL", url: String(url) }),
    spawn: () => { throw new Error("iOS does not provide external processes"); },
    waitForRedirect: () => { throw new Error("This sign-in callback needs a desktop host"); },
    webSignIn: options => request({ kind: "webSignIn", options }),
    oauthSignIn: options => request({ kind: "oauthSignIn", options }),
    removeDataDir: path => call("removeDataDir", path),
  });
  globalThis.console = Object.freeze(Object.fromEntries(["log", "info", "warn", "error", "debug"].map(name => [name, (...args) => call("console")])));
  const timers = new Map();
  globalThis.setTimeout = (callback, milliseconds = 0, ...args) => {
    const event = { kind: "timer", delay: Math.min(300000, Math.max(0, Number(milliseconds) || 0)) };
    const id = __request(event);
    timers.set(id, true);
    pending.set(id, { resolve: () => { if (timers.delete(id)) callback(...args); }, reject: () => timers.delete(id) });
    return id;
  };
  globalThis.clearTimeout = id => { timers.delete(id); pending.delete(id); __request({kind:"cancel", request:id}); };
  globalThis.setInterval = (callback, milliseconds = 0, ...args) => {
    const token = { active: true, id: 0 };
    const tick = () => { if (token.active) { callback(...args); if (token.active) token.id = setTimeout(tick, Math.max(1, milliseconds)); } };
    token.id = setTimeout(tick, Math.max(1, milliseconds)); return token;
  };
  globalThis.clearInterval = token => { token.active = false; clearTimeout(token.id); };
  globalThis.queueMicrotask = callback => Promise.resolve().then(callback);
  class Headers {
    constructor(values = {}) {
      this.items = new Map();
      for (const [key, value] of values instanceof Headers ? values.entries() : Array.isArray(values) ? values : Object.entries(values)) this.set(key, value);
    }
    get(key) { return this.items.get(String(key).toLowerCase()) ?? null; }
    set(key, value) { this.items.set(String(key).toLowerCase(), String(value)); }
    append(key, value) { this.set(key, this.has(key) ? this.get(key) + ", " + value : value); }
    has(key) { return this.items.has(String(key).toLowerCase()); }
    delete(key) { this.items.delete(String(key).toLowerCase()); }
    entries() { return this.items.entries(); }
    keys() { return this.items.keys(); }
    values() { return this.items.values(); }
    forEach(callback, owner) { for (const [key, value] of this.items) callback.call(owner, value, key, this); }
    [Symbol.iterator]() { return this.entries(); }
  }
  globalThis.Headers = Headers;
  class URLSearchParams {
    constructor(input = "") {
      this.pairs = [];
      if (input instanceof URLSearchParams) this.pairs = [...input.entries()];
      else if (typeof input === "string") {
        for (const part of input.replace(/^\?/, "").split("&")) if (part) {
          const index = part.indexOf("=");
          this.pairs.push([decodeURIComponent((index < 0 ? part : part.slice(0, index)).replace(/\+/g, " ")), decodeURIComponent((index < 0 ? "" : part.slice(index + 1)).replace(/\+/g, " "))]);
        }
      } else for (const [key, value] of Array.isArray(input) ? input : Object.entries(input)) this.append(key, value);
    }
    append(key, value) { this.pairs.push([String(key), String(value)]); }
    get(key) { return this.pairs.find(pair => pair[0] === String(key))?.[1] ?? null; }
    getAll(key) { return this.pairs.filter(pair => pair[0] === String(key)).map(pair => pair[1]); }
    has(key) { return this.pairs.some(pair => pair[0] === String(key)); }
    set(key, value) { const index = this.pairs.findIndex(pair => pair[0] === String(key)); this.delete(key); this.pairs.splice(index < 0 ? this.pairs.length : index, 0, [String(key), String(value)]); }
    delete(key) { this.pairs = this.pairs.filter(pair => pair[0] !== String(key)); }
    sort() { this.pairs.sort((a, b) => a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0); }
    *entries() { yield* this.pairs; }
    *keys() { for (const pair of this.pairs) yield pair[0]; }
    *values() { for (const pair of this.pairs) yield pair[1]; }
    forEach(callback, owner) { for (const [key, value] of this.pairs) callback.call(owner, value, key, this); }
    [Symbol.iterator]() { return this.entries(); }
    toString() { const encode = value => encodeURIComponent(value).replace(/[!'()~]/g, char => "%" + char.charCodeAt(0).toString(16).toUpperCase()).replace(/%20/g, "+"); return this.pairs.map(pair => encode(pair[0]) + "=" + encode(pair[1])).join("&"); }
  }
  globalThis.URLSearchParams = URLSearchParams;
  globalThis.URL = class URL {
    constructor(url, base) { this.href = base == null ? String(url) : call("url", String(url), String(base)).href; }
    get href() { return this.toString(); }
    set href(value) { const fields = call("url", String(value)); for (const key of ["protocol","host","hostname","pathname","hash"]) this[key] = fields[key]; this.searchParams = new URLSearchParams(fields.search); }
    get search() { const text = this.searchParams.toString(); return text ? "?" + text : ""; }
    set search(value) { this.searchParams = new URLSearchParams(value); }
    get origin() { return this.protocol + "//" + this.host; }
    toString() { return this.protocol + "//" + this.host + this.pathname + (this.searchParams.toString() ? "?" + this.searchParams : "") + this.hash; }
    toJSON() { return this.toString(); }
  };
  globalThis.atob = value => call("base64.decode", String(value));
  globalThis.btoa = value => call("base64.encode", String(value));
  class AbortSignal {
    constructor() { this.aborted = false; this.reason = undefined; this.listeners = new Map(); }
    throwIfAborted() { if (this.aborted) throw this.reason; }
    addEventListener(type, callback, options = {}) { if (type === "abort") this.listeners.set(callback, options.once); }
    removeEventListener(type, callback) { if (type === "abort") this.listeners.delete(callback); }
    dispatchEvent(event) { if (event.type !== "abort") return true; for (const [callback, once] of [...this.listeners]) { if (once) this.listeners.delete(callback); callback.call(this, event); } return true; }
  }
  globalThis.AbortSignal = AbortSignal;
  globalThis.AbortController = class AbortController {
    constructor() { this.signal = new AbortSignal(); }
    abort(reason = new Error("Operation aborted")) { if (this.signal.aborted) return; this.signal.aborted = true; this.signal.reason = reason; this.signal.dispatchEvent({type:"abort"}); }
  };
  globalThis.fetch = async (input, options = {}) => {
    const headers = new Headers(options.headers);
    let body = options.body;
    if (body instanceof URLSearchParams) { body = body.toString(); if (!headers.has("content-type")) headers.set("content-type", "application/x-www-form-urlencoded;charset=UTF-8"); }
    if (body != null && typeof body !== "string") throw new TypeError("Fetch body must be text or URLSearchParams");
    options.signal?.throwIfAborted();
    let abort;
    const redirect = options.redirect ?? "follow";
    if (!["follow", "manual", "error"].includes(redirect)) throw new TypeError("Invalid fetch redirect mode");
    const event = { kind: "fetch", url: String(input), method: options.method || "GET", headers: Object.fromEntries(headers), body: body ?? null, redirect };
    const operation = request(event);
    const cancelled = new Promise((_, reject) => { abort = () => { __request({kind:"cancel", request:event.requestID}); pending.delete(event.requestID); reject(options.signal.reason); }; options.signal?.addEventListener("abort", abort, {once:true}); });
    let result;
    try { result = options.signal ? await Promise.race([operation, cancelled]) : await operation; }
    finally { options.signal?.removeEventListener("abort", abort); }
    options.signal?.throwIfAborted();
    return Object.freeze({
      ok: result.status >= 200 && result.status < 300, status: result.status, statusText: result.statusText || "", url: result.url,
      headers: Object.assign(new Headers(result.headers), {getSetCookie: () => result.setCookies || []}), text: async () => result.body, json: async () => JSON.parse(result.body),
      arrayBuffer: async () => Uint8Array.from(atob(result.base64), char => char.charCodeAt(0)).buffer,
    });
  };
})();
