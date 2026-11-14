package com.ffvpn.client

import com.ffvpn.client.core.AppLanguage
import com.ffvpn.client.core.AppState
import com.ffvpn.client.core.Prefs

object Texts {
    private val language: AppLanguage get() = Prefs.language

    /**
     * The whole app in five languages: one line per string, English first.
     * Fixed lines take their Chinese wording from [zhText]; a line built from
     * values passes [zh] itself, because it cannot be looked up by its English.
     */
    private fun p(en: String, fa: String, ru: String, es: String, zh: String? = null): String =
        when (language) {
            // Persian is the only right-to-left language here, so it is where a
            // Latin word or a number has to be pinned in reading order.
            AppLanguage.FA -> Bidi.isolate(fa)
            AppLanguage.RU -> ru
            AppLanguage.ES -> es
            AppLanguage.EN -> en
            AppLanguage.ZH -> zh ?: zhText(en)
        }

    /** For a value that did not come from [p], such as an engine's own message. */
    fun ui(text: String): String =
        if (language == AppLanguage.FA) Bidi.isolate(text) else text

    /** Spanish is the only one of the four whose nouns agree with the number in front of them. */
    private fun es(count: Int, one: String, many: String): String =
        if (count == 1) one else many

    val appName: String
        get() = p("Niloofar VPN", "نیلوفر وی‌پی‌ان", "Niloofar VPN", "Niloofar VPN")

    val settings: String
        get() = p("Settings", "تنظیمات", "Настройки", "Ajustes")
    val back: String
        get() = p("Back", "بازگشت", "Назад", "Atrás")
    val auto: String
        get() = p("Auto", "خودکار", "Авто", "Auto")

    val languageLabel: String
        get() = p("Language", "زبان", "Язык", "Idioma")
    val languageHint: String
        get() = p(
            "Applies instantly across the app.",
            "فوراً در کل برنامه اعمال می‌شود.",
            "Мгновенно применяется во всём приложении.",
            "Se aplica al instante en toda la aplicación."
        )

    val connection: String
        get() = p("Connection", "اتصال", "Соединение", "Conexión")
    val mode: String
        get() = p("Mode", "حالت اتصال", "Режим", "Modo")

    fun modeLabel(mode: AppState.Mode): String = when (mode) {
        AppState.Mode.VPN -> p("VPN · whole device", "وی‌پی‌ان (کل دستگاه)", "VPN · всё устройство", "VPN · todo el dispositivo")
        AppState.Mode.PROXY -> p("Proxy · SOCKS5 + HTTP", "پروکسی (ساکس و اچ‌تی‌تی‌پی)", "Прокси · SOCKS5 + HTTP", "Proxy · SOCKS5 + HTTP")
    }

    fun modeShort(mode: AppState.Mode): String = when (mode) {
        AppState.Mode.VPN -> p("VPN", "وی‌پی‌ان", "VPN", "VPN")
        AppState.Mode.PROXY -> p("Proxy", "پروکسی", "Прокси", "Proxy")
    }

    val modeHint: String
        get() = p(
            "In VPN mode every app goes through the tunnel. In proxy mode only apps configured to use the local SOCKS5 proxy do.",
            "در حالت وی‌پی‌ان همه ترافیک دستگاه از تونل عبور می‌کند. " +
                "در حالت پروکسی فقط برنامه‌هایی که پروکسی SOCKS5 را تنظیم کرده‌اند.",
            "В режиме VPN весь трафик устройства идёт через туннель. В режиме прокси — только те приложения, где настроен локальный SOCKS5.",
            "En el modo VPN todo el tráfico de las aplicaciones pasa por el túnel. En el modo proxy solo lo hacen las aplicaciones configuradas para usar el proxy SOCKS5 local."
        )

    val core: String
        get() = p("Core", "هسته", "Ядро", "Núcleo")

    val coreHint: String
        get() = p(
            "The engine that carries the tunnel. Firefox uses your account; the others need no sign-in.",
            "موتوری که تونل را حمل می‌کند. فایرفاکس با حساب شما کار می‌کند؛ بقیه ورود نمی‌خواهند.",
            "Движок, который держит туннель. Firefox работает через ваш аккаунт, остальным вход не нужен.",
            "El motor que lleva el túnel. Firefox usa tu cuenta; los demás no piden sesión."
        )

    /** The engine behind each core, shown plainly in the picker. */
    fun coreName(id: String): String = when (id) {
        "aether" -> p("Aether", "اتر", "Эфир", "Aether")
        "psiphon" -> p("Psiphon", "سایفون", "Псифон", "Psiphon")
        "sunlion" -> p("Sun and Lion", "شیر و خورشید", "Солнце и Лев", "Sol y León")
        "sstp" -> p("SSTP", "اس‌اس‌تی‌پی", "SSTP", "SSTP")
        "lantern" -> p("Lantern", "لنترن", "Лантерн", "Lantern")
        "firefox" -> p("Firefox", "فایرفاکس", "Файрфокс", "Firefox")
        "tor" -> p("Tor", "تور", "Тор", "Tor")
        else -> id
    }

    val engineMissing: String
        get() = p(
            "This core is not available on this device.",
            "این هسته روی این دستگاه در دسترس نیست.",
            "Это ядро недоступно на данном устройстве.",
            "Este núcleo no está disponible en este dispositivo."
        )

    val endpointNotFound: String
        get() = p(
            "No working route was found. Check the network and try again.",
            "هیچ مسیر سالمی پیدا نشد. شبکه را بررسی کنید و دوباره تلاش کنید.",
            "Рабочий маршрут не найден. Проверьте сеть и попробуйте снова.",
            "No se encontró ninguna ruta funcional. Revisa la red e inténtalo de nuevo."
        )

    /**
     * Stage reported by the engines that keep their own server database: on the
     * first run they must fill it before any tunnel can be dialled.
     */
    const val STAGE_SERVER_DATABASE = "building server database"

    val serverDatabaseBootstrap: String
        get() = p(
            "The core is downloading server information. The first connection takes a while, please wait.",
            "هسته در حال دانلود اطلاعات سرورها است، اولین اتصال زمانبر هست، صبور باشید",
            "Ядро загружает сведения о серверах. Первое соединение занимает время, пожалуйста, подождите.",
            "El núcleo está descargando información de servidores. La primera conexión tarda un rato, espera."
        )

    /** Firefox has no anonymous free tier: it rides on the user's own account. */
    val firefoxSignInRequired: String
        get() = p(
            "Sign in to your Firefox Account in Settings first.",
            "اول از تنظیمات با حساب فایرفاکس خود وارد شوید.",
            "Сначала войдите в свой аккаунт Firefox в Настройках.",
            "Inicia primero tu sesión en la cuenta de Firefox dentro de Ajustes."
        )

