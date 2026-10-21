package org.mothvpn.android

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

class MothApplication : Application() {
    override fun onCreate() {
        super.onCreate(); instance = this; CrashLog.install(this); MothWidget.observe(this)
        Diagnostics.load() // the last ping of every server survives app restarts
        // Before libbox (Go) starts: Xray's TLS roots then include user-installed CAs (see TrustedCertificates).
        TrustedCertificates.exportPath(this); Thread { TrustedCertificates.write(this) }.start()
        Logger.i("App", "process started: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) pid=${android.os.Process.myPid()}")
    }
    companion object { lateinit var instance: MothApplication; private set }
}

object AppLanguage {
    private val app get() = MothApplication.instance
    @Volatile private var cached: Pair<String, Context>? = null
    fun tag(): String = if (Build.VERSION.SDK_INT >= 33)
        app.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags().ifEmpty { "system" }
        else app.getSharedPreferences("language", Context.MODE_PRIVATE).getString("tag", "system") ?: "system"
    fun select(tag: String) {
        require(tag in setOf("system", "en", "ru"))
        Logger.i("Settings", "language changed: $tag")
        // Non-sensitive preference also makes the language available to a cold-started tile on Android 8–12.
        app.getSharedPreferences("language", Context.MODE_PRIVATE).edit().putString("tag", tag).apply()
        AppCompatDelegate.setApplicationLocales(if (tag == "system") LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag))
    }
    fun context(): Context {
        if (Build.VERSION.SDK_INT >= 33) return app
        val tag = tag()
        if (tag == "system") return app
        val key = "$tag:${app.resources.configuration.hashCode()}"
        cached?.takeIf { it.first == key }?.let { return it.second }
        val config = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return app.createConfigurationContext(config).also { cached = key to it }
    }
}

fun tr(@StringRes id: Int, vararg args: Any): String {
    val context = AppLanguage.context()
    return if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
}

fun trPlural(@androidx.annotation.PluralsRes id: Int, count: Int, vararg args: Any): String =
    AppLanguage.context().resources.getQuantityString(id, count, *args)

