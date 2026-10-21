/* OmniBrowser — среда chrome.* для настоящих расширений Chrome Web Store.
 * Один и тот же код работает в контент-скриптах (во всех фреймах вкладок)
 * и на страницах расширения (фоновая страница / service worker, popup, настройки).
 * Связь с приложением — через WebMessageListener (__OMNI_OBJ__) с секретным токеном. */
(function () {
  'use strict';
  var W = window;
  var RUN = '__OMNI_RUN__';
  var TOKEN = '__OMNI_TOKEN__';
  var OBJ = W['__OMNI_OBJ__'];
  if (!OBJ || W['__omniBoot_' + RUN]) return;
  try { Object.defineProperty(W, '__omniBoot_' + RUN, { value: true }); } catch (e) { return; }

  /* Захватываем нативные функции до запуска скриптов страницы. */
  var _post = OBJ.postMessage.bind(OBJ);
  var _listen = OBJ.addEventListener.bind(OBJ);
  var _stringify = JSON.stringify, _parse = JSON.parse;
  var _create = Object.create, _define = Object.defineProperty, _keys = Object.keys;
  var _isArray = Array.isArray, _slice = Array.prototype.slice;
  var _apply = Reflect.apply;
  var _setTimeout = W.setTimeout.bind(W), _clearTimeout = W.clearTimeout.bind(W);
  var _Promise = W.Promise, _RegExp = W.RegExp;
  var _err = (W.console && W.console.error) ? W.console.error.bind(W.console) : function () {};
  var _addDocListener = document.addEventListener.bind(document);

  var FRAME_KEY = Math.random().toString(36).slice(2) + Date.now().toString(36);
  var EXT_ORIGIN = /\.omni-ext\.invalid$/i.test(String(location.hostname));
  var IS_TOP = false;
  try { IS_TOP = W.top === W; } catch (e) { IS_TOP = false; }

  var seq = 0, pending = {}, contexts = {}, metas = {}, ports = {}, sheets = {};
  var frameKind = 'cs';

  function envelope(extId, op, args, cid) {
    var o = _create(null);
    o.t = TOKEN; o.fk = FRAME_KEY; o.top = IS_TOP; o.kind = frameKind;
    o.url = String(location.href); o.ext = extId || ''; o.op = op;
    o.a = (args === undefined) ? null : args;
    if (cid) o.cid = cid;
    return o;
  }
  function send(o) {
    try { _post(_stringify(o)); } catch (e) { _err('[OmniExt] не удалось отправить сообщение', e); }
  }
  function call(extId, op, args) {
    return new _Promise(function (resolve, reject) {
      var cid = ++seq;
      pending[cid] = { res: resolve, rej: reject };
      send(envelope(extId, op, args, cid));
    });
  }
  function fire(extId, op, args) { send(envelope(extId, op, args, 0)); }

  _listen('message', function (ev) {
    var m;
    try { m = _parse(ev.data); } catch (e) { return; }
    if (!m || typeof m !== 'object') return;
    try { dispatch(m); } catch (e) { _err('[OmniExt] ошибка обработки', e); }
  });

  function dispatch(m) {
    switch (m.op) {
      case 'reply': {
        var p = pending[m.cid];
        if (!p) return;
        delete pending[m.cid];
        if (m.ok) p.res(m.r); else p.rej(new Error(m.e || 'Unknown error'));
        return;
      }
      case 'msg': deliverMessage(m); return;
      case 'storage': deliverStorage(m); return;
      case 'connect': deliverConnect(m); return;
      case 'portMsg': { var pt = ports[m.pid]; if (pt) pt._recv(m.msg); return; }
      case 'portDisc': { var pd = ports[m.pid]; if (pd) pd._disc(m.e); return; }
      case 'event': deliverEvent(m); return;
      case 'auth': deliverAuth(m); return;
    }
  }

  function deliverMessage(m) {
    var ctx = contexts[m.ext];
    var ls = ctx ? ctx.api.runtime.onMessage._ls.slice() : [];
    if (!ls.length) { fire(m.ext, 'respond', { rid: m.rid, none: true }); return; }
    var responded = false, isAsync = false;
    function sendResponse(resp) {
      if (responded) return;
      responded = true;
      fire(m.ext, 'respond', { rid: m.rid, resp: resp === undefined ? null : resp, undef: resp === undefined });
    }
    for (var i = 0; i < ls.length; i++) {
      var r;
      try { r = _apply(ls[i], null, [m.msg, m.sender, sendResponse]); } catch (e) { _err(e); continue; }
      if (r === true) isAsync = true;
      else if (r && typeof r.then === 'function') {
        isAsync = true;
        r.then(function (v) { sendResponse(v); }, function (er) {
          if (responded) return;
          responded = true;
          fire(m.ext, 'respond', { rid: m.rid, err: String((er && er.message) || er) });
        });
      }
    }
    if (!responded && !isAsync) fire(m.ext, 'respond', { rid: m.rid, none: true, had: true });
  }

  function deliverStorage(m) {
    var ctx = contexts[m.ext];
    if (!ctx || !ctx.api.storage) return;
    var st = ctx.api.storage;
    st.onChanged._fire(m.changes, m.area);
    if (st[m.area] && st[m.area].onChanged) st[m.area].onChanged._fire(m.changes);
  }

  function deliverConnect(m) {
    var ctx = contexts[m.ext];
    if (!ctx || !ctx.api.runtime.onConnect._ls.length) { fire(m.ext, 'portRefuse', { pid: m.pid }); return; }
    var port = makePort(m.ext, m.pid, m.name, m.sender);
    ctx.api.runtime.onConnect._fire(port);
  }

  function deliverEvent(m) {
    var ctx = contexts[m.ext];
    if (!ctx) return;
    var parts = String(m.name).split('.'), target = ctx.api;
    for (var i = 0; i < parts.length && target; i++) target = target[parts[i]];
    if (target && target._fire) _apply(target._fire, target, m.args || []);
  }

  /* Запрос логина/пароля прокси (webRequest.onAuthRequired, blocking/asyncBlocking). */
  function deliverAuth(m) {
    var ctx = contexts[m.ext];
    var ev = ctx && ctx.api && ctx.api.webRequest && ctx.api.webRequest.onAuthRequired;
    var ls = ev ? ev._ls.slice() : [];
    if (!ls.length) { fire(m.ext, 'authResult', { rid: m.rid, none: true }); return; }
    var answered = false, left = ls.length;
    function answer(resp) {
      if (answered) return;
      var c = resp && resp.authCredentials;
      if (c && c.username != null) {
        answered = true;
        fire(m.ext, 'authResult', { rid: m.rid, creds: { u: String(c.username), p: String(c.password == null ? '' : c.password) } });
        return;
      }
      if ((resp && resp.cancel) || --left <= 0) {
        answered = true;
        fire(m.ext, 'authResult', { rid: m.rid, none: true });
      }
    }
    for (var i = 0; i < ls.length; i++) {
      (function (f) {
        var used = false;
        function cb(r) { if (used) return; used = true; answer(r); }
        var r;
        try { r = _apply(f, null, [m.details, cb]); } catch (e) { _err(e); cb(null); return; }
        if (r && typeof r.then === 'function') r.then(cb, function () { cb(null); });
        else if (r !== undefined) cb(r);
      })(ls[i]);
    }
  }

  function makeEvent() {
    var ls = [];
    var e = {
      addListener: function (f) { if (typeof f === 'function' && ls.indexOf(f) < 0) ls.push(f); },
      removeListener: function (f) { var i = ls.indexOf(f); if (i >= 0) ls.splice(i, 1); },
      hasListener: function (f) { return ls.indexOf(f) >= 0; },
      hasListeners: function () { return ls.length > 0; },
      addRules: function (r, cb) { if (typeof cb === 'function') _setTimeout(function () { cb(r || []); }, 0); },
      getRules: function (a, cb) { var f = typeof a === 'function' ? a : cb; if (typeof f === 'function') _setTimeout(function () { f([]); }, 0); },
      removeRules: function (a, cb) { var f = typeof a === 'function' ? a : cb; if (typeof f === 'function') _setTimeout(f, 0); }
    };
    _define(e, '_ls', { value: ls });
    _define(e, '_fire', {
      value: function () {
        var args = arguments, copy = ls.slice();
        for (var i = 0; i < copy.length; i++) {
          try { _apply(copy[i], null, args); } catch (er) { _err(er); }
        }
      }
    });
    return e;
  }

  function makePort(extId, pid, name, sender) {
    var onMessage = makeEvent(), onDisconnect = makeEvent(), connected = true;
    var port = {
      name: name || '',
      sender: sender || undefined,
      onMessage: onMessage,
      onDisconnect: onDisconnect,
      postMessage: function (msg) {
        if (!connected) throw new Error('Attempting to use a disconnected port object');
        fire(extId, 'portPost', { pid: pid, msg: msg === undefined ? null : msg });
      },
      disconnect: function () {
        if (!connected) return;
        connected = false;
        delete ports[pid];
        fire(extId, 'portDisconnect', { pid: pid });
      }
    };
    _define(port, '_recv', { value: function (msg) { if (connected) onMessage._fire(msg, port); } });
    _define(port, '_disc', {
      value: function (err) {
        if (!connected) return;
        connected = false;
        delete ports[pid];
        var ctx = contexts[extId];
        if (err && ctx) ctx.setLastError(err);
        try { onDisconnect._fire(port); } finally { if (err && ctx) ctx.setLastError(null); }
      }
    });
    ports[pid] = port;
    return port;
  }

  function lastFn(args) { return args.length && typeof args[args.length - 1] === 'function' ? args[args.length - 1] : null; }
  function noValue() { return undefined; }
  function msgValue(r) { return (r && typeof r === 'object' && ('v' in r)) ? r.v : undefined; }

  function makeCtx(extId, meta, kind) {
    var lastError = null;
    var ctx = { extId: extId, meta: meta, kind: kind };
    ctx.setLastError = function (msg) { lastError = msg ? { message: String(msg) } : null; };
    ctx.getLastError = function () { return lastError || undefined; };
    ctx.invoke = function (op, args, cb, map) {
      var p = call(extId, op, args).then(function (r) { return map ? map(r) : r; });
      if (typeof cb === 'function') {
        p.then(function (r) { try { cb(r); } catch (e) { _err(e); } },
          function (er) {
            ctx.setLastError((er && er.message) || String(er));
            try { cb(); } catch (e) { _err(e); } finally { ctx.setLastError(null); }
          });
        return undefined;
      }
      return p;
    };
    ctx.done = function (value, cb) {
      if (typeof cb === 'function') {
        _setTimeout(function () { try { cb(value); } catch (e) { _err(e); } }, 0);
        return undefined;
      }
      return _Promise.resolve(value);
    };
    ctx.fail = function (message, cb) {
      if (typeof cb === 'function') {
        _setTimeout(function () {
          ctx.setLastError(message);
          try { cb(); } catch (e) { _err(e); } finally { ctx.setLastError(null); }
        }, 0);
        return undefined;
      }
      return _Promise.reject(new Error(message));
    };
    contexts[extId] = ctx;
    ctx.api = buildApi(ctx);
    return ctx;
  }

  function colorToCss(c) {
    if (_isArray(c)) return 'rgba(' + (c[0] | 0) + ',' + (c[1] | 0) + ',' + (c[2] | 0) + ',' + ((c.length > 3 ? c[3] : 255) / 255) + ')';
    return String(c == null ? '' : c);
  }

  var KNOWN_NOOP = {
    accessibilityFeatures: 1, bookmarks: 1, browsingData: 1, contentSettings: 1, declarativeContent: 1,
    documentScan: 1, enterprise: 1, fontSettings: 1, gcm: 1, history: 1, identity: 1, instanceID: 1,
    pageCapture: 1, power: 1, printing: 1, privacy: 1, proxy: 1, readingList: 1, search: 1, sessions: 1,
    system: 1, tabCapture: 1, tabGroups: 1, topSites: 1, ttsEngine: 1, debugger: 1, desktopCapture: 1,
    loginState: 1, platformKeys: 1, processes: 1, webAuthenticationProxy: 1, devtools: 1, input: 1, dns: 1
  };
  function deepNoop() {
    var fn = function () {
      var cb = lastFn(arguments);
      if (cb) { _setTimeout(function () { try { cb(); } catch (e) { _err(e); } }, 0); return undefined; }
      return _Promise.resolve();
    };
    return new Proxy(fn, {
      get: function (t, k) {
        if (typeof k !== 'string' || k === 'then') return undefined;
        if (k === 'prototype' || k === 'name' || k === 'length' || k === 'call' || k === 'apply' || k === 'bind' || k === 'toString') return t[k];
        if (/^on[A-Z]/.test(k)) { if (!t['__ev_' + k]) t['__ev_' + k] = makeEvent(); return t['__ev_' + k]; }
        return deepNoop();
      }
    });
  }

  function buildApi(ctx) {
    var extId = ctx.extId, meta = ctx.meta, kind = ctx.kind;
    var inv = ctx.invoke, done = ctx.done, fail = ctx.fail;
    var manifest = meta.manifest || {};
    var messages = meta.messages || {};
    var base = meta.base;
    var isPage = kind !== 'cs';

    var runtime = {
      id: extId,
      getURL: function (p) {
        p = String(p == null ? '' : p);
        if (/^[a-z][a-z0-9+.-]*:/i.test(p)) return p;
        return base + p.replace(/^\/+/, '');
      },
      getManifest: function () { return _parse(_stringify(manifest)); },
      sendMessage: function () {
        var a = _apply(_slice, arguments, []);
        var cb = typeof a[a.length - 1] === 'function' ? a.pop() : null;
        var msg;
        if (a.length >= 3) msg = a[1];
        else if (a.length === 2) msg = (typeof a[0] === 'string' && /^[a-p]{32}$/.test(a[0])) ? a[1] : a[0];
        else msg = a[0];
        return inv('sendMessage', { msg: msg === undefined ? null : msg }, cb, msgValue);
      },
      connect: function (a, b) {
        var info = (a && typeof a === 'object') ? a : (b || {});
        var pid = FRAME_KEY + ':' + (++seq);
        var port = makePort(extId, pid, info.name, undefined);
        fire(extId, 'connect', { pid: pid, name: info.name || '', target: 'runtime' });
        return port;
      },
      onMessage: makeEvent(), onMessageExternal: makeEvent(), onConnect: makeEvent(), onConnectExternal: makeEvent(),
      onInstalled: makeEvent(), onStartup: makeEvent(), onSuspend: makeEvent(), onSuspendCanceled: makeEvent(),
      onUpdateAvailable: makeEvent(), onRestartRequired: makeEvent(), onUserScriptMessage: makeEvent(), onUserScriptConnect: makeEvent(),
      getPlatformInfo: function (cb) { return done({ os: 'android', arch: 'arm64', nacl_arch: 'arm' }, cb); },
      openOptionsPage: function (cb) { return inv('openOptionsPage', null, cb, noValue); },
      setUninstallURL: function (u, cb) { return done(undefined, cb); },
      reload: function () { fire(extId, 'reload', null); },
      requestUpdateCheck: function (cb) { return done({ status: 'no_update' }, cb); },
      getContexts: function (f, cb) { return done([], cb); },
      getBackgroundPage: function (cb) { return done(null, cb); },
      sendNativeMessage: function () { return fail('Native messaging не поддерживается', lastFn(arguments)); },
      connectNative: function () { throw new Error('Native messaging не поддерживается'); },
      getPackageDirectoryEntry: function (cb) { return fail('Не поддерживается', cb); },
      restart: function () {},
      restartAfterDelay: function (d, cb) { return done(undefined, cb); },
      OnInstalledReason: { INSTALL: 'install', UPDATE: 'update', CHROME_UPDATE: 'chrome_update', SHARED_MODULE_UPDATE: 'shared_module_update' },
      OnRestartRequiredReason: { APP_UPDATE: 'app_update', OS_UPDATE: 'os_update', PERIODIC: 'periodic' },
      PlatformOs: { ANDROID: 'android', CROS: 'cros', LINUX: 'linux', MAC: 'mac', OPENBSD: 'openbsd', WIN: 'win', FUCHSIA: 'fuchsia' },
      PlatformArch: { ARM: 'arm', ARM64: 'arm64', X86_32: 'x86-32', X86_64: 'x86-64', MIPS: 'mips', MIPS64: 'mips64' },
      ContextType: { TAB: 'TAB', POPUP: 'POPUP', BACKGROUND: 'BACKGROUND', OFFSCREEN_DOCUMENT: 'OFFSCREEN_DOCUMENT', SIDE_PANEL: 'SIDE_PANEL' }
    };
    _define(runtime, 'lastError', { get: function () { return ctx.getLastError(); }, enumerable: true });

    function area(name) {
      return {
        get: function (keys, cb) {
          if (typeof keys === 'function') { cb = keys; keys = null; }
          return inv('storage.get', { area: name, keys: keys === undefined ? null : keys }, cb);
        },
        set: function (items, cb) { return inv('storage.set', { area: name, items: items || {} }, cb, noValue); },
        remove: function (keys, cb) { return inv('storage.remove', { area: name, keys: _isArray(keys) ? keys : [keys] }, cb, noValue); },
        clear: function (cb) { return inv('storage.clear', { area: name }, cb, noValue); },
        getBytesInUse: function (keys, cb) {
          if (typeof keys === 'function') { cb = keys; keys = null; }
          return inv('storage.bytes', { area: name, keys: keys === undefined ? null : keys }, cb);
        },
        getKeys: function (cb) { return inv('storage.keys', { area: name }, cb); },
        setAccessLevel: function (o, cb) { return done(undefined, cb); },
        onChanged: makeEvent(),
        QUOTA_BYTES: name === 'sync' ? 102400 : 10485760,
        QUOTA_BYTES_PER_ITEM: 8192, MAX_ITEMS: 512,
        MAX_WRITE_OPERATIONS_PER_HOUR: 1800, MAX_WRITE_OPERATIONS_PER_MINUTE: 120
      };
    }
    var storage = {
      local: area('local'), sync: area('sync'), session: area('session'), managed: area('managed'),
      onChanged: makeEvent(),
      AccessLevel: { TRUSTED_CONTEXTS: 'TRUSTED_CONTEXTS', TRUSTED_AND_UNTRUSTED_CONTEXTS: 'TRUSTED_AND_UNTRUSTED_CONTEXTS' }
    };

    var i18n = {
      getMessage: function (name, subs) {
        if (name == null) return '';
        var key = String(name).toLowerCase();
        if (key === '@@extension_id') return extId;
        if (key === '@@ui_locale') return String(meta.uiLocale || 'en').replace('-', '_');
        if (key === '@@bidi_dir') return 'ltr';
        if (key === '@@bidi_reversed_dir') return 'rtl';
        if (key === '@@bidi_start_edge') return 'left';
        if (key === '@@bidi_end_edge') return 'right';
        var m = messages[key];
        if (!m) return '';
        var list = subs == null ? [] : (_isArray(subs) ? subs : [subs]);
        list = list.map(function (s) { return String(s); });
        var sub = function (all, d) { var i = (+d) - 1; return (i >= 0 && i < list.length) ? list[i] : ''; };
        var ph = m.placeholders || {};
        var out = String(m.message || '').replace(/\$([a-zA-Z0-9_@]+)\$/g, function (all, p) {
          var e = ph[p.toLowerCase()];
          if (!e) return all;
          return String(e.content || '').replace(/\$(\d)/g, sub);
        });
        return out.replace(/\$(\d)/g, sub).replace(/\$\$/g, '$');
      },
      getUILanguage: function () { return String(meta.uiLocale || 'en').replace('_', '-'); },
      getAcceptLanguages: function (cb) { return done([String(meta.uiLocale || 'en').replace('_', '-'), 'en'], cb); },
      detectLanguage: function (t, cb) { return done({ isReliable: false, languages: [] }, cb); }
    };

    var extension = {
      getURL: runtime.getURL,
      inIncognitoContext: false,
      getBackgroundPage: function () { return null; },
      getViews: function () { return []; },
      isAllowedIncognitoAccess: function (cb) { return done(false, cb); },
      isAllowedFileSchemeAccess: function (cb) { return done(false, cb); },
      sendMessage: runtime.sendMessage, sendRequest: runtime.sendMessage,
      onMessage: runtime.onMessage, onRequest: runtime.onMessage, onConnect: runtime.onConnect,
      setUpdateUrlData: function () {},
      ViewType: { TAB: 'tab', POPUP: 'popup' }
    };
    _define(extension, 'lastError', { get: function () { return ctx.getLastError(); }, enumerable: true });

    var dom = { openOrClosedShadowRoot: function (el) { return (el && el.shadowRoot) || null; } };

    var api = { runtime: runtime, storage: storage, i18n: i18n, extension: extension, dom: dom };
    if (!isPage) return wrapUnknown(api);

    var tabs = {
      query: function (q, cb) { return inv('tabs.query', q || {}, cb); },
      get: function (id, cb) { return inv('tabs.get', { tabId: id }, cb); },
      getCurrent: function (cb) { return inv('tabs.getCurrent', null, cb, function (r) { return r || undefined; }); },
      create: function (p, cb) { return inv('tabs.create', p || {}, cb); },
      update: function (a, b, cb) {
        var id = a, props = b;
        if (a !== null && typeof a === 'object') { cb = b; props = a; id = null; }
        return inv('tabs.update', { tabId: id == null ? null : id, props: props || {} }, cb);
      },
      remove: function (ids, cb) { return inv('tabs.remove', { tabIds: _isArray(ids) ? ids : [ids] }, cb, noValue); },
      reload: function (a, b, cb) {
        var id = a;
        if (typeof a === 'function') { cb = a; id = null; }
        else if (a !== null && typeof a === 'object') { cb = b; id = null; }
        else if (typeof b === 'function') { cb = b; }
        return inv('tabs.reload', { tabId: id == null ? null : id }, cb, noValue);
      },
      duplicate: function (id, cb) { return inv('tabs.duplicate', { tabId: id }, cb); },
      sendMessage: function (tabId, msg, opts, cb) {
        if (typeof opts === 'function') { cb = opts; opts = null; }
        return inv('tabs.sendMessage', { tabId: tabId, msg: msg === undefined ? null : msg, frameId: (opts && opts.frameId != null) ? opts.frameId : null }, cb, msgValue);
      },
      connect: function (tabId, info) {
        info = info || {};
        var pid = FRAME_KEY + ':' + (++seq);
        var port = makePort(extId, pid, info.name, undefined);
        fire(extId, 'connect', { pid: pid, name: info.name || '', target: 'tab', tabId: tabId, frameId: info.frameId != null ? info.frameId : null });
        return port;
      },
      executeScript: function (a, b, cb) {
        var id = a, d = b;
        if (a !== null && typeof a === 'object') { cb = b; d = a; id = null; }
        d = d || {};
        return inv('scripting.executeScript', { target: { tabId: id == null ? null : id }, files: d.file ? [d.file] : null, code: d.code || null }, cb,
          function (r) { return (r || []).map(function (x) { return x.result; }); });
      },
      insertCSS: function (a, b, cb) {
        var id = a, d = b;
        if (a !== null && typeof a === 'object') { cb = b; d = a; id = null; }
        d = d || {};
        return inv('scripting.insertCSS', { target: { tabId: id == null ? null : id }, files: d.file ? [d.file] : null, css: d.code || null }, cb, noValue);
      },
      removeCSS: function (a, b, cb) {
        var id = a, d = b;
        if (a !== null && typeof a === 'object') { cb = b; d = a; id = null; }
        d = d || {};
        return inv('scripting.removeCSS', { target: { tabId: id == null ? null : id }, files: d.file ? [d.file] : null, css: d.code || null }, cb, noValue);
      },
      captureVisibleTab: function () { return fail('captureVisibleTab не поддерживается', lastFn(arguments)); },
      detectLanguage: function (a, cb) { if (typeof a === 'function') cb = a; return done('und', cb); },
      getZoom: function (a, cb) { if (typeof a === 'function') cb = a; return done(1, cb); },
      setZoom: function () { return done(undefined, lastFn(arguments)); },
      getZoomSettings: function (a, cb) { if (typeof a === 'function') cb = a; return done({ mode: 'automatic', scope: 'per-origin', defaultZoomFactor: 1 }, cb); },
      setZoomSettings: function () { return done(undefined, lastFn(arguments)); },
      highlight: function (i, cb) { return done(undefined, cb); },
      move: function (i, p, cb) { return done(undefined, cb); },
      discard: function (i, cb) { return done(undefined, cb); },
      goBack: function () { return done(undefined, lastFn(arguments)); },
      goForward: function () { return done(undefined, lastFn(arguments)); },
      group: function (o, cb) { return done(-1, cb); },
      ungroup: function (o, cb) { return done(undefined, cb); },
      onUpdated: makeEvent(), onActivated: makeEvent(), onRemoved: makeEvent(), onCreated: makeEvent(),
      onReplaced: makeEvent(), onHighlighted: makeEvent(), onMoved: makeEvent(), onAttached: makeEvent(),
      onDetached: makeEvent(), onZoomChange: makeEvent(), onSelectionChanged: makeEvent(), onActiveChanged: makeEvent(),
      TAB_ID_NONE: -1, TAB_INDEX_NONE: -1, MAX_CAPTURE_VISIBLE_TAB_CALLS_PER_SECOND: 2,
      TabStatus: { LOADING: 'loading', COMPLETE: 'complete', UNLOADED: 'unloaded' },
      MutedInfoReason: { USER: 'user', CAPTURE: 'capture', EXTENSION: 'extension' },
      WindowType: { NORMAL: 'normal', POPUP: 'popup', PANEL: 'panel', APP: 'app', DEVTOOLS: 'devtools' }
    };

    function winInfo(q) { return { populate: !!(q && q.populate) }; }
    var windows = {
      get: function (id, q, cb) { if (typeof q === 'function') { cb = q; q = null; } return inv('windows.get', winInfo(q), cb); },
      getCurrent: function (q, cb) { if (typeof q === 'function') { cb = q; q = null; } return inv('windows.get', winInfo(q), cb); },
      getLastFocused: function (q, cb) { if (typeof q === 'function') { cb = q; q = null; } return inv('windows.get', winInfo(q), cb); },
      getAll: function (q, cb) { if (typeof q === 'function') { cb = q; q = null; } return inv('windows.get', winInfo(q), cb, function (w) { return [w]; }); },
      create: function (d, cb) {
        if (typeof d === 'function') { cb = d; d = {}; }
        d = d || {};
        var url = _isArray(d.url) ? d.url[0] : d.url;
        return inv('tabs.create', { url: url, active: true }, cb, function (t) {
          return { id: 1, focused: true, top: 0, left: 0, tabs: [t], type: 'normal', state: 'normal', incognito: false, alwaysOnTop: false };
        });
      },
      update: function (id, info, cb) { return inv('windows.get', winInfo(null), cb); },
      remove: function (id, cb) { return done(undefined, cb); },
      onCreated: makeEvent(), onRemoved: makeEvent(), onFocusChanged: makeEvent(), onBoundsChanged: makeEvent(),
      WINDOW_ID_NONE: -1, WINDOW_ID_CURRENT: -2,
      WindowType: { NORMAL: 'normal', POPUP: 'popup', PANEL: 'panel', APP: 'app', DEVTOOLS: 'devtools' },
      WindowState: { NORMAL: 'normal', MINIMIZED: 'minimized', MAXIMIZED: 'maximized', FULLSCREEN: 'fullscreen', LOCKED_FULLSCREEN: 'locked-fullscreen' },
      CreateType: { NORMAL: 'normal', POPUP: 'popup', PANEL: 'panel' }
    };

    function tabArg(d) { return (d && d.tabId != null) ? d.tabId : null; }
    var action = {
      setBadgeText: function (d, cb) { d = d || {}; return inv('action.set', { k: 'badgeText', v: d.text == null ? '' : String(d.text), tabId: tabArg(d) }, cb, noValue); },
      getBadgeText: function (d, cb) { if (typeof d === 'function') { cb = d; d = null; } return inv('action.get', { k: 'badgeText', tabId: tabArg(d) }, cb); },
      setBadgeBackgroundColor: function (d, cb) { d = d || {}; return inv('action.set', { k: 'badgeBg', v: colorToCss(d.color), tabId: tabArg(d) }, cb, noValue); },
      getBadgeBackgroundColor: function (d, cb) { if (typeof d === 'function') cb = d; return done([0, 0, 0, 0], cb); },
      setBadgeTextColor: function (d, cb) { return done(undefined, cb); },
      getBadgeTextColor: function (d, cb) { if (typeof d === 'function') cb = d; return done([255, 255, 255, 255], cb); },
      setTitle: function (d, cb) { d = d || {}; return inv('action.set', { k: 'title', v: String(d.title || ''), tabId: tabArg(d) }, cb, noValue); },
      getTitle: function (d, cb) { if (typeof d === 'function') { cb = d; d = null; } return inv('action.get', { k: 'title', tabId: tabArg(d) }, cb); },
      setIcon: function (d, cb) { return done(undefined, cb); },
      setPopup: function (d, cb) { d = d || {}; return inv('action.set', { k: 'popup', v: String(d.popup || ''), tabId: tabArg(d) }, cb, noValue); },
      getPopup: function (d, cb) { if (typeof d === 'function') { cb = d; d = null; } return inv('action.get', { k: 'popup', tabId: tabArg(d) }, cb); },
      enable: function (t, cb) { if (typeof t === 'function') cb = t; return done(undefined, cb); },
      disable: function (t, cb) { if (typeof t === 'function') cb = t; return done(undefined, cb); },
      isEnabled: function (t, cb) { if (typeof t === 'function') cb = t; return done(true, cb); },
      getUserSettings: function (cb) { return done({ isOnToolbar: true }, cb); },
      openPopup: function (o, cb) { if (typeof o === 'function') cb = o; return inv('action.openPopup', null, cb, noValue); },
      show: function (t, cb) { return done(undefined, cb); },
      hide: function (t, cb) { return done(undefined, cb); },
      onClicked: makeEvent(), onUserSettingsChanged: makeEvent()
    };

    var scripting = {
      executeScript: function (inj, cb) {
        inj = inj || {};
        var fn = inj.func || inj['function'];
        return inv('scripting.executeScript', { target: inj.target || {}, files: inj.files || null, func: fn ? String(fn) : null, args: inj.args || [], world: inj.world || 'ISOLATED' }, cb);
      },
      insertCSS: function (inj, cb) { inj = inj || {}; return inv('scripting.insertCSS', { target: inj.target || {}, files: inj.files || null, css: inj.css || null }, cb, noValue); },
      removeCSS: function (inj, cb) { inj = inj || {}; return inv('scripting.removeCSS', { target: inj.target || {}, files: inj.files || null, css: inj.css || null }, cb, noValue); },
      registerContentScripts: function (s, cb) { return inv('scripting.register', { scripts: s || [] }, cb, noValue); },
      updateContentScripts: function (s, cb) { return inv('scripting.update', { scripts: s || [] }, cb, noValue); },
      unregisterContentScripts: function (f, cb) { if (typeof f === 'function') { cb = f; f = null; } return inv('scripting.unregister', { ids: (f && f.ids) ? f.ids : null }, cb, noValue); },
      getRegisteredContentScripts: function (f, cb) { if (typeof f === 'function') { cb = f; f = null; } return inv('scripting.getRegistered', { ids: (f && f.ids) ? f.ids : null }, cb); },
      ExecutionWorld: { ISOLATED: 'ISOLATED', MAIN: 'MAIN' },
      StyleOrigin: { AUTHOR: 'AUTHOR', USER: 'USER' }
    };

    var dnr = {
      updateDynamicRules: function (o, cb) { o = o || {}; return inv('dnr.update', { scope: 'dynamic', add: o.addRules || [], remove: o.removeRuleIds || [] }, cb, noValue); },
      getDynamicRules: function (f, cb) { if (typeof f === 'function') { cb = f; f = null; } return inv('dnr.get', { scope: 'dynamic', ids: (f && f.ruleIds) ? f.ruleIds : null }, cb); },
      updateSessionRules: function (o, cb) { o = o || {}; return inv('dnr.update', { scope: 'session', add: o.addRules || [], remove: o.removeRuleIds || [] }, cb, noValue); },
      getSessionRules: function (f, cb) { if (typeof f === 'function') { cb = f; f = null; } return inv('dnr.get', { scope: 'session', ids: (f && f.ruleIds) ? f.ruleIds : null }, cb); },
      updateEnabledRulesets: function (o, cb) { o = o || {}; return inv('dnr.rulesets', { enable: o.enableRulesetIds || [], disable: o.disableRulesetIds || [] }, cb, noValue); },
      getEnabledRulesets: function (cb) { return inv('dnr.getRulesets', null, cb); },
      updateStaticRules: function (o, cb) { o = o || {}; return inv('dnr.static', { rulesetId: o.rulesetId, enable: o.enableRuleIds || [], disable: o.disableRuleIds || [] }, cb, noValue); },
      getDisabledRuleIds: function (o, cb) { return inv('dnr.getDisabled', { rulesetId: o && o.rulesetId }, cb); },
      getAvailableStaticRuleCount: function (cb) { return done(300000, cb); },
      isRegexSupported: function (o, cb) {
        var ok = true;
        try { new _RegExp((o && o.regex) || ''); } catch (e) { ok = false; }
        return done(ok ? { isSupported: true } : { isSupported: false, reason: 'syntaxError' }, cb);
      },
      setExtensionActionOptions: function (o, cb) { return done(undefined, cb); },
      getMatchedRules: function (f, cb) { if (typeof f === 'function') cb = f; return done({ rulesMatchedInfo: [] }, cb); },
      testMatchOutcome: function (r, cb) { return done({ matchedRules: [] }, cb); },
      onRuleMatchedDebug: makeEvent(),
      MAX_NUMBER_OF_DYNAMIC_RULES: 30000, MAX_NUMBER_OF_UNSAFE_DYNAMIC_RULES: 5000,
      MAX_NUMBER_OF_SESSION_RULES: 5000, MAX_NUMBER_OF_UNSAFE_SESSION_RULES: 5000,
      MAX_NUMBER_OF_DYNAMIC_AND_SESSION_RULES: 5000, MAX_NUMBER_OF_REGEX_RULES: 1000,
      GUARANTEED_MINIMUM_STATIC_RULES: 30000, MAX_NUMBER_OF_STATIC_RULESETS: 100, MAX_NUMBER_OF_ENABLED_STATIC_RULESETS: 50,
      DYNAMIC_RULESET_ID: '_dynamic', SESSION_RULESET_ID: '_session',
      MAX_GETMATCHEDRULES_CALLS_PER_INTERVAL: 20, GETMATCHEDRULES_QUOTA_INTERVAL: 10,
      RuleActionType: { BLOCK: 'block', REDIRECT: 'redirect', ALLOW: 'allow', UPGRADE_SCHEME: 'upgradeScheme', MODIFY_HEADERS: 'modifyHeaders', ALLOW_ALL_REQUESTS: 'allowAllRequests' },
      ResourceType: { MAIN_FRAME: 'main_frame', SUB_FRAME: 'sub_frame', STYLESHEET: 'stylesheet', SCRIPT: 'script', IMAGE: 'image', FONT: 'font', OBJECT: 'object', XMLHTTPREQUEST: 'xmlhttprequest', PING: 'ping', CSP_REPORT: 'csp_report', MEDIA: 'media', WEBSOCKET: 'websocket', WEBTRANSPORT: 'webtransport', WEBBUNDLE: 'webbundle', OTHER: 'other' },
      DomainType: { FIRST_PARTY: 'firstParty', THIRD_PARTY: 'thirdParty' },
      RequestMethod: { CONNECT: 'connect', DELETE: 'delete', GET: 'get', HEAD: 'head', OPTIONS: 'options', PATCH: 'patch', POST: 'post', PUT: 'put', OTHER: 'other' },
      HeaderOperation: { APPEND: 'append', SET: 'set', REMOVE: 'remove' },
      UnsupportedRegexReason: { SYNTAX_ERROR: 'syntaxError', MEMORY_LIMIT_EXCEEDED: 'memoryLimitExceeded' }
    };

    var alarms = (function () {
      var map = {}, onAlarm = makeEvent();
      function info(a) { return { name: a.name, scheduledTime: a.scheduledTime, periodInMinutes: a.periodInMinutes }; }
      function schedule(a) {
        a.timer = _setTimeout(function tick() {
          onAlarm._fire(info(a));
          if (a.periodInMinutes) {
            a.scheduledTime = Date.now() + a.periodInMinutes * 60000;
            a.timer = _setTimeout(tick, a.periodInMinutes * 60000);
          } else if (map[a.name] === a) {
            delete map[a.name];
          }
        }, Math.max(0, a.scheduledTime - Date.now()));
      }
      return {
        create: function (name, inf, cb) {
          if (name !== null && typeof name === 'object') { cb = inf; inf = name; name = ''; }
          name = name || ''; inf = inf || {};
          if (map[name]) _clearTimeout(map[name].timer);
          var delay = inf.delayInMinutes != null ? inf.delayInMinutes : (inf.periodInMinutes != null ? inf.periodInMinutes : 0);
          var a = { name: name, scheduledTime: inf.when != null ? inf.when : Date.now() + delay * 60000, periodInMinutes: inf.periodInMinutes };
          map[name] = a;
          schedule(a);
          return done(undefined, cb);
        },
        get: function (name, cb) { if (typeof name === 'function') { cb = name; name = ''; } var a = map[name || '']; return done(a ? info(a) : undefined, cb); },
        getAll: function (cb) { return done(_keys(map).map(function (k) { return info(map[k]); }), cb); },
        clear: function (name, cb) {
          if (typeof name === 'function') { cb = name; name = ''; }
          var a = map[name || ''];
          if (a) { _clearTimeout(a.timer); delete map[name || '']; }
          return done(!!a, cb);
        },
        clearAll: function (cb) { _keys(map).forEach(function (k) { _clearTimeout(map[k].timer); delete map[k]; }); return done(true, cb); },
        onAlarm: onAlarm
      };
    })();

    var allPerms = (manifest.permissions || []).concat(manifest.optional_permissions || []);
    var permissions = {
      contains: function (p, cb) { return done(true, cb); },
      request: function (p, cb) { return done(true, cb); },
      remove: function (p, cb) { return done(true, cb); },
      getAll: function (cb) {
        return done({
          permissions: allPerms.filter(function (x) { return String(x).indexOf('://') < 0 && x !== '<all_urls>'; }),
          origins: (manifest.host_permissions || []).concat(manifest.optional_host_permissions || [])
            .concat(allPerms.filter(function (x) { return String(x).indexOf('://') >= 0 || x === '<all_urls>'; }))
        }, cb);
      },
      addHostAccessRequest: function (o, cb) { return done(undefined, cb); },
      removeHostAccessRequest: function (o, cb) { return done(undefined, cb); },
      onAdded: makeEvent(), onRemoved: makeEvent()
    };

    var menuSeq = 0;
    var contextMenus = {
      create: function (p, cb) { var id = (p && p.id != null) ? p.id : ++menuSeq; if (typeof cb === 'function') _setTimeout(cb, 0); return id; },
      update: function (id, p, cb) { return done(undefined, cb); },
      remove: function (id, cb) { return done(undefined, cb); },
      removeAll: function (cb) { return done(undefined, cb); },
      onClicked: makeEvent(), onShown: makeEvent(), onHidden: makeEvent(),
      ACTION_MENU_TOP_LEVEL_LIMIT: 6,
      ContextType: { ALL: 'all', PAGE: 'page', FRAME: 'frame', SELECTION: 'selection', LINK: 'link', EDITABLE: 'editable', IMAGE: 'image', VIDEO: 'video', AUDIO: 'audio', LAUNCHER: 'launcher', BROWSER_ACTION: 'browser_action', PAGE_ACTION: 'page_action', ACTION: 'action' },
      ItemType: { NORMAL: 'normal', CHECKBOX: 'checkbox', RADIO: 'radio', SEPARATOR: 'separator' }
    };

    var notifications = {
      create: function (a, b, cb) {
        var id = a, o = b;
        if (a !== null && typeof a === 'object') { cb = b; o = a; id = ''; }
        if (!id) id = 'n' + (++seq);
        return inv('notify', { title: (o && o.title) || '', message: (o && o.message) || '' }, cb, function () { return id; });
      },
      update: function (id, o, cb) { return done(true, cb); },
      clear: function (id, cb) { return done(true, cb); },
      getAll: function (cb) { return done({}, cb); },
      getPermissionLevel: function (cb) { return done('granted', cb); },
      onClicked: makeEvent(), onClosed: makeEvent(), onButtonClicked: makeEvent(), onShowSettings: makeEvent(), onPermissionLevelChanged: makeEvent(),
      TemplateType: { BASIC: 'basic', IMAGE: 'image', LIST: 'list', PROGRESS: 'progress' },
      PermissionLevel: { GRANTED: 'granted', DENIED: 'denied' }
    };

    var cookies = {
      get: function (d, cb) { return inv('cookies.get', d || {}, cb); },
      getAll: function (d, cb) { return inv('cookies.getAll', d || {}, cb); },
      set: function (d, cb) { return inv('cookies.set', d || {}, cb); },
      remove: function (d, cb) { return inv('cookies.remove', d || {}, cb); },
      getAllCookieStores: function (cb) { return done([{ id: '0', tabIds: [] }], cb); },
      onChanged: makeEvent(),
      SameSiteStatus: { NO_RESTRICTION: 'no_restriction', LAX: 'lax', STRICT: 'strict', UNSPECIFIED: 'unspecified' },
      OnChangedCause: { EVICTED: 'evicted', EXPIRED: 'expired', EXPLICIT: 'explicit', EXPIRED_OVERWRITE: 'expired_overwrite', OVERWRITE: 'overwrite' }
    };

    var downloads = {
      download: function (o, cb) { return inv('downloads.download', o || {}, cb); },
      search: function (q, cb) { return done([], cb); },
      pause: function (i, cb) { return done(undefined, cb); },
      resume: function (i, cb) { return done(undefined, cb); },
      cancel: function (i, cb) { return done(undefined, cb); },
      erase: function (q, cb) { return done([], cb); },
      open: function () {}, show: function () {}, showDefaultFolder: function () {},
      getFileIcon: function (id, o, cb) { if (typeof o === 'function') cb = o; return done('', cb); },
      setShelfEnabled: function () {}, setUiOptions: function (o, cb) { return done(undefined, cb); },
      onCreated: makeEvent(), onChanged: makeEvent(), onErased: makeEvent(), onDeterminingFilename: makeEvent()
    };

    var webNavigation = {
      getFrame: function (d, cb) { return inv('webNav.getFrame', d || {}, cb); },
      getAllFrames: function (d, cb) { return inv('webNav.getAllFrames', d || {}, cb); },
      onBeforeNavigate: makeEvent(), onCommitted: makeEvent(), onDOMContentLoaded: makeEvent(), onCompleted: makeEvent(),
      onErrorOccurred: makeEvent(), onCreatedNavigationTarget: makeEvent(), onReferenceFragmentUpdated: makeEvent(),
      onTabReplaced: makeEvent(), onHistoryStateUpdated: makeEvent()
    };

    var webRequest = {
      onBeforeRequest: makeEvent(), onBeforeSendHeaders: makeEvent(), onSendHeaders: makeEvent(), onHeadersReceived: makeEvent(),
      onAuthRequired: makeEvent(), onResponseStarted: makeEvent(), onBeforeRedirect: makeEvent(), onCompleted: makeEvent(),
      onErrorOccurred: makeEvent(), onActionIgnored: makeEvent(),
      handlerBehaviorChanged: function (cb) { return done(undefined, cb); },
      MAX_HANDLER_BEHAVIOR_CHANGED_CALLS_PER_10_MINUTES: 20,
      OnBeforeRequestOptions: { BLOCKING: 'blocking', REQUEST_BODY: 'requestBody', EXTRA_HEADERS: 'extraHeaders' },
      OnBeforeSendHeadersOptions: { REQUEST_HEADERS: 'requestHeaders', BLOCKING: 'blocking', EXTRA_HEADERS: 'extraHeaders' },
      OnSendHeadersOptions: { REQUEST_HEADERS: 'requestHeaders', EXTRA_HEADERS: 'extraHeaders' },
      OnHeadersReceivedOptions: { BLOCKING: 'blocking', RESPONSE_HEADERS: 'responseHeaders', EXTRA_HEADERS: 'extraHeaders' },
      OnAuthRequiredOptions: { RESPONSE_HEADERS: 'responseHeaders', BLOCKING: 'blocking', ASYNC_BLOCKING: 'asyncBlocking', EXTRA_HEADERS: 'extraHeaders' },
      OnResponseStartedOptions: { RESPONSE_HEADERS: 'responseHeaders', EXTRA_HEADERS: 'extraHeaders' },
      OnCompletedOptions: { RESPONSE_HEADERS: 'responseHeaders', EXTRA_HEADERS: 'extraHeaders' },
      ResourceType: dnr.ResourceType
    };

    var offscreen = {
      createDocument: function (p, cb) { return inv('offscreen.create', { url: p && p.url }, cb, noValue); },
      closeDocument: function (cb) { return inv('offscreen.close', null, cb, noValue); },
      hasDocument: function (cb) { return inv('offscreen.has', null, cb); },
      Reason: { TESTING: 'TESTING', AUDIO_PLAYBACK: 'AUDIO_PLAYBACK', IFRAME_SCRIPTING: 'IFRAME_SCRIPTING', DOM_SCRAPING: 'DOM_SCRAPING', BLOBS: 'BLOBS', DOM_PARSER: 'DOM_PARSER', USER_MEDIA: 'USER_MEDIA', DISPLAY_MEDIA: 'DISPLAY_MEDIA', WEB_RTC: 'WEB_RTC', CLIPBOARD: 'CLIPBOARD', LOCAL_STORAGE: 'LOCAL_STORAGE', WORKERS: 'WORKERS', BATTERY_STATUS: 'BATTERY_STATUS', MATCH_MEDIA: 'MATCH_MEDIA', GEOLOCATION: 'GEOLOCATION' }
    };

    var self = {
      id: extId, name: manifest.name || '', shortName: manifest.short_name || manifest.name || '', version: manifest.version || '',
      description: manifest.description || '', enabled: true, mayDisable: true, installType: 'normal', type: 'extension',
      offlineEnabled: false, optionsUrl: meta.optionsUrl || '', permissions: manifest.permissions || [], hostPermissions: manifest.host_permissions || [], isApp: false
    };
    var management = {
      getSelf: function (cb) { return done(self, cb); },
      getAll: function (cb) { return done([self], cb); },
      get: function (id, cb) { return done(self, cb); },
      setEnabled: function (id, e, cb) { return done(undefined, cb); },
      uninstallSelf: function (o, cb) { return done(undefined, typeof o === 'function' ? o : cb); },
      onInstalled: makeEvent(), onUninstalled: makeEvent(), onEnabled: makeEvent(), onDisabled: makeEvent(),
      ExtensionInstallType: { ADMIN: 'admin', DEVELOPMENT: 'development', NORMAL: 'normal', SIDELOAD: 'sideload', OTHER: 'other' }
    };

    var sidePanel = {
      setOptions: function (o, cb) { return done(undefined, cb); },
      getOptions: function (o, cb) { return done({ enabled: false }, cb); },
      setPanelBehavior: function (o, cb) { return done(undefined, cb); },
      getPanelBehavior: function (cb) { return done({ openPanelOnActionClick: false }, cb); },
      open: function (o, cb) { return fail('Боковая панель не поддерживается', cb); }
    };
    var idle = {
      queryState: function (s, cb) { return done('active', cb); },
      setDetectionInterval: function () {},
      getAutoLockDelay: function (cb) { return done(0, cb); },
      onStateChanged: makeEvent(),
      IdleState: { ACTIVE: 'active', IDLE: 'idle', LOCKED: 'locked' }
    };
    var tts = {
      speak: function (t, o, cb) { if (typeof o === 'function') cb = o; return done(undefined, cb); },
      stop: function () {}, pause: function () {}, resume: function () {},
      isSpeaking: function (cb) { return done(false, cb); },
      getVoices: function (cb) { return done([], cb); },
      onVoicesChanged: makeEvent()
    };
    var commands = { getAll: function (cb) { return done([], cb); }, onCommand: makeEvent() };

    var proxyError = makeEvent();
    var proxy = {
      settings: {
        get: function (d, cb) { if (typeof d === 'function') { cb = d; d = null; } return inv('proxy.get', d || {}, cb); },
        set: function (d, cb) { return inv('proxy.set', d || {}, cb, noValue); },
        clear: function (d, cb) { if (typeof d === 'function') { cb = d; d = null; } return inv('proxy.clear', d || {}, cb, noValue); },
        onChange: makeEvent()
      },
      onProxyError: proxyError,
      onError: proxyError,
      Scope: { REGULAR: 'regular', REGULAR_ONLY: 'regular_only', INCOGNITO_PERSISTENT: 'incognito_persistent', INCOGNITO_SESSION_ONLY: 'incognito_session_only' },
      Mode: { DIRECT: 'direct', AUTO_DETECT: 'auto_detect', PAC_SCRIPT: 'pac_script', FIXED_SERVERS: 'fixed_servers', SYSTEM: 'system' }
    };

    function localSetting(def) {
      var value = def, mine = false, ev = makeEvent();
      function state() { return { value: value, levelOfControl: mine ? 'controlled_by_this_extension' : 'controllable_by_this_extension' }; }
      return {
        get: function (d, cb) { if (typeof d === 'function') cb = d; return done(state(), cb); },
        set: function (d, cb) { if (d && ('value' in d)) value = d.value; mine = true; ev._fire(state()); return done(undefined, cb); },
        clear: function (d, cb) { if (typeof d === 'function') cb = d; value = def; mine = false; ev._fire(state()); return done(undefined, cb); },
        onChange: ev
      };
    }
    var privacy = {
      network: {
        networkPredictionEnabled: localSetting(true), webRTCIPHandlingPolicy: localSetting('default'),
        webRTCMultipleRoutesEnabled: localSetting(true), webRTCNonProxiedUdpEnabled: localSetting(true)
      },
      services: {
        alternateErrorPagesEnabled: localSetting(true), autofillEnabled: localSetting(true), autofillAddressEnabled: localSetting(true),
        autofillCreditCardEnabled: localSetting(true), passwordSavingEnabled: localSetting(true), safeBrowsingEnabled: localSetting(true),
        safeBrowsingExtendedReportingEnabled: localSetting(false), searchSuggestEnabled: localSetting(true),
        spellingServiceEnabled: localSetting(false), translationServiceEnabled: localSetting(true)
      },
      websites: {
        thirdPartyCookiesAllowed: localSetting(true), hyperlinkAuditingEnabled: localSetting(true), referrersEnabled: localSetting(true),
        doNotTrackEnabled: localSetting(false), protectedContentEnabled: localSetting(true), topicsEnabled: localSetting(false),
        fledgeEnabled: localSetting(false), adMeasurementEnabled: localSetting(false), relatedWebsiteSetsEnabled: localSetting(false)
      },
      IPHandlingPolicy: {
        DEFAULT: 'default', DEFAULT_PUBLIC_AND_PRIVATE_INTERFACES: 'default_public_and_private_interfaces',
        DEFAULT_PUBLIC_INTERFACE_ONLY: 'default_public_interface_only', DISABLE_NON_PROXIED_UDP: 'disable_non_proxied_udp'
      }
    };

    function bdRemover(types) { return function (o, cb) { return inv('browsingData.remove', { options: o || {}, types: types }, cb, noValue); }; }
    var browsingData = {
      remove: function (o, t, cb) { return inv('browsingData.remove', { options: o || {}, types: t || {} }, cb, noValue); },
      removeAppcache: bdRemover({ appcache: true }), removeCache: bdRemover({ cache: true }), removeCacheStorage: bdRemover({ cacheStorage: true }),
      removeCookies: bdRemover({ cookies: true }), removeDownloads: bdRemover({ downloads: true }), removeFileSystems: bdRemover({ fileSystems: true }),
      removeFormData: bdRemover({ formData: true }), removeHistory: bdRemover({ history: true }), removeIndexedDB: bdRemover({ indexedDB: true }),
      removeLocalStorage: bdRemover({ localStorage: true }), removePasswords: bdRemover({ passwords: true }), removePluginData: bdRemover({ pluginData: true }),
      removeServiceWorkers: bdRemover({ serviceWorkers: true }), removeWebSQL: bdRemover({ webSQL: true }),
      settings: function (cb) {
        return done({ options: { since: 0 }, dataToRemove: {}, dataRemovalPermitted: { appcache: true, cache: true, cacheStorage: true, cookies: true,
          downloads: true, fileSystems: true, formData: true, history: true, indexedDB: true, localStorage: true, passwords: true,
          pluginData: true, serviceWorkers: true, webSQL: true } }, cb);
      }
    };

    if (isPage) { api.proxy = proxy; api.privacy = privacy; api.browsingData = browsingData; }
    api.tabs = tabs; api.windows = windows; api.action = action; api.scripting = scripting;
    api.declarativeNetRequest = dnr; api.alarms = alarms; api.permissions = permissions;
    api.contextMenus = contextMenus; api.menus = contextMenus; api.notifications = notifications;
    api.cookies = cookies; api.downloads = downloads; api.webNavigation = webNavigation;
    api.webRequest = webRequest; api.commands = commands; api.offscreen = offscreen;
    api.management = management; api.sidePanel = sidePanel; api.idle = idle; api.tts = tts;
    if ((manifest.manifest_version | 0) < 3) {
      api.browserAction = action;
      api.pageAction = action;
    }
    return wrapUnknown(api, allPerms, manifest);
  }

  /* Как в Chrome: API без разрешения в манифесте отсутствует (undefined), иначе обёртки расширений ломаются. */
  function wrapUnknown(api, perms, manifest) {
    function permitted(k) {
      if (k === 'devtools') return !!manifest.devtools_page;
      for (var i = 0; i < perms.length; i++) {
        var p = String(perms[i]);
        if (p === k || p.indexOf(k + '.') === 0) return true;
      }
      return false;
    }
    return new Proxy(api, {
      get: function (t, k) {
        if (k in t) return t[k];
        if (typeof k === 'string' && KNOWN_NOOP[k] && permitted(k)) { t[k] = deepNoop(); return t[k]; }
        return undefined;
      },
      has: function (t, k) {
        if (k in t) return true;
        return typeof k === 'string' && !!KNOWN_NOOP[k] && permitted(k);
      }
    });
  }

  /* ---------------- Контент-скрипты во вкладках ---------------- */

  function hostMatch(e, scheme, host) {
    if (e.all && (scheme === 'http:' || scheme === 'https:')) return true;
    if (e.hs) {
      var h = host;
      if (e.hx && e.hx[h]) return true;
      while (h) {
        if (e.hs[h]) return true;
        var dot = h.indexOf('.');
        if (dot < 0) break;
        h = h.substring(dot + 1);
      }
    } else if (e.hx && e.hx[host]) {
      return true;
    }
    return false;
  }

  function testList(list, url) {
    if (!list) return false;
    for (var i = 0; i < list.length; i++) {
      try { if (new _RegExp(list[i]).test(url)) return true; } catch (er) {}
    }
    return false;
  }

  function entryMatches(e, href) {
    var url = href.split('#')[0];
    var loc = location;
    if (/^about:/.test(url)) {
      if (!e.mab) return false;
      try { loc = W.parent.location; url = String(loc.href).split('#')[0]; } catch (er) { return false; }
    }
    var scheme = String(loc.protocol), host = String(loc.hostname).toLowerCase();
    if (!hostMatch(e, scheme, host) && !testList(e.m, url)) return false;
    if (e.x && e.x.length && testList(e.x, url)) return false;
    if (e.ig && e.ig.length && !testList(e.ig, url)) return false;
    if (e.eg && e.eg.length && testList(e.eg, url)) return false;
    return true;
  }

  function addStyle(extId, cssText, key) {
    if (!cssText || sheets[key]) return;
    var rec = {};
    sheets[key] = rec;
    try {
      if (W.CSSStyleSheet && document.adoptedStyleSheets) {
        var sh = new CSSStyleSheet();
        sh.replaceSync(cssText);
        rec.sheet = sh;
        document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sh]);
        return;
      }
    } catch (er) {}
    var put = function () {
      if (rec.el) return;
      var el = document.createElement('style');
      el.textContent = cssText;
      el.setAttribute('data-omni-ext', extId);
      (document.head || document.documentElement).appendChild(el);
      rec.el = el;
    };
    if (document.documentElement) put(); else _addDocListener('readystatechange', put, { once: true });
  }

  function removeStyle(key) {
    var rec = sheets[key];
    if (!rec) return;
    delete sheets[key];
    try {
      if (rec.sheet) document.adoptedStyleSheets = document.adoptedStyleSheets.filter(function (s) { return s !== rec.sheet; });
      if (rec.el && rec.el.parentNode) rec.el.parentNode.removeChild(rec.el);
    } catch (er) {}
  }

  function schedule(runAt, fn) {
    if (runAt === 'document_start') { fn(); return; }
    var rs = document.readyState;
    if (runAt === 'document_end') {
      if (rs === 'loading') _addDocListener('DOMContentLoaded', fn, { once: true }); else fn();
      return;
    }
    var idle = function () { _setTimeout(fn, 0); };
    if (rs === 'loading') _addDocListener('DOMContentLoaded', idle, { once: true }); else idle();
  }

  function ensureCtx(extId) {
    var meta = metas[extId];
    if (!meta) return null;
    var fresh = !contexts[extId];
    var ctx = contexts[extId] || makeCtx(extId, meta, 'cs');
    if (fresh) fire(extId, 'hello', { exts: _keys(contexts) });
    return ctx;
  }

  function register(meta, entries) {
    if (EXT_ORIGIN) return;
    metas[meta.id] = meta;
    var href = String(location.href);
    for (var i = 0; i < entries.length; i++) {
      var e = entries[i];
      if (!IS_TOP && !e.af) continue;
      if (!entryMatches(e, href)) continue;
      runEntry(meta, e);
    }
  }

  function runEntry(meta, e) {
    var ctx = ensureCtx(meta.id);
    if (!ctx) return;
    if (e.css) addStyle(meta.id, e.css, 'cs:' + meta.id + ':' + e.id);
    if (typeof e.js === 'function') {
      var fn = e.js;
      schedule(e.ra, function () {
        try { _apply(fn, W, [ctx.api, ctx.api]); } catch (err) { _err('[' + (meta.name || meta.id) + '] ошибка контент-скрипта:', err); }
      });
    }
  }

  function start() {
    if (EXT_ORIGIN) return;
    fire('', 'frame', null);
  }

  /* executeScript / insertCSS из фоновой страницы (вызывается приложением через evaluateJavascript). */
  _define(W, '__omniExec_' + RUN, {
    value: function (token, extId, xid, fn, cssText, cssKey, removeCss) {
      if (token !== TOKEN) return;
      var ctx = ensureCtx(extId);
      if (!ctx) { fire(extId, 'execResult', { xid: xid, error: 'Расширение не активно в этой вкладке' }); return; }
      if (cssText != null) {
        if (removeCss) removeStyle(cssKey); else addStyle(extId, cssText, cssKey);
        fire(extId, 'execResult', { xid: xid, result: null });
        return;
      }
      var finish = function (v) {
        try { _stringify(v === undefined ? null : v); } catch (e) { v = null; }
        fire(extId, 'execResult', { xid: xid, result: v === undefined ? null : v });
      };
      var failExec = function (er) { fire(extId, 'execResult', { xid: xid, error: String((er && er.message) || er) }); };
      try {
        var res = _apply(fn, W, [ctx.api, ctx.api]);
        if (res && typeof res.then === 'function') res.then(finish, failExec); else finish(res);
      } catch (er) { failExec(er); }
    },
    enumerable: false, configurable: false, writable: false
  });

  /* ---------------- Страницы расширения (фон, popup, настройки) ---------------- */

  function b64FromBuffer(buf) {
    var bytes = new Uint8Array(buf), parts = [], chunk = 0x8000;
    for (var i = 0; i < bytes.length; i += chunk) parts.push(String.fromCharCode.apply(null, bytes.subarray(i, i + chunk)));
    return btoa(parts.join(''));
  }
  function bufferFromB64(b64) {
    if (!b64) return new Uint8Array(0);
    var bin = atob(b64), out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  /* Расширения с host_permissions в Chrome делают кросс-доменные запросы без CORS — повторяем через приложение. */
  function installFetchBridge(ctx) {
    var nativeFetch = W.fetch;
    if (!nativeFetch) return;
    var bridged = function (input, init) {
      var req;
      try { req = new Request(input, init); } catch (e) { return _Promise.reject(e); }
      var u;
      try { u = new URL(req.url); } catch (e) { return _apply(nativeFetch, W, [input, init]); }
      if (u.origin === location.origin || (u.protocol !== 'http:' && u.protocol !== 'https:')) return _apply(nativeFetch, W, [input, init]);
      var headers = {};
      req.headers.forEach(function (v, k) { headers[k] = v; });
      var method = req.method;
      var bodyP = (method === 'GET' || method === 'HEAD') ? _Promise.resolve(null) : req.arrayBuffer().then(function (b) { return b.byteLength ? b64FromBuffer(b) : null; });
      return bodyP.then(function (body) {
        return call(ctx.extId, 'http', { url: u.href, method: method, headers: headers, body: body });
      }).then(function (r) {
        if (!r || !r.status) throw new TypeError('Failed to fetch');
        var nullBody = r.status === 101 || r.status === 204 || r.status === 205 || r.status === 304 || method === 'HEAD';
        var resp = new Response(nullBody ? null : bufferFromB64(r.body), { status: r.status, statusText: r.statusText || '', headers: r.headers || {} });
        try { _define(resp, 'url', { value: r.url || u.href }); } catch (e) {}
        try { _define(resp, 'redirected', { value: !!r.redirected }); } catch (e) {}
        return resp;
      }, function (er) { throw new TypeError('Failed to fetch: ' + ((er && er.message) || er)); });
    };
    W.fetch = bridged;

    var XP = XMLHttpRequest.prototype, XO = XP.open, XS = XP.send, XH = XP.setRequestHeader;
    XP.open = function (method, url, async) {
      try {
        var u = new URL(String(url), location.href);
        if (u.origin !== location.origin && (u.protocol === 'http:' || u.protocol === 'https:') && async !== false) {
          this.__omni = { method: String(method || 'GET').toUpperCase(), url: u.href, headers: {} };
          return;
        }
      } catch (e) {}
      this.__omni = null;
      return _apply(XO, this, arguments);
    };
    XP.setRequestHeader = function (k, v) {
      if (this.__omni) { this.__omni.headers[k] = v; return; }
      return _apply(XH, this, arguments);
    };
    XP.send = function (body) {
      var o = this.__omni;
      if (!o) return _apply(XS, this, arguments);
      var xhr = this;
      var init = { method: o.method, headers: o.headers };
      if (body != null && o.method !== 'GET' && o.method !== 'HEAD') init.body = body;
      var def = function (k, v) { try { _define(xhr, k, { value: v, configurable: true }); } catch (e) {} };
      var emit = function (t) { try { xhr.dispatchEvent(new Event(t)); } catch (e) {} };
      bridged(o.url, init).then(function (resp) {
        var rt = xhr.responseType || '';
        return (rt === 'arraybuffer' || rt === 'blob' ? resp.arrayBuffer() : resp.text()).then(function (data) {
          var hdrs = [];
          resp.headers.forEach(function (v, k) { hdrs.push(k + ': ' + v); });
          var response = data;
          if (rt === 'json') { try { response = _parse(data); } catch (e) { response = null; } }
          else if (rt === 'blob') response = new Blob([data], { type: resp.headers.get('content-type') || '' });
          def('readyState', 4); def('status', resp.status); def('statusText', resp.statusText);
          def('responseURL', resp.url || o.url); def('response', response);
          if (rt === '' || rt === 'text') def('responseText', data);
          def('getAllResponseHeaders', function () { return hdrs.join('\r\n'); });
          def('getResponseHeader', function (k) { return resp.headers.get(k); });
          emit('readystatechange'); emit('load'); emit('loadend');
        });
      }).catch(function () {
        def('readyState', 4); def('status', 0);
        emit('readystatechange'); emit('error'); emit('loadend');
      });
    };
  }

  function installServiceWorkerShims(ctx, meta) {
    var api = ctx.api;
    try {
      if (!('registration' in W)) {
        W.registration = {
          scope: meta.base, installing: null, waiting: null,
          active: { state: 'activated', scriptURL: meta.base, postMessage: function () {} },
          showNotification: function (title, o) { return api.notifications.create({ title: title, message: (o && o.body) || '' }); },
          getNotifications: function () { return _Promise.resolve([]); },
          update: function () { return _Promise.resolve(); },
          unregister: function () { return _Promise.resolve(true); },
          addEventListener: function () {}, removeEventListener: function () {}
        };
      }
      if (!('clients' in W)) {
        W.clients = {
          matchAll: function () { return _Promise.resolve([]); },
          claim: function () { return _Promise.resolve(); },
          get: function () { return _Promise.resolve(undefined); },
          openWindow: function (u) { return api.tabs.create({ url: u }); }
        };
      }
      if (!('skipWaiting' in W)) W.skipWaiting = function () { return _Promise.resolve(); };
    } catch (e) {}
    W.importScripts = function () {
      for (var i = 0; i < arguments.length; i++) {
        var u = new URL(String(arguments[i]), location.href).href;
        var x = new XMLHttpRequest();
        x.open('GET', u, false);
        x.send(null);
        if (x.status !== 200) throw new Error('importScripts: не удалось загрузить ' + u);
        (0, eval)(x.responseText + '\n//# sourceURL=' + u);
      }
    };
    var lifecycle = function () {
      ['install', 'activate'].forEach(function (t) {
        try { var ev = new Event(t); ev.waitUntil = function () {}; W.dispatchEvent(ev); } catch (e) {}
      });
    };
    if (document.readyState === 'loading') _addDocListener('DOMContentLoaded', lifecycle, { once: true }); else _setTimeout(lifecycle, 0);
  }

  function page(meta, kind) {
    frameKind = kind;
    metas[meta.id] = meta;
    var ctx = makeCtx(meta.id, meta, kind);
    var api = ctx.api;
    try { _define(W, 'chrome', { value: api, configurable: true, writable: true }); } catch (e) { try { W.chrome = api; } catch (e2) {} }
    try { _define(W, 'browser', { value: api, configurable: true, writable: true }); } catch (e) {}
    installFetchBridge(ctx);
    if (kind === 'popup') {
      try { W.close = function () { fire(meta.id, 'closePopup', null); }; } catch (e) {}
    }
    if (kind === 'bg') installServiceWorkerShims(ctx, meta);
    var ready = function () { _setTimeout(function () { fire(meta.id, 'ready', { kind: kind }); }, 0); };
    if (document.readyState === 'loading') _addDocListener('DOMContentLoaded', ready, { once: true }); else ready();
  }

  var core = { register: register, start: start, page: page };
  _define(W, '__omniCore', { value: core, configurable: true, enumerable: false, writable: false });
})();