    val firefoxAccount: String
        get() = p("Firefox Account", "حساب فایرفاکس", "Аккаунт Firefox", "Cuenta de Firefox")
    val firefoxAccountHint: String
        get() = p(
            "The Firefox core uses the free proxy that comes with any Firefox Account — no subscription. Only the sign-in token is kept, never the password.",
            "هستهٔ فایرفاکس از پروکسی رایگان که همراه هر حساب فایرفاکس است استفاده می‌کند — بدون اشتراک. " +
                "فقط توکن ورود نگه داشته می‌شود، هرگز رمز عبور.",
            "Ядро Firefox использует бесплатный прокси, который есть у любого аккаунта Firefox — без подписки. Сохраняется только токен входа, пароль — никогда.",
            "El núcleo de Firefox usa el proxy gratuito que incluye cualquier cuenta de Firefox, sin suscripción. Solo se guarda el token de sesión, nunca la contraseña."
        )
    val firefoxEmail: String
        get() = p("Email", "ایمیل", "Эл. почта", "Correo electrónico")
    val firefoxSignUp: String
        get() = p(
            "No Firefox Account yet? Sign up for free",
            "حساب فایرفاکس ندارید؟ رایگان ثبت‌نام کنید",
            "Нет аккаунта Firefox? Создайте его бесплатно",
            "¿Aún no tienes cuenta de Firefox? Regístrate gratis"
        )
    val noBrowser: String
        get() = p(
            "No browser is available to open the link.",
            "مرورگری برای باز کردن پیوند در دسترس نیست.",
            "Браузера, чтобы открыть ссылку, нет.",
            "No hay ningún navegador disponible para abrir el enlace."
        )
    val firefoxPassword: String
        get() = p("Password", "رمز عبور", "Пароль", "Contraseña")
    val signIn: String
        get() = p("Sign in", "ورود", "Войти", "Iniciar sesión")
    val signingIn: String
        get() = p("Signing in…", "در حال وارد شدن…", "Входим…", "Iniciando sesión…")
    val signOut: String
        get() = p("Sign out", "خروج", "Выйти", "Cerrar sesión")
    val firefoxNotSignedIn: String
        get() = p("Not signed in", "وارد نشده‌اید", "Вы не вошли", "Sin sesión iniciada")

    fun signedInAs(email: String): String =
        p("Signed in as $email", "وارد شده با $email", "Вы вошли как $email", "Sesión iniciada como $email", "已登录：$email")

    fun firefoxQuota(usage: String): String =
        p("Free bandwidth: $usage", "پهنای باند رایگان: $usage", "Бесплатный трафик: $usage", "Ancho de banda gratuito: $usage", "免费流量：$usage")

    val firefoxFieldsMissing: String
        get() = p(
            "Enter both the email and the password.",
            "هم ایمیل و هم رمز عبور را وارد کنید.",
            "Введите и почту, и пароль.",
            "Escribe el correo y la contraseña."
        )

    // ── Config tools: the card in Settings and the screen behind it ──

    val tools: String
        get() = p("Config tools", "ابزارهای تنظیمات", "Инструменты настройки", "Herramientas de configuración")
    val toolsBody: String
        get() = p(
            "Two tools collect the addresses the cores dial: an edge scanner for Sun and Lion, the VPN Gate list for SSTP. Both work from this device.",
            "دو ابزار آدرس‌های هسته‌ها را جمع می‌کنند: اسکنر لبهٔ سی‌دی‌ان برای شیر و خورشید، و فهرست وی‌پی‌ان گیت برای sstp. هر دو از همین دستگاه کار می‌کنند.",
            "Два инструмента собирают адреса для ядер: сканер CDN для Sun and Lion и список SSTP для VPN Gate. Оба работают с этого устройства.",
            "Dos herramientas recogen las direcciones que marcan los núcleos: un escáner de bordes para Sol y León, y la lista de VPN Gate para SSTP. Las dos funcionan desde este dispositivo."
        )
    val toolsHint: String
        get() = p(
            "Nothing is saved until you tap «Use these». With «Delete the old list» on, what the core used before is removed first; with it off, the new entries are added to it.",
            "تا «این‌ها را استفاده کن» را نزنید، چیزی نوشته نمی‌شود. با روشن بودن «فهرست قبلی حذف شود» اول لیست قبلی پاک می‌شود؛ با خاموش، جدیدها به آن اضافه می‌شوند.",
            "Ничего не сохранится, пока вы не нажмёте «Использовать это». Если «Удалить старый список» включён, старые адреса сначала удаляются; если выключен — новые добавляются к ним.",
            "Nada se guarda hasta que pulses «Usar estas». Si «Borrar la lista anterior» está activado, primero se elimina lo que usaba el núcleo; si está desactivado, las entradas nuevas se añaden a esa lista."
        )

    val replaceLists: String
        get() = p("Delete the old list", "فهرست قبلی حذف شود", "Удалить старый список", "Borrar la lista anterior")

    /** Said out loud when a second scan is tapped: the click landed, it is just too early. */
    val busyAlready: String
        get() = p(
            "One task is already running here. Wait for it or press stop.",
            "یک کار همین حالا در حال اجراست. صبر کنید یا دکمهٔ توقف را بزنید.",
            "Здесь уже выполняется задача. Подождите или нажмите «остановить».",
            "Ya hay una tarea en ejecución aquí. Espera a que termine o pulsa parar."
        )

    fun toolsInUse(ips: Int, snis: Int, hosts: Int, relays: Int): String =
        p(
            "Sun and Lion uses $ips IP(s) and $snis SNI value(s), $hosts host(s) · SSTP uses $relays relay(s)",
            "شیر و خورشید: $ips آی‌پی و $snis sni و $hosts هاست · sstp: $relays سرور",
            "Sun and Lion: $ips IP и $snis SNI, $hosts host · SSTP: $relays ретрансляторов",
            "Sol y León usa $ips IP y $snis ${es(snis, "valor", "valores")} SNI, $hosts host · SSTP usa $relays ${es(relays, "relé", "relés")}",
            "太阳与狮子使用 $ips 个 IP、$snis 个 SNI 值和 $hosts 个主机 · SSTP 使用 $relays 个中继"
        )

    val ipScanner: String
        get() = p("IP scanner", "آی‌پی اسکنر", "Сканер IP", "Escáner de IP")
    val vpngate: String
        get() = p("VPN Gate SSTP servers", "سرورهای sstp وی‌پی‌ان گیت", "SSTP-серверы VPN Gate", "Servidores VPN Gate SSTP")

    val preset: String
        get() = p("CDN group", "گروه سی‌دی‌ان", "Группа CDN", "Grupo CDN")
    val ipRanges: String
        get() = p("IP or CIDR list", "فهرست آی‌پی یا سی‌آی‌دی‌آر", "Список IP или CIDR", "Lista de IP o CIDR")
    val ipRangesHint: String
        get() = p(
            "One per line; 104.16.0.0/16 and 172.64.0.1-172.64.0.9 both work. Leave it empty to scan the group.",
            "هر خط یکی؛ 104.16.0.0/16 و 172.64.0.1-172.64.0.9 هر دو خوانده می‌شوند. خالی بگذارید تا خود گروه اسکن شود.",
            "По одному в строке; 104.16.0.0/16 и 172.64.0.1-172.64.0.9 тоже подходят. Оставьте пустым, чтобы сканировать группу.",
            "Uno por línea; 104.16.0.0/16 y 172.64.0.1-172.64.0.9 valen. Déjalo vacío para escanear el grupo."
        )
    val sniValues: String
        get() = p("SNI values", "مقادیر SNI", "Значения SNI", "Valores SNI")
    val sniValuesHint: String
        get() = p(
            "One per line. Empty means: try the group’s own list and keep whatever answers.",
            "هر خط یکی. خالی یعنی فهرست خود گروه امتحان شود و فقط جواب‌گیرها بمانند.",
            "По одному в строке. Пусто — проверить список самой группы и оставить те, что ответили.",
            "Uno por línea. Vacío significa: probar la lista propia del grupo y conservar lo que responda."
        )
    val httpHosts: String
        get() = p("HTTP Host values", "مقادیر HTTP Host", "Значения HTTP Host", "Valores Host de HTTP")
    val httpHostsHint: String
        get() = p(
            "One per line. The name the request asks for in its Host header; it has to differ from the SNI. Empty means the group’s own list.",
            "هر خط یکی. نامی که درخواست در سرِ Host می‌فرستد و باید با SNI فرق داشته باشد. خالی یعنی فهرست خود گروه.",
            "По одному в строке. Имя в заголовке Host; оно должно отличаться от SNI. Пусто — список самой группы.",
            "Uno por línea. Es el nombre que la petición escribe en su cabecera Host; tiene que ser distinto del SNI. Vacío significa la lista propia del grupo."
        )
    val concurrency: String
        get() = p("Parallel probes", "تست هم‌زمان", "Параллельные проверки", "Sondas en paralelo")
    val scanDepth: String
        get() = p("Scan depth", "عمق اسکن", "Глубина сканирования", "Profundidad del escaneo")
    val modeQuick: String
        get() = p("Quick", "سریع", "Быстрая", "Rápido")
    val modeBalanced: String
        get() = p("Balanced", "متوازن", "Сбалансированная", "Equilibrado")
    val modeDeep: String
        get() = p("Deep", "کامل", "Полная", "Profundo")