/** Translate validation errors from the Android-independent parser without exposing subscription URLs. */
fun localizedError(message: String): String {
    val id = errorResources[message] ?: englishErrors[message]
    if (id != null) return tr(id)
    val transport = Regex("^Транспорт (.+) не поддерживается$").matchEntire(message)
    return if (transport != null) tr(R.string.msg_transport_type_is_not_supported_171, transport.groupValues[1]) else message
}
private val englishErrors by lazy {
    val app = MothApplication.instance
    val config = Configuration(app.resources.configuration).apply { setLocale(Locale.ENGLISH) }
    val english = app.createConfigurationContext(config)
    errorResources.values.associate { english.getString(it) to it }
}
private val errorResources = mapOf(
        "Неверный режим фрагментации" to R.string.msg_err_fragment,
        "Неверный режим мультиплексирования" to R.string.msg_err_mux,
        "AmneziaWG не поддерживается" to R.string.msg_err_amneziawg,
        "Нет поддерживаемых серверов в конфигурации sing-box" to R.string.msg_err_singbox_empty,
        "Неверный режим раздельного туннеля" to R.string.msg_err_split_mode,
        "Неверный интервал обновления подписки" to R.string.msg_err_sub_interval,
        "Неверный список приложений" to R.string.msg_err_split_apps,
        "Неверный DNS-сервер" to R.string.msg_err_dns,
        "Неверное правило маршрутизации" to R.string.msg_err_routing_rule,
        "Неверный порог предупреждения о трафике" to R.string.msg_err_traffic_warning,
        "Это не резервная копия Moth" to R.string.backup_err_not_backup,
        "Резервная копия создана более новой версией Moth" to R.string.backup_err_newer,
        "Неизвестный формат резервной копии" to R.string.backup_err_format,
        "Файл слишком большой" to R.string.backup_too_large,
        "Параллельных проверок: от 1 до 16" to R.string.msg_probe_parallel_range,
        "Таймаут пинга: от 500 до 30000 мс" to R.string.msg_probe_timeout_range,
        "Соединение ещё не проверено" to R.string.msg_connection_has_not_been_checked_yet_0,
        "Не ответил вовремя" to R.string.msg_server_timed_out_1,
        "Не удалось найти адрес вовремя" to R.string.msg_dns_lookup_timed_out_2,
        "Не удалось найти адрес сервера" to R.string.msg_could_not_resolve_the_server_address_3,
        "Порт сервера не отвечает" to R.string.msg_server_port_is_not_responding_4,
        "Не удалось проверить HTTPS" to R.string.msg_could_not_verify_https_5,
        "Проверка не прошла" to R.string.msg_check_failed_6,
        "VPN-сеть ещё не появилась" to R.string.msg_vpn_network_is_not_ready_yet_7,
        "Отключить VPN" to R.string.msg_disconnect_vpn_8,
        "Подключить VPN" to R.string.msg_connect_vpn_9,
        "Подключиться" to R.string.msg_connect_10,
        "VPN включён" to R.string.msg_vpn_is_on_11,
        "Подключаемся…" to R.string.msg_connecting_12,
        "Отключаем…" to R.string.msg_disconnecting_13,
        "Не удалось подключиться" to R.string.msg_could_not_connect_14,
        "Проверяем интернет…" to R.string.msg_checking_internet_access_15,
        "Добавить подписку" to R.string.msg_add_subscription_16,
        "Трафик: %.1f / %.1f ГиБ" to R.string.msg_traffic_1f_1f_gib_18,
        "Подписка до " to R.string.msg_subscription_expires_on_19,
        "Нет сети" to R.string.msg_no_network_20,
        "Мобильные данные" to R.string.msg_mobile_data_21,
        "Разрешение на VPN не выдано" to R.string.msg_vpn_permission_was_not_granted_22,
        "Не удалось прочитать сохранённую подписку. Добавь её снова." to R.string.msg_could_not_read_the_saved_subscription_add_it_23,
        "Не удалось прочитать настройки" to R.string.msg_could_not_read_settings_24,
        "Не удалось применить настройки" to R.string.msg_could_not_apply_settings_25,
        "Главная" to R.string.msg_home_26,
        "Серверы" to R.string.msg_servers_27,
        "Настройки" to R.string.msg_settings_28,
        "Вставь HTTPS-ссылку от своего VPN-провайдера. Она хранится только на устройстве." to R.string.msg_paste_the_https_link_from_your_vpn_provider_i_29,
        "Ссылка подписки" to R.string.msg_subscription_link_30,
        "Добавить" to R.string.msg_add_31,
        "Отмена" to R.string.msg_cancel_32,
        "Загружаем серверы…" to R.string.msg_downloading_servers_33,
        "Сервис подписки не ответил вовремя" to R.string.msg_subscription_service_timed_out_36,
        "Не удалось найти сервис подписки. Проверь интернет." to R.string.msg_could_not_find_the_subscription_service_check_37,
        "Не удалось проверить защищённое соединение с сервисом подписки" to R.string.msg_could_not_verify_the_secure_connection_to_the_38,
        "Не удалось добавить подписку" to R.string.msg_could_not_add_the_subscription_39,
        "Не удалось загрузить подписку. Проверь ссылку и интернет." to R.string.msg_could_not_download_the_subscription_check_the_40,
        "Не удалось сохранить выбранный сервер" to R.string.msg_could_not_save_the_selected_server_41,
        "Не удалось удалить подписку" to R.string.msg_could_not_remove_the_subscription_43,
        "Переключи сеть для этой проверки" to R.string.msg_switch_networks_to_run_this_check_44,
        "Не удалось запустить проверку или VPN" to R.string.msg_could_not_start_the_check_or_vpn_45,
        "Проверка отменена" to R.string.msg_check_cancelled_46,
        "Заменить подписку" to R.string.msg_replace_subscription_47,
        "Обновить подписку" to R.string.msg_update_subscription_48,
        "Отменить проверки" to R.string.msg_cancel_checks_49,
        "Проверить серверы" to R.string.msg_check_servers_50,
        "Удалить подписку" to R.string.msg_remove_subscription_52,
        "Без недоступных" to R.string.msg_hide_unavailable_56,
        "Поиск сервера" to R.string.msg_search_servers_57,
        "Добавь подписку" to R.string.msg_add_a_subscription_58,
        "Серверы не найдены" to R.string.msg_no_servers_found_59,
        "Показать все" to R.string.msg_show_all_60,
        ", выбран" to R.string.msg_selected_61,
        "проверяем…" to R.string.msg_checking_62,
        "не проверен" to R.string.msg_not_checked_64,
        "Порт доступен; работа VPN не проверена" to R.string.msg_port_is_reachable_vpn_traffic_has_not_been_ch_65,
        "Удалить подписку?" to R.string.msg_remove_subscription_67,
        "Для подключения понадобится снова добавить ссылку." to R.string.msg_the_subscription_link_will_need_to_be_added_a_68,
        "Удалить" to R.string.msg_remove_69,
        "Нужен корректный HTTPS-адрес" to R.string.msg_enter_a_valid_https_url_70,
        "Проверь настройки" to R.string.msg_check_your_settings_71,
        "Введи целое число" to R.string.msg_enter_a_whole_number_72,
        "Назад" to R.string.msg_back_73,
        "Интерфейс" to R.string.msg_appearance_74,
        "Подключение" to R.string.msg_connection_75,
        "Проверки" to R.string.msg_checks_76,
        "Подписка и HWID" to R.string.msg_subscription_and_hwid_77,
        "О приложении" to R.string.msg_about_78,
        "Тема" to R.string.msg_theme_79,
        "Как в системе" to R.string.msg_system_default_80,
        "Тёмная" to R.string.msg_dark_81,
        "Светлая" to R.string.msg_light_82,
        "Компактные строки серверов" to R.string.msg_compact_server_rows_83,
        "Сортировать по задержке" to R.string.msg_sort_by_latency_84,
        "Скрывать недоступные серверы" to R.string.msg_hide_unavailable_servers_85,
        "Скрываются серверы с неудачной проверкой за последние 15 минут. Непроверенные и проверяемые остаются в списке. Перепроверь серверы при смене сети." to R.string.msg_servers_with_failed_checks_in_the_last_15_min_86,
        "Можно транслировать экран и делать скриншоты. Ссылка подписки и HWID скрыты в полях ввода." to R.string.msg_screen_sharing_and_screenshots_are_allowed_su_87,
        "IPv6 через VPN" to R.string.msg_ipv6_through_vpn_89,
        "При выключении IPv6 блокируется внутри туннеля." to R.string.msg_when_disabled_ipv6_is_blocked_inside_the_tunn_90,
        "DNS для адресов серверов" to R.string.msg_server_bootstrap_dns_91,
        "Этот DNS используется напрямую для подключения к серверу. Если Cloudflare недоступен, можно указать другой DoH." to R.string.msg_this_dns_is_used_directly_to_connect_to_serve_92,
        "Отключи VPN и заверши проверки, чтобы менять эти параметры." to R.string.msg_disconnect_vpn_and_finish_checks_to_change_th_93,
        "Проверять активный туннель" to R.string.msg_check_the_active_tunnel_94,
        "Интервал · 30–600 секунд" to R.string.msg_interval_30_600_seconds_95,
        "Тайм-аут · 3–30 секунд" to R.string.msg_timeout_3_30_seconds_96,
        "HTTPS-адрес проверки" to R.string.msg_https_check_url_97,
        "Проверка идёт по HTTPS через сервер. Отказ этого адреса не доказывает недоступность всех сайтов." to R.string.msg_latency_includes_establishing_an_https_connec_98,
        "Способ проверки" to R.string.msg_check_method_99,
        "TCP не подтверждает работу VPN и не подходит для Hysteria/TUIC на UDP. HTTPS проверяет передачу трафика через сервер." to R.string.msg_tcp_does_not_verify_vpn_traffic_and_cannot_ch_103,
        "Заверши подключение и проверки, чтобы менять параметры подписки." to R.string.msg_disconnect_and_finish_checks_to_change_subscr_104,
        "Передавать HWID провайдеру" to R.string.msg_send_hwid_to_the_provider_105,
        "Постоянный идентификатор установки, без IMEI и серийного номера. Меняй его только если провайдер выдал конкретное значение." to R.string.msg_a_persistent_installation_identifier_without_106,
        "Скопировать HWID" to R.string.msg_copy_hwid_107,
        "Передавать модель и версию Android" to R.string.msg_send_device_model_and_android_version_108,
        "User-Agent подписки" to R.string.msg_subscription_user_agent_109,
        "Провайдер может выбирать формат подписки по User-Agent. HWID отправляется только исходному сайту подписки." to R.string.msg_providers_may_select_the_subscription_format_110,
        "sing-box 1.12.25 + Xray 26.10.17\\nОткрытый исходный код · GPL-3.0-or-later\\nhttps://github.com/Bu11t0N/Moth" to R.string.msg_sing_box_1_11_15_xray_25_3_6_nopen_source_gpl_111,
        "Нужна HTTPS-ссылка без логина и пароля в адресе" to R.string.msg_use_an_https_link_without_a_username_or_passw_112,
        "Достигнут лимит устройств подписки. Обратись к провайдеру." to R.string.msg_subscription_device_limit_reached_contact_the_113,
        "Провайдер требует HWID. Включи его отправку в настройках." to R.string.msg_the_provider_requires_hwid_enable_it_in_setti_114,
        "Нет адреса перенаправления" to R.string.msg_redirect_destination_is_missing_115,
        "Список серверов слишком большой" to R.string.msg_the_server_list_is_too_large_116,
        "Подписка недоступна. Проверь ссылку и срок действия." to R.string.msg_subscription_is_unavailable_check_the_link_an_117,
        "Сервис подписки не ответил успешно. Попробуй позже." to R.string.msg_subscription_service_returned_an_error_try_ag_118,
        "Слишком много перенаправлений" to R.string.msg_too_many_redirects_119,
        "Проверяем интернет через VPN…" to R.string.msg_checking_internet_access_through_vpn_120,
        "Проверка отключена в настройках" to R.string.msg_checks_are_disabled_in_settings_121,
        "VPN-подключение" to R.string.msg_vpn_connection_125,
        "Отключить" to R.string.msg_disconnect_126,
        "Интернет через VPN доступен" to R.string.msg_internet_access_through_vpn_is_available_127,
        "Проверка интернета через VPN не прошла" to R.string.msg_internet_check_through_vpn_failed_128,
        "UDP-протокол: выбери HTTPS-проверку" to R.string.msg_udp_protocol_choose_an_https_check_129,
        "Нет адреса" to R.string.msg_no_address_found_133,
        "Нужна Clash-подписка с разделом proxies" to R.string.msg_use_a_clash_subscription_with_a_proxies_list_134,
        "Clash: нужен встроенный список proxies; proxy-providers пока не поддерживаются" to R.string.msg_clash_an_embedded_proxies_list_is_required_pr_135,
        "Слишком много серверов" to R.string.msg_too_many_servers_136,
        "Clash: нет совместимых серверов; проверь протоколы и параметры TLS" to R.string.msg_clash_no_supported_profiles_check_the_format_137,
        "Небезопасный TLS" to R.string.msg_unsafe_tls_configuration_138,
        "Зависимый профиль не поддерживается" to R.string.msg_proxy_chains_are_not_supported_139,
        "Нестандартные WS headers" to R.string.msg_unsupported_websocket_headers_140,
        "WS early data пока не поддерживается" to R.string.msg_websocket_early_data_is_not_supported_yet_141,
        "TUIC v4 не поддерживается" to R.string.msg_tuic_v4_is_not_supported_142,
        "Неподдерживаемый протокол" to R.string.msg_unsupported_protocol_143,
        "Неверная тема" to R.string.msg_invalid_theme_144,
        "Неверный способ проверки" to R.string.msg_invalid_check_method_145,
        "MTU: от 1280 до 9000" to R.string.msg_mtu_must_be_between_1280_and_9000_146,
        "Тайм-аут: от 1000 до 60000 мс" to R.string.msg_timeout_must_be_between_3_and_30_seconds_147,
        "Интервал проверки: от 30 до 600 секунд" to R.string.msg_check_interval_must_be_between_30_and_600_sec_148,
        "HWID: 10–64 символа, буквы, цифры, = или -" to R.string.msg_hwid_10_64_letters_digits_or_149,
        "User-Agent должен содержать печатные латинские символы" to R.string.msg_user_agent_must_contain_printable_ascii_chara_150,
        "Отключение проверки TLS не поддерживается" to R.string.msg_disabling_tls_verification_is_not_supported_151,
        "Нет адреса сервера" to R.string.msg_server_address_is_missing_152,
        "Hysteria port hopping пока не поддерживается" to R.string.msg_hysteria_port_hopping_is_not_supported_yet_153,
        "Нет пароля Hysteria2" to R.string.msg_hysteria2_password_is_missing_154,
        "Неверные параметры обфускации Hysteria2" to R.string.msg_invalid_hysteria2_obfuscation_settings_155,
        "Hysteria поддерживает UDP transport" to R.string.msg_hysteria_requires_udp_transport_156,
        "Для Hysteria нужны upmbps и downmbps" to R.string.msg_hysteria_requires_upmbps_and_downmbps_157,
        "TUIC v5: нужны UUID и пароль" to R.string.msg_tuic_v5_requires_a_uuid_and_password_158,
        "Нет ключа сервера" to R.string.msg_server_key_is_missing_159,
        "Неизвестное шифрование VLESS" to R.string.msg_unsupported_vless_encryption_160,
        "XHTTP не поддерживает Vision flow" to R.string.msg_xhttp_does_not_support_vision_flow_161,
        "Неизвестный режим XHTTP" to R.string.msg_unsupported_xhttp_mode_162,
        "Слишком сложные параметры XHTTP" to R.string.msg_xhttp_extra_settings_are_too_large_163,
        "Нет публичного ключа REALITY" to R.string.msg_reality_public_key_is_missing_164,
        "Нет поддерживаемых серверов. Нужны ссылки VLESS, VMess, Trojan, Shadowsocks, Hysteria2, TUIC, SOCKS, HTTP, WireGuard или SSH, либо конфигурация Clash, Xray или sing-box." to R.string.msg_no_supported_servers_use_vless_vmess_trojan_o_165,
        "VMess alterId не поддерживается" to R.string.msg_vmess_alterid_is_not_supported_166,
        "Протокол не поддерживается" to R.string.msg_transport_is_not_supported_167,
        "Неверный адрес сервера" to R.string.msg_invalid_server_address_168,
        "Небезопасная проверка TLS не поддерживается" to R.string.msg_insecure_tls_settings_are_not_supported_169,
        "Shadowsocks plugin не поддерживается" to R.string.msg_shadowsocks_plugins_are_not_supported_170,
)
