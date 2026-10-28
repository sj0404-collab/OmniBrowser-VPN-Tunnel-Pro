package com.winwasm64.ui

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** v4.2.0: состояние воспроизведения для «картинки в картинке» и фонового видео. */
object MediaState {
    /** Окно приложения сейчас в режиме «картинка в картинке». */
    var inPip by mutableStateOf(false)

    /** В активной вкладке играет видео/аудио. */
    var activeMediaPlaying by mutableStateOf(false)

    @Volatile
    var activeTabId: String? = null

    /** v4.2.3: обработчик команд кнопок окна PiP (play/pause/back/fwd); ставится экраном браузера. */
    @Volatile
    var controlHandler: ((String) -> Unit)? = null

    /** Когда пользователь последний раз нажимал кнопку в окне PiP (автовозобновление в это время не вмешивается). */
    @Volatile
    var lastUserControlAt = 0L

    fun control(cmd: String) {
        val h = controlHandler ?: return
        lastUserControlAt = android.os.SystemClock.elapsedRealtime()
        Handler(Looper.getMainLooper()).post { h(cmd) }
    }

    /** Управление видео на странице из окна PiP: пуск/пауза/перемотка ±10 с — для самого крупного или играющего элемента. */
    fun mediaControlScript(cmd: String): String = """
(function(cmd){
  try {
    var vs = Array.prototype.slice.call(document.querySelectorAll('video,audio'));
    if (!vs.length) return;
    var v = null;
    for (var i = 0; i < vs.length; i++) { if (!vs[i].paused && !vs[i].ended) { v = vs[i]; break; } }
    if (!v) { vs.sort(function(a, b){ return (b.clientWidth * b.clientHeight) - (a.clientWidth * a.clientHeight); }); v = vs[0]; }
    var play = function(){ try { var p = v.play(); if (p && p.catch) p.catch(function(){}); } catch (e) {} };
    var want = null;
    if (cmd === 'play') { play(); want = 'play'; }
    else if (cmd === 'pause') { window.__omniUserPauseAt = Date.now(); v.pause(); want = 'pause'; }
    else if (cmd === 'toggle') { if (v.paused) { play(); want = 'play'; } else { window.__omniUserPauseAt = Date.now(); v.pause(); want = 'pause'; } }
    else if (cmd === 'back') v.currentTime = Math.max(0, v.currentTime - 10);
    else if (cmd === 'fwd') { var d = isFinite(v.duration) ? v.duration : 1e12; v.currentTime = Math.min(d, v.currentTime + 10); }
    // сайт может «откатить» команду своим состоянием плеера — повторяем её дважды
    if (want) { [400, 1200].forEach(function(ms){ setTimeout(function(){ try { if (want === 'play' && v.paused && !v.ended) play(); else if (want === 'pause' && !v.paused) v.pause(); } catch (e) {} }, ms); }); }
  } catch (e) {}
})('$cmd');
"""

    private val mediaHosts = listOf(
        "youtube.com", "youtu.be", "youtube-nocookie.com", "twitch.tv", "rutube.ru", "vk.com", "vkvideo.ru", "ok.ru",
        "dzen.ru", "kinopoisk.ru", "soundcloud.com", "music.yandex", "vimeo.com", "dailymotion.com", "kick.com", "tiktok.com"
    )

    /** Сайт с видео/музыкой: его вкладку не скрываем от движка при переключении, чтобы воспроизведение не прерывалось. */
    fun isMediaSite(url: String?): Boolean {
        val host = try { android.net.Uri.parse(url ?: return false).host?.lowercase() ?: return false } catch (_: Exception) { return false }
        return mediaHosts.any { host == it || host.endsWith(".$it") || host.startsWith(it) }
    }