    fun modeDescription(samples: Int, cap: Int): String =
        p(
            if (samples == 0) "Every address in the list, up to $cap."
            else "$samples sample(s) per network, up to $cap addresses.",
            if (samples == 0) "همهٔ آدرس‌های فهرست، حداکثر $cap."
            else "هر شبکه $samples نمونه، حداکثر $cap آدرس.",
            if (samples == 0) "Все адреса списка, не больше $cap."
            else "По $samples образцу на сеть, не больше $cap адресов.",
            if (samples == 0) "Todas las direcciones de la lista, hasta $cap."
            else "$samples muestras por red, como máximo $cap direcciones.",
            if (samples == 0) "列表中的全部地址，最多 $cap 个。"
            else "每个网络 $samples 个样本，最多 $cap 个地址。"
        )

    val scoutSnis: String
        get() = p("Find working SNIs", "پیدا کردن sni سالم", "Найти рабочие SNI", "Buscar SNI que funcionen")
    val scoutingSnis: String
        get() = p("Probing SNIs…", "در حال تست sni…", "Проверяем SNI…", "Probando valores SNI…")

    fun scoutResult(snis: Int, hosts: Int): String =
        p(
            "$snis SNI value(s) and $hosts host(s) answered",
            "$snis sni و $hosts هاست پاسخ دادند",
            "Ответили SNI: $snis, хостов: $hosts",
            "$snis ${es(snis, "valor", "valores")} SNI y $hosts host respondieron",
            "$snis 个 SNI 值和 $hosts 个主机有响应"
        )

    val scoutNoSni: String
        get() = p(
            "No SNI answered from this network.",
            "هیچ sni از این شبکه پاسخ نداد.",
            "Из этой сети не ответил ни один SNI.",
            "Ningún SNI respondió desde esta red."
        )

    val startScan: String
        get() = p("Start scan", "شروع اسکن", "Начать сканирование", "Empezar el escaneo")
    val stopScan: String
        get() = p("Stop", "توقف", "Стоп", "Parar")
    val scanning: String
        get() = p("Scanning…", "در حال اسکن…", "Сканируем…", "Escaneando…")

    fun scanProgress(done: Int, total: Int, usable: Int): String =
        p(
            "Scanned $done/$total · $usable usable",
            "$done از $total اسکن شد · $usable قابل استفاده",
            "Просканировано $done/$total · доступно $usable",
            "Escaneadas $done/$total · $usable útiles",
            "已扫描 $done/$total · $usable 个可用"
        )

    val scanNoHits: String
        get() = p(
            "No address passed the test.",
            "هیچ آدرسی تست را رد نشد.",
            "Ни один адрес не прошёл проверку.",
            "Ninguna dirección superó la prueba."
        )

    fun scanFinished(usable: Int, total: Int): String =
        p("$usable usable out of $total", "$usable قابل استفاده از $total", "Доступно $usable из $total", "$usable útiles de $total", "$total 个中有 $usable 个可用")

    val copyAll: String
        get() = p("Copy all", "کپی همه", "Копировать всё", "Copiar todo")
    val copied: String
        get() = p("Copied", "کپی شد", "Скопировано", "Copiado")
    val useTop5: String
        get() = p("Use these 5", "این ۵ را استفاده کن", "Использовать эти 5", "Usar estas 5")
    val useTop20: String
        get() = p("Use these 20", "این ۲۰ را استفاده کن", "Использовать эти 20", "Usar estas 20")

    fun scannerKept(replaced: Boolean, ips: Int, snis: Int, hosts: Int): String =
        p(
            if (replaced) "Old list deleted · $ips IP(s), $snis SNI value(s), $hosts host(s)"
            else "Added to the old list · $ips IP(s), $snis SNI value(s), $hosts host(s)",
            if (replaced) "فهرست قبلی حذف شد · $ips آی‌پی، $snis sni و $hosts هاست"
            else "به فهرست قبلی اضافه شد · $ips آی‌پی، $snis sni و $hosts هاست",
            if (replaced) "Старый список удалён · $ips IP, $snis SNI, $hosts хостов"
            else "Добавлено к старому списку · $ips IP, $snis SNI, $hosts хостов",
            if (replaced) "Lista anterior borrada · $ips IP, $snis ${es(snis, "valor", "valores")} SNI, $hosts host"
            else "Añadido a la lista anterior · $ips IP, $snis ${es(snis, "valor", "valores")} SNI, $hosts host",
            if (replaced) "旧列表已删除 · $ips 个 IP、$snis 个 SNI 值、$hosts 个主机"
            else "已添加到旧列表 · $ips 个 IP、$snis 个 SNI 值、$hosts 个主机"
        )

    fun edgeRow(ip: String, sni: String, host: String, latencyMs: Int, ok: Int, tries: Int): String =
        p("$ip · $sni → $host · ${latencyMs}ms · $ok/$tries", "$ip · $sni · $host · ${latencyMs}ms · $ok/$tries",
            "$ip · $sni → $host · ${latencyMs}мс · $ok/$tries", "$ip · $sni → $host · ${latencyMs}ms · $ok/$tries",
            "$ip · $sni → $host · ${latencyMs}毫秒 · $ok/$tries")

    val fetchServers: String
        get() = p("Fetch the list", "گرفتن فهرست", "Загрузить список", "Obtener la lista")
    val fetchingServers: String
        get() = p("Reading vpngate.net…", "در حال خواندن vpngate.net…", "Читаем vpngate.net…", "Leyendo vpngate.net…")

    fun fetchedCount(count: Int): String =
        p(
            "$count SSTP server(s) on the page",
            "$count سرور sstp در صفحه پیدا شد",
            "На странице найдено SSTP-серверов: $count",
            "$count ${es(count, "servidor", "servidores")} SSTP en la página",
            "页面上有 $count 个 SSTP 服务器"
        )

