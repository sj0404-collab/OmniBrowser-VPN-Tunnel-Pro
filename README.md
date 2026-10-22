# OmniBrowser & VPN Tunnel Pro (Android)

Браузер на WebView с настоящими расширениями из Chrome Web Store, встроенным обходом DPI и per-app VPN для других приложений (YouTube, Telegram, Lemur Browser…). Сборка — только в GitHub Actions, APK публикуется в Releases.

## Что внутри

| Модуль | Файлы | Что делает |
|---|---|---|
| Браузер | `ui/`, `MainActivity.kt` | Compose-оболочка над WebView: вкладки, omnibox, закладки, история, загрузки, инкогнито, десктоп-режим |
| Расширения | `extensions/` | Установка CRX2/CRX3 с серверов Google, `chrome.*` runtime (runtime/storage/tabs/scripting/alarms/declarativeNetRequest/proxy/webRequest.onAuthRequired), фоновые страницы, popup |
| Прокси расширений | `extensions/ExtensionProxy.kt` | Локальный прокси для `chrome.proxy` (PAC / fixed_servers), HTTP/HTTPS/SOCKS5 upstream, 407-авторизация через расширение, автотуннель через рабочие серверы Browsec, откат на обход DPI |
| VPN приложений | `vpn/BrowsecVpnService.kt` | `VpnService` с per-app списком; **userspace TCP-стек** (`TunTcpStack.kt`) → loopback → обработчик: серверы Browsec (если расширение включено) или обход DPI; QUIC → TCP; IPv6 → RST; DNS через DoH |
| Обход DPI | `vpn/BrowsecVpnService.kt` (`SmartAntiDpiPacketSender`, `BrowserDirectRepeater`) | Разрезание TLS ClientHello (SNI split / micro-split), DoH с ECS, резервные anycast-IP; «страны» в списках — это профили обхода, IP они не меняют |
| Журнал VPN | `vpn/VpnLog*.kt`, `vpn/VpnStatusView.kt` | Что включено, куда и с какой ошибкой подключалось каждое приложение; копирование/сохранение |
| Торренты | `torrent/BitTorrentEngine.kt` | Встроенный BitTorrent-клиент |

## Как работает VPN приложений (v4.1.0)

1. `VpnService.Builder` с `addAllowedApplication` для выбранных пакетов, маршрут `0.0.0.0/0`, DNS `10.215.173.2`.
2. Весь TCP приложений завершается в `TunTcpStack` (SYN-ACK, окна, повторы — в приложении, без зависимости от маршрутизации ядра).
3. Поток байт каждого соединения уходит loopback-сокетом в `handleTransparentClient`, который по локальному порту узнаёт исходный адрес назначения и владельца (`getConnectionOwnerUid`).
4. Маршрут: **серверы Browsec** (CONNECT через HTTPS-прокси из PAC расширения, учётные данные — от расширения) → иначе **прямое соединение с обходом DPI**.
5. UDP 443/80 получает ICMP «порт недоступен» (приложение переходит на TCP), DNS A-запросы — через DoH, AAAA/HTTPS — пусто.

## Сборка

Локально ничего собирать не нужно — любой push в `main` собирает подписанный release-APK (`.github/workflows/build.yml`), тег `v*` дополнительно публикует GitHub Release с заметками из `RELEASE_NOTES.md`.

```
git tag v4.1.0 && git push origin main v4.1.0
```

Юнит-тесты (`app/src/test`) запускаются в том же workflow; стек TCP и DNS-сборщик не зависят от Android и тестируются обычным JUnit.

## Подпись

Единый ключ `debug.keystore` (alias `androiddebugkey`), SHA-256 `b79d7958…` — обновления ставятся поверх любой версии 4.x.