    /** Скрипт, который выполняется до скриптов страницы: учёт воспроизведения + страница не узнаёт, что скрыта. */
    const val DOCUMENT_START_SCRIPT = """
(function(){
  if (window.__omniMediaHooked) return; window.__omniMediaHooked = true;
  // v4.10.0: маскировка WebView под Chrome там, где WebView не умеет подменять UA Client Hints:
  // navigator.userAgentData без бренда «Android WebView» и объект window.chrome (GeForce NOW и др. проверяют их).
  try {
    var ua0 = navigator.userAgent || '';
    var isWin = ua0.indexOf('Windows NT') >= 0;
    var cm = ua0.match(/Chrome\/(\d+)((?:\.\d+)*)/); var maj = cm ? cm[1] : '131'; var full = cm ? (cm[1] + (cm[2] || '.0.0.0')) : '131.0.0.0';
    var uad = navigator.userAgentData;
    var needFix = !uad || !uad.brands || uad.brands.some(function(b){ return /WebView/i.test(b.brand || ''); }) || (isWin && (uad.mobile || uad.platform !== 'Windows'));
    if (needFix) {
      var brands = [{ brand: 'Chromium', version: maj }, { brand: 'Google Chrome', version: maj }, { brand: 'Not)A;Brand', version: '99' }];
      var fullList = [{ brand: 'Chromium', version: full }, { brand: 'Google Chrome', version: full }, { brand: 'Not)A;Brand', version: '99.0.0.0' }];
      var am = ua0.match(/Android (\d+(?:\.\d+)*)/);
      var low = { brands: brands, mobile: !isWin, platform: isWin ? 'Windows' : 'Android' };
      var high = { architecture: isWin ? 'x86' : 'arm', bitness: '64', model: '', platformVersion: isWin ? '15.0.0' : (am ? am[1] : '13'), uaFullVersion: full, fullVersionList: fullList, wow64: false, formFactors: [isWin ? 'Desktop' : 'Mobile'] };
      var data = { brands: brands, mobile: low.mobile, platform: low.platform,
        getHighEntropyValues: function(hints){ var r = { brands: brands, mobile: low.mobile, platform: low.platform }; (hints || []).forEach(function(h){ if (h in high) r[h] = high[h]; }); return Promise.resolve(r); },
        toJSON: function(){ return low; } };
      try { Object.defineProperty(Navigator.prototype, 'userAgentData', { get: function(){ return data; }, configurable: true }); }
      catch (e) { try { Object.defineProperty(navigator, 'userAgentData', { value: data, configurable: true }); } catch (x) {} }
    }
    if (!window.chrome) {
      var noop = function(){};
      window.chrome = {
        app: { isInstalled: false, getDetails: function(){ return null; }, getIsInstalled: function(){ return false; }, installState: function(cb){ if (cb) cb('not_installed'); }, runningState: function(){ return 'cannot_run'; },
               InstallState: { DISABLED: 'disabled', INSTALLED: 'installed', NOT_INSTALLED: 'not_installed' }, RunningState: { CANNOT_RUN: 'cannot_run', READY_TO_RUN: 'ready_to_run', RUNNING: 'running' } },
        runtime: { id: undefined, connect: function(){ return { onMessage: { addListener: noop, removeListener: noop }, onDisconnect: { addListener: noop, removeListener: noop }, postMessage: noop, disconnect: noop }; }, sendMessage: noop,
                   OnInstalledReason: { CHROME_UPDATE: 'chrome_update', INSTALL: 'install', SHARED_MODULE_UPDATE: 'shared_module_update', UPDATE: 'update' },
                   PlatformOs: { ANDROID: 'android', CROS: 'cros', LINUX: 'linux', MAC: 'mac', OPENBSD: 'openbsd', WIN: 'win' }, PlatformArch: { ARM: 'arm', ARM64: 'arm64', X86_32: 'x86-32', X86_64: 'x86-64' } },
        csi: function(){ return { startE: Date.now(), onloadT: Date.now(), pageT: 0, tran: 15 }; },
        loadTimes: function(){ return { requestTime: Date.now() / 1000, startLoadTime: Date.now() / 1000, commitLoadTime: Date.now() / 1000, finishDocumentLoadTime: 0, finishLoadTime: 0, firstPaintTime: 0, firstPaintAfterLoadTime: 0, navigationType: 'Other', wasFetchedViaSpdy: true, wasNpnNegotiated: true, npnNegotiatedProtocol: 'h2', wasAlternateProtocolAvailable: false, connectionInfo: 'h2' }; }
      };
    }
  } catch (e) {}
  // v4.10.3/4.10.5: WebRTC — диагностика в журнал и режим «только через прокси (TCP)» (по переключателю в настройках).
  // Для GeForce NOW режим НЕ включаем: GFN требует UDP (с блокировкой WebRTC даёт 0xC0F2220F) — проверено 4.10.3.
  try {
    var RPC0 = window.RTCPeerConnection || window.webkitRTCPeerConnection;
    if (RPC0 && !RPC0.__omniWrapped) {
      var tcpOnly = (function(){ try { return !!(window.OmniMedia && OmniMedia.webrtcTcp && OmniMedia.webrtcTcp()); } catch (e) { return false; } })();
      var rsay = function(m){ try { if (window.OmniMedia && OmniMedia.trace) OmniMedia.trace('WebRTC: ' + m); } catch (e) {} };
      var candProto = function(c){ var p = String(c || '').replace(/^a=/, '').split(' '); return p.length > 2 ? p[2].toLowerCase() : ''; };
      var candType = function(c){ var m = String(c || '').match(/ typ ([a-z]+)/); return m ? m[1] : '?'; };
      var candAddr = function(c){ var p = String(c || '').replace(/^a=/, '').split(' '); return p.length > 5 ? (p[4] + ':' + p[5]) : ''; };
      var isUdpCand = function(c){ return candProto(c) === 'udp'; };
      var stripUdp = function(sdp){ return String(sdp || '').split(/\r\n|\n/).filter(function(l){ return !(l.indexOf('a=candidate:') === 0 && isUdpCand(l)); }).join('\r\n'); };
      var sdpSummary = function(sdp){
        var lines = String(sdp || '').split(/\r\n|\n/); var udp = 0, tcp = 0, m = [], codecs = {}, first = '';
        lines.forEach(function(l){
          if (l.indexOf('a=candidate:') === 0) { if (isUdpCand(l)) { udp++; if (!first) first = candType(l) + '/udp ' + candAddr(l); } else tcp++; }
          else if (l.indexOf('m=') === 0) { var mp = l.split(' '); m.push(mp[0].slice(2) + (mp[1] === '0' ? '(откл.)' : '')); }
          else if (l.indexOf('a=setup:') === 0) codecs['dtls-' + l.slice(8)] = 1;
          else if (l.indexOf('a=rtpmap:') === 0) { var c = l.split(' ')[1]; if (c) codecs[c.split('/')[0]] = 1; }
        });
        return 'медиа ' + (m.join('+') || '-') + ' · кандидаты udp ' + udp + ', tcp ' + tcp + (first ? ' (' + first + ')' : '') + ' · кодеки ' + (Object.keys(codecs).join(',') || '-');
      };
      var P = RPC0.prototype;
      var origAEL = P.addEventListener;
      var pcCount = 0;
      var capsLogged = false;
      var logCaps = function(){ if (capsLogged) return; capsLogged = true; try { var v = (window.RTCRtpReceiver && RTCRtpReceiver.getCapabilities) ? RTCRtpReceiver.getCapabilities('video') : null; var names = {}; ((v && v.codecs) || []).forEach(function(c){ names[String(c.mimeType || '').replace('video/', '')] = 1; }); rsay('декодеры видео WebView: ' + (Object.keys(names).join(',') || 'нет данных')); } catch (e) {} };
      var Wrapped = function(cfg){
        var pc = new RPC0(cfg);
        var id = ++pcCount; var t0 = Date.now();
        var st = { lu: 0, lt: 0, lr: 0, ru: 0, rt: 0, logged: {}, first: '' };
        pc.__omniSt = st; pc.__omniId = id; pc.__omniT0 = t0;
        var ago = function(){ return Math.round((Date.now() - t0) / 100) / 10 + ' с'; };
        logCaps();
        try { rsay('#' + id + ' создано · ICE-серверов: ' + ((cfg && cfg.iceServers) ? cfg.iceServers.length : 0) + ' · политика: ' + ((cfg && cfg.iceTransportPolicy) || 'all') + (tcpOnly ? ' · режим «только через прокси (TCP)»' : '')); } catch (e) {}
        origAEL.call(pc, 'icecandidate', function(e){
          try {
            var c = e.candidate && e.candidate.candidate;
            if (!c) { rsay('#' + id + ' свои кандидаты собраны за ' + ago() + ': udp ' + st.lu + ', tcp ' + st.lt + ', relay ' + st.lr + (st.first ? ' · первый: ' + st.first : '') + (tcpOnly ? ' (udp странице не отдаём)' : '')); return; }
            if (isUdpCand(c)) st.lu++; else st.lt++;
            if (c.indexOf(' typ relay') >= 0) st.lr++;
            if (!st.first) st.first = candType(c) + '/' + candProto(c) + ' ' + candAddr(c);
          } catch (x) {}
        });
        origAEL.call(pc, 'icegatheringstatechange', function(){ try { if (pc.iceGatheringState === 'gathering') rsay('#' + id + ' сбор кандидатов начат (' + ago() + ')'); } catch (x) {} });
        origAEL.call(pc, 'track', function(e){ try { var tr = e.track; rsay('#' + id + ' получен трек ' + (tr ? tr.kind : '?') + ' (' + ago() + ')'); } catch (x) {} });
        var statsLine = function(label){
          pc.getStats().then(function(r){
            var pair = null, cands = {}, inbound = [], tr = null;
            r.forEach(function(v){ if (v.type === 'transport') tr = v; });
            r.forEach(function(v){ if (v.type === 'local-candidate' || v.type === 'remote-candidate') cands[v.id] = v; });
            r.forEach(function(v){ if (v.type === 'candidate-pair' && (v.selected || v.nominated || v.state === 'succeeded' || v.state === 'in-progress') && (!pair || (v.bytesReceived || 0) > (pair.bytesReceived || 0))) pair = v; });
            r.forEach(function(v){ if (v.type === 'inbound-rtp') inbound.push((v.kind || v.mediaType || '?') + ': ' + Math.round((v.bytesReceived || 0) / 1024) + ' КБ, пакетов ' + (v.packetsReceived || 0) + ', потеряно ' + (v.packetsLost || 0) + (v.kind === 'video' ? ', кадров ' + (v.framesDecoded || 0) + ' декодер ' + (v.decoderImplementation || '?') : '')); });
            var d = function(c){ return c ? ((c.candidateType || '?') + '/' + (c.protocol || '?') + ' ' + (c.address || c.ip || '') + ':' + (c.port || '')) : '?'; };
            rsay('#' + id + ' ' + label + ' (' + ago() + ')' + (pair ? ' · пара ' + pair.state + ': ' + d(cands[pair.localCandidateId]) + ' → ' + d(cands[pair.remoteCandidateId]) + ' · отправлено ' + Math.round((pair.bytesSent || 0) / 1024) + ' КБ, получено ' + Math.round((pair.bytesReceived || 0) / 1024) + ' КБ · STUN: наших запросов ' + (pair.requestsSent || 0) + ', ответов на них ' + (pair.responsesReceived || 0) + ', запросов от сервера ' + (pair.requestsReceived || 0) : ' · пары нет') + (tr ? ' · DTLS ' + (tr.dtlsState || '?') + (tr.dtlsRole ? '/' + tr.dtlsRole : '') + (tr.tlsVersion ? ' ' + tr.tlsVersion : '') + ', пакетов получено ' + (tr.packetsReceived || 0) : '') + (inbound.length ? ' · приём: ' + inbound.join('; ') : ''));
          }).catch(function(e){ rsay('#' + id + ' ' + label + ' (' + ago() + ') · статистика недоступна: ' + e); });
        };
        origAEL.call(pc, 'iceconnectionstatechange', function(){
          try {
            var s = pc.iceConnectionState;
            if (st.logged['ice' + s]) return; st.logged['ice' + s] = 1;
            if (s === 'checking' || s === 'new') { rsay('#' + id + ' ICE ' + s + ' (' + ago() + ') · удалённые кандидаты: udp ' + st.ru + ', tcp ' + st.rt); return; }
            statsLine('ICE ' + s);
            if (s === 'connected' || s === 'completed') { setTimeout(function(){ try { if (pc.connectionState !== 'closed') statsLine('через 5 с после соединения'); } catch (x) {} }, 5000); setTimeout(function(){ try { if (pc.connectionState !== 'closed') statsLine('через 20 с после соединения'); } catch (x) {} }, 20000); }
          } catch (x) {}
        });
        origAEL.call(pc, 'connectionstatechange', function(){ try { var s = pc.connectionState; if (s === 'failed' || s === 'disconnected') statsLine('соединение ' + s); } catch (x) {} });
        return pc;
      };
      Wrapped.prototype = P; Wrapped.__omniWrapped = true;
      try { Wrapped.generateCertificate = RPC0.generateCertificate.bind(RPC0); } catch (e) {}
      var origAdd = P.addIceCandidate, origSetRemote = P.setRemoteDescription, origClose = P.close, origSetLocal = P.setLocalDescription;
      P.setLocalDescription = function(desc){
        var self = this; var pr = origSetLocal.apply(this, arguments);
        try { if (pr && pr.then) pr.then(function(){ try { var ld = self.localDescription; if (ld && ld.sdp && self.__omniSt && !self.__omniSt.logged['local' + ld.type]) { self.__omniSt.logged['local' + ld.type] = 1; rsay('#' + self.__omniId + ' наш ' + ld.type + ': ' + sdpSummary(ld.sdp)); } } catch (e) {} }, function(e){ rsay('#' + self.__omniId + ' setLocalDescription отклонён: ' + e); }); } catch (e) {}
        return pr;
      };
      P.close = function(){ try { var st = this.__omniSt; if (st && !st.logged.closed) { st.logged.closed = 1; rsay('#' + this.__omniId + ' закрыто страницей через ' + Math.round((Date.now() - this.__omniT0) / 100) / 10 + ' с · ICE ' + this.iceConnectionState + ', соединение ' + this.connectionState); } } catch (e) {} return origClose.apply(this, arguments); };
      P.addIceCandidate = function(c){
        try {
          var st = this.__omniSt; var line = c && (typeof c === 'string' ? c : c.candidate);
          if (st && line) { if (isUdpCand(line)) st.ru++; else st.rt++; if (st.ru + st.rt === 1) rsay('#' + this.__omniId + ' первый удалённый кандидат: ' + candType(line) + '/' + candProto(line) + ' ' + candAddr(line)); }
          if (tcpOnly && line && isUdpCand(line)) { var cb = arguments[1]; if (typeof cb === 'function') { try { cb(); } catch (e) {} } return Promise.resolve(); }
        } catch (e) {}
        return origAdd.apply(this, arguments);
      };
      P.setRemoteDescription = function(desc){
        try {
          var st = this.__omniSt;
          if (desc && desc.sdp) {
            if (st) { String(desc.sdp).split(/\r\n|\n/).forEach(function(l){ if (l.indexOf('a=candidate:') === 0) { if (isUdpCand(l)) st.ru++; else st.rt++; } }); rsay('#' + this.__omniId + ' удалённый ' + (desc.type || '?') + ': ' + sdpSummary(desc.sdp)); }
            if (tcpOnly) { var a = Array.prototype.slice.call(arguments); a[0] = { type: desc.type, sdp: stripUdp(desc.sdp) }; return origSetRemote.apply(this, a); }
          }
        } catch (e) {}
        return origSetRemote.apply(this, arguments);
      };
      if (tcpOnly) {
        var wrapHandler = function(fn){ return (typeof fn === 'function') ? function(e){ if (e && e.candidate && e.candidate.candidate && isUdpCand(e.candidate.candidate)) return; return fn.apply(this, arguments); } : fn; };
        P.addEventListener = function(type, fn, opt){ if (type === 'icecandidate') { var w = wrapHandler(fn); if (fn && w !== fn) { try { fn.__omniW = w; } catch (e) {} } return origAEL.call(this, type, w, opt); } return origAEL.call(this, type, fn, opt); };
        var origREL = P.removeEventListener;
        P.removeEventListener = function(type, fn, opt){ return origREL.call(this, type, (fn && fn.__omniW) || fn, opt); };
        var dOn = Object.getOwnPropertyDescriptor(P, 'onicecandidate');
        if (dOn && dOn.set) Object.defineProperty(P, 'onicecandidate', { configurable: true, get: dOn.get, set: function(fn){ dOn.set.call(this, wrapHandler(fn)); } });
        ['localDescription', 'currentLocalDescription', 'pendingLocalDescription'].forEach(function(name){
          var dd = Object.getOwnPropertyDescriptor(P, name);
          if (dd && dd.get) Object.defineProperty(P, name, { configurable: true, get: function(){ var v = dd.get.call(this); if (!v || !v.sdp) return v; try { return new RTCSessionDescription({ type: v.type, sdp: stripUdp(v.sdp) }); } catch (e) { return v; } } });
        });
      }
      window.RTCPeerConnection = Wrapped;
      try { window.webkitRTCPeerConnection = Wrapped; } catch (e) {}
    }
  } catch (e) {}
  try {
    var last = null;
    var report = function(){
      try {
        var vs = document.querySelectorAll('video,audio'); var p = false;
        for (var i = 0; i < vs.length; i++) { var v = vs[i]; if (!v.paused && !v.ended) { p = true; break; } }
        if (p !== last) { last = p; if (window.OmniMedia) OmniMedia.state(p); }
      } catch (e) {}
    };
    ['play','playing','pause','ended','emptied','abort'].forEach(function(ev){ document.addEventListener(ev, function(){ setTimeout(report, 80); }, true); });
    setInterval(report, 3000);
    // v4.2.13: сколько видео реально играло и сколько трафика съела страница (ресурсы через PerformanceObserver;
    // для googlevideo, где размер скрыт политикой Timing-Allow-Origin, берём Content-Length из fetch/XHR)
    var S = window.__omniStats = { playedMs: 0, firstPlayAt: 0, lastPlayAt: 0, bytes: 0, media: 0, n: 0, lastCt: -1 };
    var clByUrl = {};
    var isMedia = function(u){ return u.indexOf('googlevideo.com') >= 0 || u.indexOf('videoplayback') >= 0 || u.indexOf('.m3u8') >= 0 || u.indexOf('.ts?') >= 0 || u.indexOf('.mp4') >= 0 || u.indexOf('.m4s') >= 0 || u.indexOf('.webm') >= 0; };
    var addBytes = function(u, b){ if (!(b > 0)) return; S.bytes += b; S.n++; if (isMedia(u)) S.media += b; };
    try {
      var po = new PerformanceObserver(function(list){
        try {
          var es = list.getEntries();
          for (var i = 0; i < es.length; i++) {
            var e = es[i]; var b = e.transferSize || e.encodedBodySize || 0;
            if (!b) { b = clByUrl[e.name] || 0; }
            delete clByUrl[e.name];
            addBytes(e.name, b);
          }
        } catch (x) {}
      });
      po.observe({ type: 'resource', buffered: true });
    } catch (e) {}
    try {
      var of = window.fetch;
      if (of) window.fetch = function(input, init){
        var u = (typeof input === 'string') ? input : (input && input.url) || '';
        return of.apply(this, arguments).then(function(res){
          try { var cl = parseInt(res.headers.get('content-length') || '0', 10); if (cl > 0 && u) clByUrl[u] = cl; } catch (x) {}
          return res;
        });
      };
    } catch (e) {}
    try {
      var oo = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = function(m, u){
        try { var self = this; self.__omniUrl = String(u); self.addEventListener('loadend', function(){ try { var cl = parseInt(self.getResponseHeader('content-length') || '0', 10); if (cl > 0) clByUrl[self.__omniUrl] = cl; } catch (x) {} }); } catch (x) {}
        return oo.apply(this, arguments);
      };
    } catch (e) {}
    document.addEventListener('timeupdate', function(e){
      try {
        var v = e.target; if (!v || (v.tagName !== 'VIDEO' && v.tagName !== 'AUDIO') || v.paused) return;
        var ct = v.currentTime; var d = ct - S.lastCt;
        if (S.lastCt >= 0 && d > 0 && d < 2) S.playedMs += d * 1000;
        S.lastCt = ct;
        if (!S.firstPlayAt) S.firstPlayAt = Date.now();
        S.lastPlayAt = Date.now();
      } catch (x) {}
    }, true);
    document.addEventListener('seeking', function(){ S.lastCt = -1; }, true);
    window.__omniStatsJson = function(){
      return JSON.stringify({ played: Math.round(S.playedMs), wall: S.firstPlayAt ? (Date.now() - S.firstPlayAt) : 0, bytes: Math.round(S.bytes), media: Math.round(S.media), n: S.n });
    };
    var statsAt = function(){ return { t: Date.now(), played: S.playedMs, bytes: S.bytes, media: S.media }; };
    var lastStop = statsAt();
    document.addEventListener('pause', function(e){
      try {
        var v = e.target; if (!v || (v.tagName !== 'VIDEO' && v.tagName !== 'AUDIO')) return;
        setTimeout(function(){
          try {
            var now = statsAt(); var seg = { played: now.played - lastStop.played, bytes: now.bytes - lastStop.bytes, media: now.media - lastStop.media, wall: now.t - lastStop.t };
            lastStop = now;
            if (seg.played < 1000) return;
            if (window.OmniMedia && OmniMedia.stats) OmniMedia.stats(JSON.stringify({ kind: 'stop', seg: seg, total: { played: S.playedMs, wall: S.firstPlayAt ? (Date.now() - S.firstPlayAt) : 0, bytes: S.bytes, media: S.media } }));
          } catch (x) {}
        }, 400);
      } catch (x) {}
    }, true);
    document.addEventListener('playing', function(){ try { if (lastStop.played < S.playedMs - 1000) return; lastStop = statsAt(); } catch (x) {} }, true);
    var pipMark = null;
    window.__omniPipMark = function(){ pipMark = statsAt(); };
    window.__omniPipReport = function(){
      try {
        if (!pipMark) return; var now = statsAt();
        if (window.OmniMedia && OmniMedia.stats) OmniMedia.stats(JSON.stringify({ kind: 'pip', seg: { played: now.played - pipMark.played, bytes: now.bytes - pipMark.bytes, media: now.media - pipMark.media, wall: now.t - pipMark.t }, total: { played: S.playedMs, wall: S.firstPlayAt ? (Date.now() - S.firstPlayAt) : 0, bytes: S.bytes, media: S.media } }));
        pipMark = null;
      } catch (x) {}
    };
    // каждые 5 минут непрерывной игры — промежуточная сводка
    var lastPeriodic = statsAt();
    setInterval(function(){
      try {
        if (Date.now() - S.lastPlayAt > 4000) { lastPeriodic = statsAt(); return; }
        var now = statsAt(); if (now.t - lastPeriodic.t < 300000) return;
        if (window.OmniMedia && OmniMedia.stats) OmniMedia.stats(JSON.stringify({ kind: 'period', seg: { played: now.played - lastPeriodic.played, bytes: now.bytes - lastPeriodic.bytes, media: now.media - lastPeriodic.media, wall: now.t - lastPeriodic.t }, total: { played: S.playedMs, wall: Date.now() - S.firstPlayAt, bytes: S.bytes, media: S.media } }));
        lastPeriodic = now;
      } catch (x) {}
    }, 10000);
    // v4.2.10: причина пауз в PiP — сам плеер YouTube: в его конфиге есть флаги html5_picture_in_picture_blocking_*
    // (onresize/ontimeupdate/standard_api/document_fullscreen) — плеер распознаёт маленькое окно PiP и ставит паузу
    // на каждом timeupdate. Как в Brave: переписываем флаги в ytcfg до создания плеера.
    var hn = location.hostname || '';
    if (hn === 'youtube.com' || hn.slice(-12) === '.youtube.com' || hn.slice(-21) === '.youtube-nocookie.com') {
      var flagRe = new RegExp('html5_picture_in_picture_(blocking|logging)_([a-z_]+)=true', 'g');
      var fixFlags = function(){
        try {
          var cfg = window.ytcfg; if (!cfg || !cfg.get) return;
          var confs = cfg.get('WEB_PLAYER_CONTEXT_CONFIGS'); if (!confs) return;
          var n = 0;
          for (var k in confs) {
            var c = confs[k]; if (!c || typeof c.serializedExperimentFlags !== 'string') continue;
            var f = c.serializedExperimentFlags.replace(flagRe, function(m, a, b){ return 'html5_picture_in_picture_' + a + '_' + b + '=false'; });
            if (f !== c.serializedExperimentFlags) { c.serializedExperimentFlags = f; n++; }
          }
          if (n && !window.__omniPipFlagsFixed) { window.__omniPipFlagsFixed = true; if (window.OmniMedia && OmniMedia.trace) OmniMedia.trace('YouTube: отключены флаги блокировки PiP в конфиге плеера (' + n + ')'); }
        } catch (e) {}
      };
      var hookSet = function(){
        try {
          var cfg = window.ytcfg; if (!cfg || !cfg.set || cfg.__omniHooked) return;
          cfg.__omniHooked = true; var os = cfg.set;
          cfg.set = function(){ var r = os.apply(this, arguments); fixFlags(); return r; };
        } catch (e) {}
      };
      fixFlags(); hookSet();
      document.addEventListener('load', function(e){ try { if (e.target && e.target.tagName === 'SCRIPT') { hookSet(); fixFlags(); } } catch (x) {} }, true);
      var t0 = Date.now(); var iv = setInterval(function(){ hookSet(); fixFlags(); if (Date.now() - t0 > 20000) clearInterval(iv); }, 250);
      document.addEventListener('DOMContentLoaded', function(){ hookSet(); fixFlags(); });
      window.__omniFixYtFlags = fixFlags;
    }
    // v4.2.10: с января 2026 YouTube останавливает видео у не-Premium, если страница «не видна» (проверяет
    // document.visibilityState, а не только событие). Для вкладки, которую смотрит пользователь (активная / PiP),
    // страница всегда «видима»: как в Brave и mozilla/video-bg-play.
    try {
      var held = function(){ return !!(window.__omniInPip || window.__omniHoldFocus); };
      var vsDesc = Object.getOwnPropertyDescriptor(Document.prototype, 'visibilityState');
      var hdDesc = Object.getOwnPropertyDescriptor(Document.prototype, 'hidden');
      if (vsDesc && vsDesc.get) Object.defineProperty(document, 'visibilityState', { configurable: true, get: function(){ return held() ? 'visible' : vsDesc.get.call(document); } });
      if (hdDesc && hdDesc.get) Object.defineProperty(document, 'hidden', { configurable: true, get: function(){ return held() ? false : hdDesc.get.call(document); } });
      var origHasFocus = document.hasFocus;
      document.hasFocus = function(){ return held() ? true : origHasFocus.call(document); };
      var swallow = function(e){ if (held()) { e.stopImmediatePropagation(); if (e.type === 'visibilitychange' && window.OmniMedia && OmniMedia.trace) OmniMedia.trace('событие visibilitychange скрыто от страницы (вкладка удерживается)'); } };
      window.addEventListener('visibilitychange', swallow, true);
      document.addEventListener('visibilitychange', swallow, true);
      window.addEventListener('pagehide', swallow, true);
      window.addEventListener('blur', function(e){ if (held() && e.target === window) e.stopImmediatePropagation(); }, true);
    } catch (e) {}
    // v4.2.9: причина пауз найдена — Chromium при потере аудиофокуса (второй плеер в другой вкладке / другое приложение)
    // шлёт странице действие mediaSession «pause», YouTube вызывает pauseVideo(); две вкладки качают фокус друг у друга
    // раз в секунду. Для вкладки, которую смотрит пользователь (активная или в PiP), такое «системное» pause игнорируем.
    try {
      var ms = navigator.mediaSession;
      if (ms && ms.setActionHandler) {
        var origSet = ms.setActionHandler.bind(ms);
        ms.setActionHandler = function(action, handler){
          if (action === 'pause' && typeof handler === 'function') {
            var wrapped = function(){
              window.__omniLastMsPause = Date.now();
              if (window.__omniInPip || window.__omniHoldFocus) {
                var n = (window.__omniMsIgnored || 0) + 1; window.__omniMsIgnored = n;
                if ((n <= 3 || n % 20 === 0) && window.OmniMedia && OmniMedia.trace) OmniMedia.trace('системная команда «пауза» (аудиофокус отобрал другой плеер) проигнорирована, №' + n);
                return;
              }
              return handler.apply(this, arguments);
            };
            return origSet(action, wrapped);
          }
          return origSet(action, handler);
        };
      }
    } catch (e) {}
    // кто ставит паузу — запоминаем стек вызова pause() (если паузу поставил движок, стека не будет)
    try {
      var origPause = HTMLMediaElement.prototype.pause;
      HTMLMediaElement.prototype.pause = function(){
        try { window.__omniLastPause = { t: Date.now(), stack: (new Error().stack || '').split('\n').slice(1, 9).map(function(l){ l = l.trim(); return l.indexOf('at ') === 0 ? l.slice(3) : l; }).join(' | ') }; } catch (e) {}
        return origPause.apply(this, arguments);
      };
    } catch (e) {}
    var lastPauseLog = 0;
    document.addEventListener('pause', function(e){
      try {
        var v = e.target; if (!v || v.tagName !== 'VIDEO' || v.ended) return;
        var user = Date.now() - (window.__omniUserPauseAt || 0) < 5000;
        var lp = window.__omniLastPause; var viaJs = lp && Date.now() - lp.t < 300;
        var viaMs = Date.now() - (window.__omniLastMsPause || 0) < 300;
        var who = user ? 'кнопка пользователя' : viaMs ? 'системная команда mediaSession (аудиофокус)' : viaJs ? ('скрипт сайта: ' + lp.stack) : 'движок/система (не через JS)';
        if (Date.now() - lastPauseLog > 1500 && window.OmniMedia && OmniMedia.trace) { lastPauseLog = Date.now(); OmniMedia.trace('пауза' + (window.__omniInPip ? ' в PiP' : '') + ' @' + Math.round(v.currentTime) + 'с — ' + who); }
        if (user || !window.__omniInPip) return;
        var n = window.__omniResumeCount || 0;
        if (n >= 30) return;
        window.__omniResumeCount = n + 1;
        setTimeout(function(){ try { if (window.__omniInPip && v.paused && !v.ended) { var p = v.play(); if (p && p.catch) p.catch(function(){}); } } catch (x) {} }, 250);
      } catch (x) {}
    }, true);
    var swallow = function(e){ try { e.stopImmediatePropagation(); } catch (x) {} };
    document.addEventListener('visibilitychange', swallow, true);
    document.addEventListener('webkitvisibilitychange', swallow, true);
    window.addEventListener('pagehide', swallow, true);
    var vis = function(name, val){ try { Object.defineProperty(document, name, { get: function(){ return val; }, configurable: true }); } catch (e) {} };
    vis('hidden', false); vis('webkitHidden', false); vis('visibilityState', 'visible'); vis('webkitVisibilityState', 'visible');
    try { if (document.hasFocus) document.hasFocus = function(){ return true; }; } catch (e) {}
    // v4.8.0: «продолжить с места» — позиция длинных видео запоминается (OmniMedia.position) и восстанавливается
    // при следующем открытии той же страницы/ролика, если в адресе нет явного t=/start=.
    try {
      var posKey = function(){
        try {
          var h = location.hostname || '', u = new URL(location.href);
          if (h === 'youtube.com' || h.slice(-12) === '.youtube.com' || h === 'youtu.be' || h.slice(-21) === '.youtube-nocookie.com') {
            var id = u.searchParams.get('v');
            if (!id) { var m = u.pathname.match(/\/(shorts|embed|live|v)\/([A-Za-z0-9_-]{6,})/); if (m) id = m[2]; }
            if (!id && h === 'youtu.be') id = u.pathname.slice(1);
            return id ? 'yt:' + id : '';
          }
          return u.origin + u.pathname + u.search;
        } catch (e) { return ''; }
      };
      var hasExplicitStart = function(){ try { var u = new URL(location.href); return !!(u.searchParams.get('t') || u.searchParams.get('start') || /[?&#]t=/.test(location.hash)); } catch (e) { return false; } };
      var lastSaveAt = 0, restoredFor = '';
      document.addEventListener('timeupdate', function(e){
        try {
          var v = e.target; if (!v || v.tagName !== 'VIDEO' || v.paused) return;
          var now = Date.now(); if (now - lastSaveAt < 3000) return; lastSaveAt = now;
          var d = v.duration; if (!isFinite(d) || d < 120 || v.currentTime < 10) return;
          var k = posKey(); if (!k) return;
          if (window.OmniMedia && OmniMedia.position) OmniMedia.position(k, v.currentTime, d);
        } catch (x) {}
      }, true);
      var tryRestore = function(v){
        try {
          if (!v || v.tagName !== 'VIDEO') return;
          var d = v.duration; if (!isFinite(d) || d < 120) return;
          var k = posKey(); if (!k || restoredFor === k || hasExplicitStart()) return;
          restoredFor = k;
          if (v.currentTime > 8) return;
          var saved = (window.OmniMedia && OmniMedia.savedPosition) ? OmniMedia.savedPosition(k) : 0;
          if (saved > 10 && saved < d - 15) { v.currentTime = saved; if (window.OmniMedia && OmniMedia.trace) OmniMedia.trace('продолжаем с ' + Math.round(saved) + ' с'); }
        } catch (x) {}
      };
      document.addEventListener('loadedmetadata', function(e){ tryRestore(e.target); }, true);
      document.addEventListener('playing', function(e){ tryRestore(e.target); }, true);
    } catch (e) {}
    // v4.9.0: автозапуск следующего видео. После события ended (не реклама) ждём 2.5 с: если сайт сам не переключил
    // ролик — YouTube: nextVideo() плеера → кнопка «Далее» на конечной заставке → первая рекомендация;
    // другие сайты: видимая кнопка/ссылка «Следующее видео / Next».
    try {
      var isYt = function(){ var h = location.hostname || ''; return h === 'youtube.com' || h.slice(-12) === '.youtube.com' || h.slice(-21) === '.youtube-nocookie.com'; };
      var curVid = function(){ try { return new URL(location.href).searchParams.get('v') || ''; } catch (e) { return ''; } };
      var isAd = function(){ try { var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player'); return !!(p && p.classList && p.classList.contains('ad-showing')) || !!document.querySelector('.ytp-ad-player-overlay, .ad-interrupting'); } catch (e) { return false; } };
      var anyPlaying = function(){ var vs = document.querySelectorAll('video'); for (var i = 0; i < vs.length; i++) { if (!vs[i].paused && !vs[i].ended && vs[i].currentTime > 0) return true; } return false; };
      var visible = function(el){ try { var r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== 'hidden'; } catch (e) { return false; } };
      var say = function(m){ try { if (window.OmniMedia && OmniMedia.trace) OmniMedia.trace('автозапуск: ' + m); } catch (e) {} };
      // v4.9.1: переключатель автовоспроизведения YouTube больше не трогаем — синтетический клик по элементам
      // управления плеера m.youtube.com мог сработать как тап по плееру (пауза). Переход делает только обработчик ended.
      var goNextYt = function(startVid, startHref){
        var changed = function(){ return location.href !== startHref || curVid() !== startVid || anyPlaying(); };
        try { var p = document.getElementById('movie_player'); if (p && typeof p.nextVideo === 'function') { p.nextVideo(); say('плеер → nextVideo()'); } } catch (e) {}
        setTimeout(function(){
          if (changed()) return;
          var btn = document.querySelector('ytm-autonav-endscreen-renderer a[href*="/watch"], .autonav-endscreen-upnext-button, .autonav-endscreen-upnext-thumbnail, [class*="autonav-endscreen"] a[href*="/watch"], .ytp-autonav-endscreen-upnext-button, a.ytp-autonav-endscreen-link-container');
          if (btn) { btn.click(); say('кнопка «Далее» на заставке'); }
          setTimeout(function(){
            if (changed()) return;
            var links = document.querySelectorAll('ytm-item-section-renderer a[href*="/watch?v="], ytm-video-with-context-renderer a[href*="/watch?v="], ytm-compact-video-renderer a[href*="/watch?v="], a.compact-media-item-image[href*="/watch?v="], ytd-compact-video-renderer a[href*="/watch?v="], a[href*="/watch?v="]');
            for (var i = 0; i < links.length; i++) {
              var a = links[i]; var href = a.getAttribute('href') || ''; var m = href.match(/[?&]v=([A-Za-z0-9_-]{6,})/);
              if (!m || m[1] === startVid) continue;
              say('первая рекомендация ' + m[1]); a.click();
              setTimeout(function(){ if (!changed()) location.href = href; }, 1500);
              return;
            }
            say('следующее видео не найдено');
          }, 2000);
        }, 2000);
      };
      var goNextGeneric = function(startHref){
        var els = document.querySelectorAll('a, button, [role="button"]');
        var re = /^(следующ\w*( видео| серия| эпизод| ролик)?|дальше|далее|next( video| episode)?|play next|смотреть следующ\w*)$/i;
        for (var i = 0; i < els.length; i++) {
          var el = els[i]; var t = ((el.getAttribute('aria-label') || el.getAttribute('title') || el.innerText || '').trim()).replace(/\s+/g, ' ');
          if (t && t.length < 40 && re.test(t) && visible(el)) { say('кнопка «' + t + '»'); el.click(); return; }
        }
        var ln = document.querySelector('a[rel="next"]');
        if (ln && ln.href && ln.href !== startHref) { say('ссылка rel=next'); location.href = ln.href; return; }
        say('на этом сайте следующее видео не найдено');
      };
      document.addEventListener('ended', function(e){
        try {
          var v = e.target; if (!v || v.tagName !== 'VIDEO') return;
          if (!(window.OmniMedia && OmniMedia.autoNext && OmniMedia.autoNext())) return;
          if (!isFinite(v.duration) || v.duration < 45) return;
          if (isYt() && (isAd() || location.pathname.indexOf('/shorts/') === 0)) return;
          var startHref = location.href, startVid = curVid();
          setTimeout(function(){
            try {
              if (location.href !== startHref || anyPlaying()) return; // сайт переключил сам
              if (isYt()) goNextYt(startVid, startHref); else goNextGeneric(startHref);
            } catch (x) {}
          }, 2500);
        } catch (x) {}
      }, true);
      document.addEventListener('durationchange', function(e){ try { var v = e.target; if (v && v.tagName === 'VIDEO' && restoredFor && restoredFor !== posKey()) { restoredFor = ''; } tryRestore(v); } catch (x) {} }, true);
    } catch (e) {}
  } catch (e) {}
})();
"""