    val fetchFailed: String
        get() = p(
            "vpngate.net could not be read. Use your VPN, and try again.",
            "vpngate.net خوانده نشد. با فیلترشکن امتحان کنید یا بعداً دوباره تلاش کنید.",
            "Не удалось прочитать vpngate.net. Попробуйте через VPN или позже.",
            "No se pudo leer vpngate.net. Usa tu VPN e inténtalo de nuevo."
        )
    val testHealth: String
        get() = p("Test which answer", "کدام‌ها جواب می‌دهند؟", "Какие отвечают?", "Probar cuáles responden")
    val testingHealth: String
        get() = p("Testing relays…", "در حال تست سرورها…", "Проверяем ретрансляторы…", "Probando relés…")

    fun healthResult(alive: Int, tcpOnly: Int, dead: Int): String =
        p(
            "$alive speak TLS, $tcpOnly TCP only, $dead silent",
            "$alive با tls، $tcpOnly فقط tcp، $dead بی‌صدا",
            "$alive работают по TLS, $tcpOnly — только TCP, $dead молчат",
            "$alive hablan TLS, $tcpOnly solo TCP, $dead silenciosos",
            "$alive 个支持 TLS，$tcpOnly 个仅 TCP，$dead 个无响应"
        )

    val restoreBuiltIn: String
        get() = p(
            "Delete the installed list (use the built-in one)",
            "حذف فهرست نصب‌شده (بازگشت به فهرست خود برنامه)",
            "Удалить установленный список (вернуть встроенный)",
            "Borrar la lista instalada (usar la incorporada)"
        )
    val vpngateFilter: String
        get() = p(
            "Skip Iran, Russia, China and North Korea",
            "سرورهای ایران، روسیه، چین و کره شمالی حذف شوند",
            "Пропустить Иран, Россию, Китай и КНДР",
            "Omitir Irán, Rusia, China y Corea del Norte"
        )

    fun filterResult(hidden: Int, shown: Int): String =
        p(
            "$hidden hidden by the country filter, $shown in the list",
            "$hidden با فیلتر کشوری حذف شد، $shown در فهرست است",
            "Фильтр по странам скрыл $hidden, в списке $shown",
            "$hidden ocultos por el filtro de países, $shown en la lista",
            "国家过滤已隐藏 $hidden 个，列表中有 $shown 个"
        )

    val vpngateHint: String
        get() = p(
            "VPN Gate publishes servers that change every day; a country flag with no relay behind it is normal. The list is fetched without a tunnel, so if the page does not load, open this screen while another core is connected.",
            "وی‌پی‌ان گیت سرورهایش را هر روز عوض می‌کند و پرچم کش بدون سرورِ زنده طبیعی است. این فهرست بدون تونل خوانده می‌شود؛ اگر صفحه باز نشد، همین صفحه را وقتی با هستهٔ دیگری وصل هستید باز کنید.",
            "VPN Gate меняет серверы каждый день; флаг страны без ретранслятора — это нормально. Список читается без туннеля, поэтому если страница не открылась, зайдите сюда, пока подключено другое ядро.",
            "VPN Gate publica servidores que cambian cada día; una bandera de país sin relé detrás es normal. La lista se descarga sin túnel, así que si la página no carga, abre esta pantalla con otro núcleo conectado."
        )

    fun relaysInstalled(count: Int): String =
        p(
            "$count relay(s) installed for SSTP",
            "$count سرور برای sstp نصب شد",
            "Для SSTP установлено ретрансляторов: $count",
            "$count ${es(count, "relé instalado", "relés instalados")} para SSTP",
            "已为 SSTP 安装 $count 个中继"
        )

    val builtInInUse: String
        get() = p(
            "The built-in list is in use",
            "فهرست خود برنامه در حال استفاده است",
            "Используется встроенный список",
            "Se está usando la lista incorporada"
        )

    val torBridges: String
        get() = p("Tor bridges", "بریج‌های تور", "Мосты Tor", "Puentes de Tor")
    val torBridgesSwitchHint: String
        get() = p(
            "Off: Tor dials a public relay. On: it goes through an obfs4 bridge.",
            "خاموش: تور به یک رله عمومی وصل می‌شود. روشن: از پل obfs4 عبور می‌کند.",
            "Выкл.: Tor подключается к публичному узлу. Вкл.: идёт через obfs4-мост.",
            "Apagado: Tor usa un nodo público. Encendido: pasa por un puente obfs4."
        )

    val torBridgesSwitch: String
        get() = p(
            "Connect through an obfs4 bridge",
            "اتصال از طریق بریج obfs4",
            "Подключаться через obfs4-мост",
            "Conectar a través de un puente obfs4"
        )
    val psiphonConduit: String
        get() = p(
            "Psiphon Conduit",
            "کاندوئیت سایفون",
            "Psiphon Conduit",
            "Psiphon Conduit",
            "Psiphon Conduit"
        )
    val psiphonConduitSwitchHint: String
        get() = p(
            "Off: straight to Psiphon's own servers. On: through volunteer WebRTC relays — slower, but it gets through where those servers are blocked.",
            "خاموش: مستقیم به سرورهای خود سایفون. روشن: از رله‌های داوطلبانهٔ WebRTC — کندتر، ولی جایی که آن سرورها بسته‌اند رد می‌شود.",
            "Выкл.: напрямую к серверам Psiphon. Вкл.: через добровольные WebRTC-узлы — медленнее, но проходит там, где серверы заблокированы.",
            "Apagado: directo a los servidores de Psiphon. Encendido: por nodos WebRTC voluntarios — más lento, pero pasa donde esos servidores están bloqueados.",
            "关闭：直连 Psiphon 服务器。开启：通过志愿者 WebRTC 中继——较慢，但在服务器被封时仍能连上。"
        )

    val psiphonConduitSwitch: String
        get() = p(
            "Connect through Conduit",
            "اتصال از طریق کاندوئیت",
            "Подключаться через Conduit",
            "Conectar a través de Conduit",
            "通过 Conduit 连接"
        )
    val psiphonConduitHint: String
        get() = p(
            "Only the Psiphon core reads this. Turning it on narrows the engine to its inproxy transports, so the tunnel is carried by Conduit relays instead of Psiphon's entry points.",
            "فقط هستهٔ سایفون این را می‌خواند. روشن‌کردنش موتور را به ترنسپورت‌های inproxy محدود می‌کند تا تونل به‌جای ورودی‌های سایفون از رله‌های کاندوئیت عبور کند.",
            "Это читает только ядро Psiphon. Включение сужает движок до транспортов inproxy, и туннель идёт через узлы Conduit, а не через входные точки Psiphon.",
            "Solo lo lee el núcleo de Psiphon. Al activarlo el motor se limita a sus transportes inproxy, así el túnel pasa por nodos Conduit en vez de las entradas de Psiphon.",
            "只有 Psiphon 内核会读取此项。开启后引擎只使用 inproxy 传输，隧道经由 Conduit 中继而非 Psiphon 入口。"
        )

