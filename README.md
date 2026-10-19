# Windows 11 WASM64 Workstation Android APK (`v1.0.0`)

Полноценная рабочая среда **Windows 11 в WebAssembly 64-bit (`WASM64` / `Memory64`)** в виде автономного Android APK:
- **Ядро WASM64 (`ntoskrnl.wasm`)**: компиляция и исполнение настоящих 64-битных модулей WebAssembly (`\0asm\x01\x00\x00\x00` + `Memory64` + 64-bit `i64` ALU и линейная память) с многоядерным ускорением на процессоре телефона.
- **Программы Windows**: Проводник (`explorer.exe`), редактор кода VS Code / Notepad++ (`code.exe`), 64-битный инженерный калькулятор WASM64 (`calc.exe`), Диспетчер задач (`taskmgr.exe`).
- **Игры Windows со звуком (60 FPS + PCM 44.1kHz)**:
  - **DOOM / Wolf3D WASM64 3D Raycaster (`doom_wasm64.exe`)**
  - **Сапёр Windows 11 Pro (`winmine.exe`)**
- **Терминал PowerShell + Node.js v22.11.0 + npm 10.9.0**: выполнение JS-скриптов, работа с файловой системой `C:\`, установка пакетов `npm`.
- **YouTube со звуком (44.1kHz PCM + авто-пропуск рекламы + усилитель звука 200%)**.
- **Браузер Chromium/Edge с рабочими расширениями**: **AdBlock Pro**, **Browsec VPN** (6 стран + Веб-Прокси туннель), **SponsorBlock**, **Dark Reader**, **Tampermonkey**, **DevTools (Eruda)**.