    /** Вкладка, которую смотрит пользователь: системные «pause» по аудиофокусу игнорируются. */
    fun holdFocusScript(on: Boolean): String = "(function(){ window.__omniHoldFocus = $on; })();"

    /** Вызывается, когда во вкладке [tabId] началось воспроизведение — остальные вкладки надо поставить на паузу явно. */
    @Volatile
    var onTabStartedPlaying: ((String) -> Unit)? = null

    /** Диагностика окна PiP для журнала: размер вьюпорта, видео и его положение. */
    const val PIP_DIAG_SCRIPT = """
(function(){
  try {
    var vs = document.querySelectorAll('video'); var v = null;
    for (var i = 0; i < vs.length; i++) { if (!vs[i].paused) { v = vs[i]; break; } }
    if (!v && vs.length) v = vs[0];
    var r = v ? v.getBoundingClientRect() : null;
    return JSON.stringify({ vp: innerWidth + 'x' + innerHeight, dpr: devicePixelRatio, videos: vs.length,
      paused: v ? v.paused : null, ready: v ? v.readyState : null, size: v ? (v.videoWidth + 'x' + v.videoHeight) : null,
      rect: r ? [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)] : null,
      fs: !!(document.fullscreenElement || document.webkitFullscreenElement), vis: document.visibilityState });
  } catch (e) { return 'error: ' + e; }
})();
"""