    val torBridgesHint: String
        get() = p(
            "Without a bridge, Tor first connects to a relay whose address is public, and a network that blocks Tor simply blocks those. A bridge looks like an ordinary TLS connection instead. Turning this on scans and tests bridges from this device.",
            "بدون بریج، تور اول به رله‌ای وصل می‌شود که آدرسش عمومی است و شبکه‌ای که تور را مسدود کرده، همان آدرس‌ها را می‌بندد. " +
                "بریج اما شبیه یک اتصال TLS معمولی به نظر می‌رسد. با روشن کردن این گزینه، بریج‌ها از روی همین دستگاه اسکن و تست می‌شوند.",
            "Без моста Tor сначала обращается к публичному реле, а сеть, блокирующая Tor, просто закрывает такие адреса. Мост же выглядит как обычное TLS-соединение. Если включить это, мосты будут найдены и проверены с этого устройства.",
            "Sin puente, Tor se conecta primero a un relé cuya dirección es pública, y una red que bloquea Tor simplemente bloquea esas direcciones. Un puente parece en cambio una conexión TLS normal. Al activarlo, la aplicación escanea y prueba puentes desde este dispositivo."
        )
    val scanBridges: String
        get() = p("Scan and test bridges", "اسکن و تست بریج‌ها", "Найти и проверить мосты", "Escanear y probar puentes")
    val scanningBridges: String
        get() = p("Scanning bridges…", "در حال اسکن بریج‌ها…", "Ищем мосты…", "Escaneando puentes…")
    val customBridges: String
        get() = p("Your own bridges", "بریج‌های خودتان", "Ваши мосты", "Tus propios puentes")
    val customBridgesHint: String
        get() = p(
            "One obfs4 line per row, exactly as you received it: obfs4 1.2.3.4:9001 FINGERPRINT cert=… iat-mode=0. A scan replaces the list it found itself but never these lines.",
            "هر بریج obfs4 در یک خط، دقیقاً همان‌طور که دریافت کرده‌اید: obfs4 1.2.3.4:9001 FINGERPRINT cert=… iat-mode=0. اسکن، فهرست خودش را عوض می‌کند نه این خط‌ها را.",
            "По одной строке obfs4 на строку, точно как вам её выдали: obfs4 1.2.3.4:9001 ОТПЕЧАТОК cert=… iat-mode=0. Сканирование заменяет найденный им список, но не эти строки.",
            "Una línea obfs4 por fila, exactamente como la recibiste: obfs4 1.2.3.4:9001 FINGERPRINT cert=… iat-mode=0. Un escaneo reemplaza la lista que encontró él mismo, pero nunca estas líneas."
        )
    val saveBridges: String
        get() = p("Save these bridges", "ذخیره این بریج‌ها", "Сохранить эти мосты", "Guardar estos puentes")

    fun bridgesSaved(accepted: Int, rejected: Int): String =
        p(
            if (rejected == 0) "$accepted bridge(s) saved"
            else "$accepted bridge(s) saved, $rejected not a valid obfs4 line",
            if (rejected == 0) "$accepted بریج ذخیره شد"
            else "$accepted بریج ذخیره شد، $rejected خط معتبر obfs4 نبود",
            if (rejected == 0) "Сохранено мостов: $accepted"
            else "Сохранено мостов: $accepted, ещё $rejected — не строка obfs4",
            if (rejected == 0) "Se ${es(accepted, "guardó", "guardaron")} $accepted ${es(accepted, "puente", "puentes")}"
            else "Se ${es(accepted, "guardó", "guardaron")} $accepted ${es(accepted, "puente", "puentes")}, $rejected ${es(rejected, "no es", "no son")} una línea obfs4 válida",
            if (rejected == 0) "$accepted 个网桥已保存"
            else "$accepted 个网桥已保存，$rejected 行不是有效的 obfs4"
        )

    val torNoBridges: String
        get() = p(
            "No bridge answered. Scan again, or connect without bridges.",
            "هیچ بریژی پاسخ نداد. دوباره اسکن کنید یا بدون بریج متصل شوید.",
            "Ни один мост не ответил. Ищите снова или подключитесь без мостов.",
            "Ningún puente respondió. Escanea otra vez, o conecta sin puentes."
        )
    val torEnableBridges: String
        get() = p(
            "Tor could not reach the network directly — enable «Connect through an obfs4 bridge» in Settings.",
            "تور نتوانست مستقیم به شبکه برسد — از تنظیمات «اتصال از طریق بریج obfs4» را فعال کنید.",
            "Tor не смог выйти в сеть напрямую — включите «Подключаться через obfs4-мост» в Настройках.",
            "Tor no pudo llegar a la red directamente: activa «Conectar a través de un puente obfs4» en Ajustes."
        )

    fun bridgeCount(total: Int, custom: Int): String = when {
        total == 0 -> p(
            "No bridge stored yet",
            "هنوز بریجی ذخیره نشده",
            "Мостов пока не сохранено",
            "Todavía no hay puentes guardados",
            "尚未保存任何网桥"
        )
        custom == 0 -> p(
            "$total reachable bridge(s) stored",
            "$total بریج قابل دسترس ذخیره شد",
            "Сохранено доступных мостов: $total",
            "$total ${es(total, "puente accesible guardado", "puentes accesibles guardados")}",
            "已保存 $total 个可达网桥"
        )
        else -> p(
            "$total bridge(s) ready · $custom entered by you",
            "$total بریج آماده · $custom از خودتان",
            "Готово мостов: $total · из них ваших: $custom",
            "$total ${es(total, "puente listo", "puentes listos")} · $custom ${es(custom, "escrito", "escritos")} por ti",
            "$total 个网桥就绪 · 其中 $custom 个由你输入"
        )
    }

    fun bridgeScanResult(reachable: Int, total: Int): String =
        p(
            "$reachable of $total bridges answered from this network",
            "$reachable بریج از $total بریج از این شبکه پاسخ داد",
            "Из $total мостов отвечали $reachable",
            "$reachable de $total puentes respondieron desde esta red",
            "$total 个网桥中有 $reachable 个从该网络响应"
        )

    /** Spells out that the scan itself keeps what it found, so no extra save is needed. */
    fun bridgeScanStored(result: String, stored: Int): String =
        p(
            "$result · $stored bridge(s) kept for Tor",
            "$result · $stored بریج برای تور نگه داشته شد",
            "$result · сохранено мостов: $stored",
            "$result · $stored ${es(stored, "puente guardado", "puentes guardados")} para Tor",
            "$result · 已保留 $stored 个网桥供 Tor 使用"
        )

    val share: String
        get() = p("Share on LAN", "اشتراک در شبکه محلی", "Раздать по локальной сети", "Compartir en LAN")
    val shareSwitch: String
        get() = p(
            "Share the proxy with devices on this network",
            "اشتراک پروکسی با دستگاه‌های شبکه",
            "Делиться прокси с устройствами этой сети",
            "Compartir el proxy con los dispositivos de esta red"
        )
    val shareHint: String
        get() = p(
            "Other devices on the same network can use these addresses, as a SOCKS5 proxy or as an HTTP proxy.",
            "دستگاه‌های دیگر همان شبکه می‌توانند از این آدرس‌ها به‌عنوان پروکسی SOCKS5 یا پروکسی HTTP استفاده کنند.",
            "Другие устройства этой сети могут использовать эти адреса как SOCKS5- или HTTP-прокси.",
            "Otros dispositivos de la misma red pueden usar estas direcciones como proxy SOCKS5 o HTTP.",
            "同一网络中的其他设备可以将这些地址用作 SOCKS5 代理或 HTTP 代理。"
        )
    val shareIdle: String
        get() = p(
            "Turns on automatically when connected.",
            "بعد از برقراری اتصال فعال می‌شود.",
            "Включается автоматически после подключения.",
            "Se activa solo al conectar."
        )
    val sharePreparing: String
        get() = p("Preparing the share…", "در حال آماده‌سازی اشتراک…", "Готовим раздачу…", "Preparando el compartido…")

    fun shareAddress(address: String): String =
        p(
            "LAN address: $address (SOCKS5)",
            "آدرس اشتراک: $address  (SOCKS5)",
            "Адрес раздачи: $address (SOCKS5)",
            "Dirección LAN: $address (SOCKS5)",
            "局域网地址：$address（SOCKS5）"
        )

