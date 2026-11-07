package com.winwasm64.vpn

/**
 * v4.11.0: единые ЧЕСТНЫЕ формулировки про область действия VPN.
 *
 * Причина появления: значок VPN в шторке и заголовки «VPN приложений» читались как «VPN для всех приложений»,
 * а на деле туннель охватывает только выбранные приложения (и раньше — ещё и жёстко прописанный YouTube).
 * Все места интерфейса (уведомление, список приложений, плитка) берут текст отсюда, чтобы не было расхождений.
 */
object VpnScope {

    /** Короткая подпись области действия для заголовка уведомления. */
    fun titleShort(perAppOnly: Boolean, count: Int): String =
        if (perAppOnly) "только $count прил." else "все приложения"

    /** Полная строка «кого касается VPN» — для подробного текста уведомления и шапки списка. */
    fun scopeLine(perAppOnly: Boolean, count: Int): String =
        if (perAppOnly) "VPN применяется ТОЛЬКО к $count прил. из списка — остальные приложения идут напрямую, без VPN"
        else "VPN применяется ко ВСЕМ приложениям, кроме OmniBrowser"

    /** Состояние одного приложения в списке. */
    fun appState(inVpn: Boolean): String =
        if (inVpn) "в VPN — трафик идёт через сервер" else "вне VPN — напрямую, VPN не применяется"

    /**
     * Пояснение, если приложение вне VPN, но работает: чтобы не принимали чужую заслугу за VPN.
     * null — приложение в VPN, пояснять нечего.
     */
    fun whyWorksWithoutVpn(inVpn: Boolean): String? =
        if (inVpn) null else "открывается без VPN — значит не заблокировано; VPN тут ни при чём"

    /** Что делает кнопка «Проверить мой IP»: две строки для сравнения. */
    fun ipCompare(directIp: String, vpnIp: String): String = when {
        directIp.isBlank() && vpnIp.isBlank() -> "не удалось проверить IP ни напрямую, ни через VPN"
        vpnIp.isBlank() -> "домашний IP: $directIp · через VPN проверить не удалось (VPN/прокси не поднят)"
        directIp.isBlank() -> "IP через VPN: $vpnIp (домашний проверить не удалось)"
        directIp == vpnIp -> "IP через VPN СОВПАДАЕТ с домашним ($directIp) — трафик идёт напрямую, IP не меняется"
        else -> "домашний IP: $directIp → через VPN: $vpnIp (IP изменился)"
    }
}