    /** Включает/выключает раскладку «только видео на весь экран» (для окна PiP). */
    fun pipLayoutScript(on: Boolean): String = """
(function(on){
  try {
    var id = '__omniPipStyle'; var s = document.getElementById(id);
    var clearMarks = function(){
      var all = document.querySelectorAll('[data-omni-pip],[data-omni-pip-anc]');
      for (var k = 0; k < all.length; k++) { all[k].removeAttribute('data-omni-pip'); all[k].removeAttribute('data-omni-pip-anc'); }
    };
    var pick = function(){
      try {
        var vs = Array.prototype.slice.call(document.querySelectorAll('video')); if (!vs.length) return;
        var best = null;
        for (var i = 0; i < vs.length; i++) { if (!vs[i].paused && !vs[i].ended && vs[i].readyState > 1) { best = vs[i]; break; } }
        if (!best) { vs.sort(function(a, b){ var ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect(); return (rb.width * rb.height) - (ra.width * ra.height); }); best = vs[0]; }
        if (best.getAttribute('data-omni-pip') === '1') return;
        clearMarks();
        best.setAttribute('data-omni-pip', '1');
        // v4.2.5: помечаем всю цепочку предков — только они остаются видимыми (всё остальное: панели, подложки, оверлеи плеера — скрыто),
        // а их transform/filter/contain снимаются, иначе position:fixed считается не от окна, а от контейнера плеера
        var p = best.parentElement;
        while (p) { p.setAttribute('data-omni-pip-anc', '1'); p = p.parentElement; }
      } catch (e) {}
    };
    window.__omniInPip = !!on;
    if (on) {
      window.__omniResumeCount = 0;
      try { if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen(); } catch (e) {}
      try { if (document.webkitFullscreenElement && document.webkitExitFullscreen) document.webkitExitFullscreen(); } catch (e) {}
      try { window.scrollTo(0, 0); } catch (e) {}
      pick();
      if (!window.__omniPipTimer) window.__omniPipTimer = setInterval(pick, 2000);
      if (!s) {
        s = document.createElement('style'); s.id = id;
        s.textContent =
          'html,body{overflow:hidden!important;background:#000!important;margin:0!important;padding:0!important}' +
          'body *{visibility:hidden!important}' +
          '[data-omni-pip-anc]{visibility:visible!important;transform:none!important;filter:none!important;perspective:none!important;contain:none!important;clip-path:none!important;backdrop-filter:none!important;will-change:auto!important;background:transparent!important;box-shadow:none!important;border:0!important;outline:0!important}' +
          '[data-omni-pip-anc]::before,[data-omni-pip-anc]::after{display:none!important}' +
          'video[data-omni-pip="1"]{visibility:visible!important;position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;min-width:0!important;min-height:0!important;margin:0!important;z-index:2147483647!important;object-fit:contain!important;background:#000!important;transform:none!important;opacity:1!important;filter:none!important;clip-path:none!important}';
        document.documentElement.appendChild(s);
      }
    } else {
      if (s) s.remove();
      if (window.__omniPipTimer) { clearInterval(window.__omniPipTimer); window.__omniPipTimer = null; }
      clearMarks();
    }
  } catch (e) {}
})($on);
"""
}