    fun shareHttpAddress(address: String): String =
        p(
            "LAN address: $address (HTTP)",
            "آدرس اشتراک: $address  (HTTP)",
            "Адрес раздачи: $address (HTTP)",
            "Dirección LAN: $address (HTTP)",
            "局域网地址：$address（HTTP）"
        )

    val alerts: String
        get() = p("Alerts", "اعلان‌ها", "Уведомления", "Avisos")
    val soundSwitch: String
        get() = p("Sound when connected", "پخش صدا هنگام برقراری اتصال", "Звук при подключении", "Sonar al conectar")
    val soundHint: String
        get() = p(
            "A short chime plays the moment the tunnel comes up, even if the ringer is muted.",
            "همان لحظه‌ای که اتصال برقرار می‌شود یک صدای کوتاه پخش می‌شود؛ حتی اگر گوشی بی‌صدا باشد.",
            "Короткий сигнал звучит в момент подключения — даже если телефон без звука.",
            "Suena un tono corto en cuanto el túnel se levanta, aunque el teléfono esté en silencio."
        )

    val autoRetry: String
        get() = p("Retry automatically", "تلاش خودکار دوباره", "Повторять автоматически", "Reintentar automáticamente")
    val autoRetryHint: String
        get() = p(
            "Only a drop the user never asked for is retried: a stalled tunnel or a network change brings the app back with longer waits in between. Turning the connection off yourself, or cancelling, always stays final.",
            "فقط قطعی‌ای که خودتان نخواسته‌اید دوباره امتحان می‌شود: اگر تونل بی‌کار شود یا شبکه عوض شود، برنامه با فاصله‌های بیشتر اتصال را برمی‌گرداند. اما وقتی خودتان اتصال را قطع یا لغو می‌کنید، هیچ تلاش دوباره‌ای انجام نمی‌شود.",
            "Повторяется только обрыв, которого вы не хотели: зависший туннель или смена сети возвращают соединение с всё большими паузами. Если вы сами отключили или отменили — повтор не будет.",
            "Solo se reintenta una caída que el usuario no provocó: un túnel estancado o un cambio de red devuelven la aplicación, con esperas cada vez más largas. Apagar la conexión uno mismo, o cancelar, siempre queda definitivo."
        )

    val exiting: String
        get() = p(
            "Disconnecting and closing Niloofar VPN…",
            "در حال قطع اتصال و بستن نیلوفر وی‌پی‌ان…",
            "Отключаемся и закрываем Niloofar VPN…",
            "Desconectando y cerrando Niloofar VPN…"
        )

    fun retryIn(duration: String, attempt: Int): String =
        p(
            "Attempt $attempt failed · trying again in $duration",
            "تلاش $attempt بی‌نتیجه بود · تلاش دوباره تا $duration",
            "Попытка $attempt не удалась · следующая через $duration",
            "Intento $attempt fallido · nuevo intento en $duration",
            "第 $attempt 次尝试失败 · $duration 后重试"
        )

    /** One line naming the likely cause of a failed attempt, from the probes. */
    fun diagnosis(report: com.ffvpn.client.core.Diagnostics.Report): String = when {
        !report.dnsOk && !report.tcp443Ok && !report.tcp80Ok -> p(
            "Nothing leaves the network: not even an IP address answers. The connection itself is down.",
            "هیچ بسته‌ای از شبکه بیرون نمی‌رود؛ حتی یک آی‌پی هم پاسخ نمی‌دهد. خودِ اینترنت قطع است.",
            "Из сети не выходит ничего: не отвечает даже простой IP-адрес. Интернет не работает.",
            "Nada sale de la red: ni siquiera una dirección IP responde. La conexión está caída."
        )
        !report.dnsOk -> p(
            "IP addresses still answer but names do not resolve: this network's DNS is blocked.",
            "آی‌پی‌ها هنوز پاسخ می‌دهند اما نام‌ها تبدیل نمی‌شوند: DNS این شبکه بسته است.",
            "IP-адреса отвечают, но имена не разбираются: DNS этой сети заблокирован.",
            "Las direcciones IP siguen respondiendo pero los nombres no se resuelven: el DNS de esta red está bloqueado."
        )
        !report.tcp443Ok && report.tcp80Ok -> p(
            "Port 443 is closed while port 80 still answers: the cores that ride on TLS port 443 cannot work here.",
            "پورت ۴۴۳ بسته است در حالی که پورت ۸۰ پاسخ می‌دهد: هسته‌هایی که با TLS روی ۴۴۳ کار می‌کنند در این شبکه نمی‌توانند.",
            "Порт 443 закрыт, а 80 отвечает: ядра, работающие через TLS на 443, здесь бесполезны.",
            "El puerto 443 está cerrado mientras el 80 sí responde: los núcleos que viajan por TLS en el 443 no pueden funcionar aquí."
        )
        !report.tcp443Ok -> p(
            "Neither port 443 nor port 80 accepts a connection: the network only carries a little traffic.",
            "نه پورت ۴۴۳ باز می‌شود نه پورت ۸۰: این شبکه تقریباً چیزی رد و بدل نمی‌کند.",
            "Не открывается ни 443, ни 80: сеть едва что-то пропускает.",
            "Ni el puerto 443 ni el 80 aceptan una conexión: la red apenas transporta tráfico."
        )
        !report.tlsSniOk -> p(
            "TCP is open but the TLS handshake never completes: the network is killing TLS itself.",
            "مسیر TCP باز است اما دست‌دهی TLS کامل نمی‌شود: شبکه خودِ TLS را می‌کشد.",
            "TCP открывается, но TLS-рукопожатие не завершается: сеть убивает сам TLS.",
            "TCP está abierto pero el saludo TLS nunca termina: la red está matando el propio TLS."
        )
        !report.tlsPlainOk -> p(
            "TLS only works with a real SNI, so the cores that hide behind a fake address have the advantage here.",
            "TLS فقط با SNI واقعی کار می‌کند؛ اینجا هسته‌هایی که پشت یک نشانی جعلی پنهان می‌شوند شانس بیشتری دارند.",
            "TLS работает только с настоящим SNI — здесь выигрыш у ядер, прячущихся за подставным адресом.",
            "TLS solo funciona con un SNI real, así que aquí llevan ventaja los núcleos que se esconden detrás de una dirección falsa."
        )
        else -> p(
            "The network looks healthy: the servers of this core are the problem. Try another core.",
            "شبکه سالم به نظر می‌رسد؛ مشکل از سرورهای همین هسته است. یک هستهٔ دیگر را امتحان کنید.",
            "Сеть выглядит исправной: проблема в серверах этого ядра. Попробуйте другое ядро.",
            "La red parece sana: el problema son los servidores de este núcleo. Prueba con otro núcleo."
        )
    }

    val about: String
        get() = p("About", "درباره", "О приложении", "Acerca de")
    val aboutBody: String
        get() = p(
            "Niloofar VPN — a resilient tunnel for the open internet",
            "نیلوفر وی‌پی‌ان — تونل مقاوم برای اینترنت آزاد",
            "Niloofar VPN — устойчивый туннель в открытый интернет",
            "Niloofar VPN — un túnel resistente hacia el internet abierto"
        )

    fun version(name: String): String =
        p("Version $name", "نسخه $name", "Версия $name", "Versión $name", "版本 $name")

    val aboutHint: String
        get() = p(
            "If the connection stalls after switching networks, reconnect.",
            "اگر بعد از تغییر شبکه اتصال متوقف شد، دوباره وصل شوید.",
            "Если соединение зависло после смены сети — переподключитесь.",
            "Si la conexión se estanca al cambiar de red, vuelve a conectar."
        )

    val connect: String
        get() = p("Connect", "اتصال", "Подключиться", "Conectar")
    val cancel: String
        get() = p("Cancel", "لغو", "Отмена", "Cancelar")
    val disconnect: String
        get() = p("Disconnect", "قطع اتصال", "Отключиться", "Desconectar")

    val hintIdle: String
        get() = p("tap to start", "برای شروع", "нажмите, чтобы начать", "toca para empezar")
    val hintWaiting: String
        get() = p("please wait", "صبور باشید", "подождите", "espera un momento")
    val hintConnected: String
        get() = p("uptime", "مدت اتصال", "в сети", "tiempo conectado")
    val scale: String
        get() = p("/ 15:00", "از 15:00", "/ 15:00", "/ 15:00")

    val ready: String
        get() = p("Ready", "آماده", "Готово", "Listo")
    val connecting: String
        get() = p("Connecting…", "در حال اتصال…", "Подключение…", "Conectando…")

    /** Shown between the core's own "ready" and the app's "connected". */
    val verifyingTunnel: String
        get() = p(
            "Checking that YouTube and Facebook really open…",
            "در حال بررسی باز شدن یوتیوب و فیسبوک…",
            "Проверяем, открываются ли YouTube и Facebook…",
            "Comprobando que YouTube y Facebook abran de verdad…",
            "正在确认 YouTube 和 Facebook 是否真的可以打开…"
        )
    val disconnecting: String
        get() = p("Disconnecting…", "در حال قطع اتصال…", "Отключение…", "Desconectando…", "正在断开连接…")

    /** Shown while a new session waits for the previous engine to release the port. */
    val waitingForEngine: String
        get() = p(
            "Waiting for the previous core to close…",
            "در حال انتظار برای بسته شدن هسته قبلی…",
            "Ждём закрытия предыдущего ядра…",
            "Esperando a que se cierre el núcleo anterior…",
            "正在等待上一个核心关闭…"
        )
    val disconnectingHint: String
        get() = p(
            "Please wait until the core has completely stopped.",
            "لطفاً تا توقف کامل هسته صبر کنید.",
            "Пожалуйста, дождитесь полной остановки ядра.",
            "Espera hasta que el núcleo se haya detenido por completo.",
            "请等待内核完全停止。"
        )
    val connected: String
        get() = p("Connected", "متصل", "Подключено", "Conectado")
    val disconnected: String
        get() = p("Disconnected", "اتصال قطع شد", "Соединение разорвано", "Desconectado")
    val failed: String
        get() = p("Connection failed", "اتصال ناموفق", "Не удалось подключиться", "Conexión fallida")
    val stopped: String
        get() = p("Disconnected", "قطع شد", "Отключено", "Desconectado")
    val revoked: String
        get() = p("VPN permission revoked", "دسترسی وی‌پی‌ان لغو شد", "Доступ VPN отозван", "Permiso de VPN revocado")
    val denied: String
        get() = p("VPN permission was not granted", "دسترسی وی‌پی‌ان داده نشد", "Доступ VPN не выдан", "No se concedió el permiso de VPN")
    val timeout: String
        get() = p("Connection timed out", "زمان اتصال به پایان رسید", "Время подключения истекло", "La conexión expiró")
    val tunnelFailed: String
        get() = p(
            "Could not set up the network tunnel",
            "ساخت رابط شبکه ناموفق بود",
            "Не удалось создать сетевой туннель",
            "No se pudo crear el túnel de red"
        )

    fun status(code: Int): String = p("Status $code", "وضعیت $code", "Состояние $code", "Estado $code", "状态 $code")

    fun error(detail: String): String =
        if (detail.isBlank()) p("Error", "خطا", "Ошибка", "Error", "错误")
        else p("Error: $detail", "خطا: $detail", "Ошибка: $detail", "Error: $detail", "错误：$detail")

    fun startFailed(detail: String): String = p(
        "Could not start the tunnel: $detail",
        "راه‌اندازی تونل ناموفق بود: $detail",
        "Не удалось запустить туннель: $detail",
            "No se pudo iniciar el túnel: $detail",
        "无法启动隧道：$detail"
    )

    /** The same sentence, naming the sites that did not answer. */
    fun noTrafficDetail(detail: String): String =
        p(
            "The tunnel came up but nothing passed through it ($detail)",
            "تونل برقرار شد اما هیچ ترافیکی از آن رد نشد ($detail)",
            "Туннель поднялся, но через него ничего не прошло ($detail)",
            "El túnel se levantó pero nada pasó por él ($detail)",
            "隧道已建立，但没有任何流量通过（$detail）"
        )

    val noTraffic: String
        get() = p(
            "The tunnel came up but nothing passed through it",
            "تونل برقرار شد اما هیچ ترافیکی از آن رد نشد",
            "Туннель поднялся, но через него ничего не прошло",
            "El túnel se levantó pero nada pasó a través de él"
        )

    fun proxyDetail(socks: String, http: String): String =
        p(
            "SOCKS5 $socks · HTTP $http",
            "ساکس $socks · اچ‌تی‌تی‌پی $http",
            "SOCKS5 $socks · HTTP $http",
            "SOCKS5 $socks · HTTP $http",
            "SOCKS5 $socks · HTTP $http"
        )

    /** Line on the main screen naming the core that carries the traffic. */
    fun coreActiveDetail(name: String): String =
        p(
            "Connected with $name",
            "اتصال با $name برقرار است",
            "Подключено через $name",
            "Conectado con $name",
            "已通过 $name 连接"
        )

    /** The address the tunnel hands to the outside world, and where it lives. */
    val publicIp: String
        get() = p("Public IP", "آی‌پی عمومی", "Публичный IP", "IP pública", "公网 IP")
    val location: String
        get() = p("Location", "موقعیت مکانی", "Расположение", "Ubicación", "位置")
    val session: String
        get() = p("Session", "مدت اتصال", "Сеанс", "Sesión", "连接时长")

    /** Line on the main screen saying how long the successful attempt took. */
    fun connectTook(duration: String): String =
        p(
            "It took $duration to connect",
            "برقراری اتصال $duration طول کشید",
            "Подключение заняло $duration",
            "Conectar tardó $duration",
            "连接用时 $duration"
        )

    /** Line on the main screen saying how long the current session has been up. */
    fun sessionUptime(duration: String): String =
        p(
            "Session $duration",
            "مدت اتصال $duration",
            "Сеанс $duration",
            "Sesión $duration",
            "连接时长 $duration"
        )

    /** A span of time in words, e.g. `13s` / `13 ثانیه` / `13 с`. */
    fun duration(millis: Long): String {
        val total = (millis / 1000L).coerceAtLeast(0L)
        val hours = total / 3600L
        val minutes = (total % 3600L) / 60L
        val seconds = total % 60L
        val parts = buildList {
            if (hours > 0) add("$hours${p("h", " ساعت", " ч", " h")}")
            if (minutes > 0) add("$minutes${p("m", " دقیقه", " мин", " min")}")
            if (seconds > 0 && hours == 0L) add("$seconds${p("s", " ثانیه", " с", " s")}")
        }
        if (parts.isEmpty()) return p("under a second", "کمتر از یک ثانیه", "меньше секунды", "menos de un segundo", "不到一秒")
        return parts.joinToString(if (language == AppLanguage.FA) " و " else " ")
    }