/** Мост из страницы: OmniMedia.state(true/false). */
class OmniMediaBridge(private val tab: BrowserTabModel) {
    @JavascriptInterface
    fun trace(msg: String) {
        com.winwasm64.vpn.VpnLog.i("Видео", "«${tab.title.take(40)}»: " + msg.take(600))
    }

    /** v4.2.13: сводка «сколько играло и сколько трафика»: {kind: stop|pip|period, seg:{played,wall,bytes,media}, total:{…}}. */
    @JavascriptInterface
    fun stats(json: String) {
        try {
            val o = org.json.JSONObject(json)
            val kind = o.optString("kind")
            val seg = o.getJSONObject("seg")
            val tot = o.getJSONObject("total")
            fun dur(ms: Long): String {
                val s = ms / 1000
                return when {
                    s >= 3600 -> "${s / 3600} ч ${(s % 3600) / 60} мин"
                    s >= 60 -> "${s / 60} мин ${s % 60} с"
                    else -> "$s с"
                }
            }
            fun mb(b: Long): String = if (b >= 1_048_576) String.format(java.util.Locale.US, "%.1f МБ", b / 1_048_576.0) else "${b / 1024} КБ"
            val sp = seg.optLong("played"); val sw = seg.optLong("wall"); val sb = seg.optLong("bytes"); val sm = seg.optLong("media")
            val rate = if (sw > 1000) " ≈${sb * 1000 / sw / 1024} КБ/с" else ""
            val head = when (kind) {
                "pip" -> "PiP закрыт: в окне PiP играло ${dur(sp)} из ${dur(sw)}"
                "period" -> "играет уже ${dur(tot.optLong("played"))}; за последние ${dur(sw)}"
                else -> "остановлено: играло ${dur(sp)} подряд" + (if (sw > sp + 15_000) " (с паузами/буферизацией за ${dur(sw)})" else "")
            }
            val traffic = if (sm > 0) "трафик видео ${mb(sm)}, всего ${mb(sb)}$rate" else "трафик ${mb(sb)}$rate"
            val total = "всего с открытия: играло ${dur(tot.optLong("played"))}, трафик ${mb(tot.optLong("bytes"))}"
            com.winwasm64.vpn.VpnLog.i("Видео", "«${tab.title.take(40)}» — $head, $traffic · $total")
        } catch (_: Exception) {
        }
    }

    // v4.3.6: загрузка файла силами страницы — см. InPageDownloads
    @JavascriptInterface
    fun dlChunk(id: String, b64: String) { com.winwasm64.ui.browser.InPageDownloads.onChunk(id, b64) }

    @JavascriptInterface
    fun dlDone(id: String, bytes: Double) { com.winwasm64.ui.browser.InPageDownloads.onDone(id, bytes.toLong()) }

    @JavascriptInterface
    fun dlError(id: String, msg: String) { com.winwasm64.ui.browser.InPageDownloads.onError(id, msg) }

    @JavascriptInterface
    fun textResult(id: String, text: String, error: String) { com.winwasm64.ui.browser.InPageDownloads.onText(id, text, error) }

    /** v4.3.0: страница сообщила, что на ней есть видео/аудио (элемент или медиа-запрос) — показать кнопку «Скачать». */
    @JavascriptInterface
    fun hasMedia(present: Boolean) {
        Handler(Looper.getMainLooper()).post { tab.hasMedia = present }
    }

    /** v4.8.0: позиция длинного видео — для «продолжить с места». */
    @JavascriptInterface
    fun position(key: String, seconds: Double, duration: Double) { MediaPositions.save(key, seconds, duration) }

    @JavascriptInterface
    fun savedPosition(key: String): Double = MediaPositions.get(key)

    /** v4.9.0: включён ли автозапуск следующего видео (настройка браузера). */
    @JavascriptInterface
    fun autoNext(): Boolean = MediaPositions.autoNext

    @JavascriptInterface
    fun webrtcTcp(): Boolean = MediaPositions.webrtcTcpAll

    @JavascriptInterface
    fun state(playing: Boolean) {
        val was = tab.mediaPlaying
        tab.mediaPlaying = playing
        if (was != playing) {
            com.winwasm64.vpn.VpnLog.i("Видео", "«${tab.title.take(40)}» — " + (if (playing) "играет" else "остановлено") +
                (if (tab.id == MediaState.activeTabId) " (активная вкладка" + (if (MediaState.inPip) ", PiP)" else ")") else " (фоновая вкладка)"))
        }
        if (tab.id == MediaState.activeTabId) {
            Handler(Looper.getMainLooper()).post { MediaState.activeMediaPlaying = playing }
        }
        if (playing && !was) {
            val h = MediaState.onTabStartedPlaying
            if (h != null) Handler(Looper.getMainLooper()).post { h(tab.id) }
        }
    }
}