    val patienceProgress: String
        get() = p(
            "Please wait, connecting can take up to 15 minutes",
            "شکیبا باشید، برقراری اتصال ممکن است تا ۱۵ دقیقه طول بکشد",
            "Подождите: подключение может занимать до 15 минут",
            "Espera, conectar puede tardar hasta 15 minutos"
        )

    /**
     * What the chosen core is doing right now, under the ring. [detail] is the
     * stage the engine reported; known stages get a fixed wording and anything
     * else is passed through as the engine wrote it.
     */
    fun progress(coreId: String, detail: String): String {
        val stage = detail.trim().lowercase()
        if (stage == STAGE_SERVER_DATABASE) return serverDatabaseBootstrap
        if (stage.startsWith("dialing ")) {
            val endpoint = detail.trim().substringAfter(' ')
            return p(
                "Dialing $endpoint",
                "در حال اتصال به $endpoint",
                "Соединение с $endpoint",
                "Marcando $endpoint",
                "正在连接 $endpoint"
            )
        }
        if (coreId == "tor" && stage.startsWith("bootstrapping")) {
            val percent = stage.substringAfter("bootstrapping").trim().toIntOrNull() ?: 0
            return p(
                "Building the Tor circuit… $percent%",
                "در حال ساختن مدار تور… ${percent}٪",
                "Строим цепочку Tor… $percent%",
            "Creando el circuito de Tor… $percent%",
                "正在构建 Tor 链路… $percent%"
            )
        }
        return when (coreId to stage) {
            "sunlion" to "connecting" ->
                p(
                    "Trying the current IPs and SNI values",
                    "در حال امتحان آی‌پی‌ها و sni های فعلی",
                    "Пробуем текущие IP и SNI",
            "Probando las IP y los valores SNI actuales"
                )
            "sunlion" to "scanning" ->
                p(
                    "Scanning for new IPs and SNI values",
                    "در حال اسکن برای به دست آوردن آی‌پی‌ها و sni های جدید",
                    "Ищем новые IP и SNI",
            "Buscando IP y valores SNI nuevos"
                )
            "aether" to "http2 scan" ->
                p(
                    "Scanning MASQUE and HTTP2 for an endpoint",
                    "در حال اسکن مسک و http2 برای پیدا کردن اندپوینت",
                    "Ищем точку доступа через MASQUE и HTTP2",
            "Buscando un extremo entre MASQUE y HTTP2"
                )
            "aether" to "automatic scan" ->
                p("Running the automatic scan…", "در حال اسکن اتوماتیک...", "Запускаем автоматический поиск…", "Ejecutando el escaneo automático…")
            "sstp" to "connecting" ->
                p(
                    "Scanning and checking the available servers…",
                    "در حال اسکن و بررسی سرورهای موجود...",
                    "Проверяем доступные серверы…",
            "Escaneando y comprobando los servidores disponibles…"
                )
            "lantern" to "connecting" ->
                p(
                    "Finding servers we can connect to",
                    "در حال پیدا کردن سرورهای قابل اتصال",
                    "Ищем серверы, к которым можно подключиться",
            "Buscando servidores a los que se pueda conectar"
                )
            "firefox" to "connecting" ->
                p("Refreshing the free server list", "در حال تازه کردن فهرست سرورهای رایگان", "Обновляем список бесплатных серверов", "Actualizando la lista de servidores gratuitos")
            "firefox" to "reading list" ->
                p("Reading the free server list", "در حال خواندن فهرست سرورهای رایگان", "Читаем список бесплатных серверов", "Leyendo la lista de servidores gratuitos")
            "firefox" to "opening tunnel" ->
                p(
                    "Opening the system tunnel",
                    "در حال باز کردن تونل سیستم",
                    "Открываем системный туннель",
                    "Abriendo el túnel del sistema"
                )
            "firefox" to "checking servers" ->
                p(
                    "Checking which servers answer",
                    "در حال بررسی اینکه کدام سرورها پاسخ می‌دهند",
                    "Проверяем, какие серверы отвечают",
            "Comprobando qué servidores responden"
                )
            "tor" to "connecting" ->
                p(
                    "Starting Tor and building a circuit",
                    "در حال راه‌اندازی تور و ساختن مدار",
                    "Запускаем Tor и строим цепочку",
            "Iniciando Tor y creando un circuito"
                )
            "tor" to "checking bridges" ->
                p(
                    "Scanning and testing obfs4 bridges",
                    "در حال اسکن و تست بریج‌های obfs4",
                    "Ищем и проверяем obfs4-мосты",
            "Escaneando y probando puentes obfs4"
                )
            else -> when {
                stage.isNotBlank() && stage != "connecting" && stage != "reconnecting" -> detail.trim()
                else -> connecting
            }
        }
    }

    val ports: String
        get() = p("Proxy ports", "پورت پروکسی‌ها", "Порты прокси", "Puertos del proxy")
    val socksPortLabel: String
        get() = p("SOCKS5 port", "پورت SOCKS5", "Порт SOCKS5", "Puerto SOCKS5")
    val httpPortLabel: String
        get() = p("HTTP port", "پورت HTTP", "Порт HTTP", "Puerto HTTP")
    val portsHint: String
        get() = p(
            "Every core listens on these ports. Changing them applies on the next connect.",
            "همهٔ هسته‌ها روی این پورت‌ها گوش می‌دهند. تغییر آن‌ها از اتصال بعدی اعمال می‌شود.",
            "Все ядра слушают эти порты. Изменения применятся при следующем подключении.",
            "Todos los núcleos escuchan en estos puertos. Los cambios se aplican en la conexión siguiente."
        )
    val portPrompt: String
        get() = p(
            "Port number (1024 – 65535)",
            "شمارهٔ پورت (۱۰۲۴ تا ۶۵۵۳۵)",
            "Номер порта (1024–65535)",
            "Número de puerto (1024 – 65535)"
        )
    val portInvalid: String
        get() = p(
            "Pick a free port between 1024 and 65535",
            "پورت آزاد بین ۱۰۲۴ تا ۶۵۵۳۵ را انتخاب کنید",
            "Выберите свободный порт от 1024 до 65535",
            "Elige un puerto libre entre 1024 y 65535"
        )

    fun lanDetail(socks: String, http: String): String {
        val both = listOf(socks, http).filter { it.isNotBlank() }.joinToString(" · ")
        return p(
            "LAN share · $both",
            "اشتراک در شبکه: $both",
            "Раздача по сети · $both",
            "Compartido en LAN · $both",
            "局域网共享 · $both"
        )
    }

    val detailIdle: String
        get() = p(
            "Tap the button to connect",
            "برای اتصال، دکمه بالا را لمس کنید",
            "Нажмите кнопку, чтобы подключиться",
            "Toca el botón para conectar"
        )

    val continueLabel: String
        get() = p("Continue", "ادامه", "Продолжить", "Continuar")

    val notificationConnecting: String
        get() = p("Connecting…", "در حال اتصال…", "Подключение…", "Conectando…")
    val notificationProxy: String
        get() = p("Proxy is active", "پروکسی فعال است", "Прокси работает", "El proxy está activo")
    val notificationVpn: String
        get() = p("VPN is active", "وی‌پی‌ان فعال است", "VPN работает", "La VPN está activa")
}
