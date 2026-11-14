package com.ffvpn.client

/**
 * Chinese wording for the fixed lines in [Texts], keyed by their English
 * source. A line built from variables (a count, a duration, an address) cannot
 * be looked up here, so those carry their Chinese text at the call site.
 */
private val ZH = mapOf(
    "Niloofar VPN" to "Niloofar VPN",
    "The open internet, quietly" to "安静地通往开放互联网",
    "Settings" to "设置",
    "Back" to "返回",
    "Auto" to "自动",
    "Language" to "语言",
    "Applies instantly across the app." to "立即在整个应用中生效。",
    "Connection" to "连接",
    "Mode" to "模式",
    "VPN · whole device" to "VPN · 整个设备",
    "Proxy · SOCKS5 + HTTP" to "代理 · SOCKS5 + HTTP",
    "VPN" to "VPN",
    "Proxy" to "代理",
    "In VPN mode every app goes through the tunnel. In proxy mode only apps configured to use the local SOCKS5 proxy do." to
        "在 VPN 模式下，所有应用的流量都经过隧道。在代理模式下，只有配置了本地 SOCKS5 代理的应用才会经过。",
    "Core" to "内核",
    "The engine that carries the tunnel. Firefox uses your account; the others need no sign-in." to
        "承载隧道的内核。Firefox 使用你的账户；其他内核无需登录。",
    "Aether" to "Aether",
    "Psiphon" to "Psiphon",
    "Sun and Lion" to "太阳与狮子",
    "SSTP" to "SSTP",
    "Lantern" to "Lantern",
    "Firefox" to "Firefox",
    "Tor" to "Tor",
    "This core is not available on this device." to "此内核在该设备上不可用。",
    "No working route was found. Check the network and try again." to "未找到可用线路。请检查网络后重试。",
    "The core is downloading server information. The first connection takes a while, please wait." to
        "内核正在下载服务器信息。首次连接需要一些时间，请耐心等待。",
    "Sign in to your Firefox Account in Settings first." to "请先在设置中登录你的 Firefox 账户。",
    "Firefox Account" to "Firefox 账户",
    "The Firefox core uses the free proxy that comes with any Firefox Account — no subscription. Only the sign-in token is kept, never the password." to
        "Firefox 内核使用每个 Firefox 账户自带的免费代理，无需订阅。只保存登录令牌，绝不保存密码。",
    "Email" to "邮箱",
    "No Firefox Account yet? Sign up for free" to "还没有 Firefox 账户？免费注册",
    "No browser is available to open the link." to "没有可用来打开链接的浏览器。",
    "Password" to "密码",
    "Sign in" to "登录",
    "Signing in…" to "正在登录…",
    "Sign out" to "退出登录",
    "Not signed in" to "未登录",
    "Enter both the email and the password." to "请输入邮箱和密码。",
    "Config tools" to "配置工具",
    "Two tools collect the addresses the cores dial: an edge scanner for Sun and Lion, the VPN Gate list for SSTP. Both work from this device." to
        "两个工具为内核收集地址：为太阳与狮子扫描边缘节点，为 SSTP 收集 VPN Gate 列表。两者都在本设备上运行。",
    "Nothing is saved until you tap «Use these». With «Delete the old list» on, what the core used before is removed first; with it off, the new entries are added to it." to
        "在你点击“使用这些”之前，不会保存任何内容。开启“删除旧列表”时，内核之前使用的地址会先被清除；关闭时，新条目会追加到旧列表中。",
    "Delete the old list" to "删除旧列表",
    "One task is already running here. Wait for it or press stop." to
        "这里已有一个任务在运行。请等待它完成或按停止。",
    "IP scanner" to "IP 扫描器",
    "VPN Gate SSTP servers" to "VPN Gate SSTP 服务器",
    "CDN group" to "CDN 组",
    "IP or CIDR list" to "IP 或 CIDR 列表",
    "One per line; 104.16.0.0/16 and 172.64.0.1-172.64.0.9 both work. Leave it empty to scan the group." to
        "每行一个；104.16.0.0/16 和 172.64.0.1-172.64.0.9 都可以。留空则扫描整个组。",
    "SNI values" to "SNI 值",
    "One per line. Empty means: try the group’s own list and keep whatever answers." to
        "每行一个。留空表示：尝试该组自带的列表并保留所有能响应的。",
    "HTTP Host values" to "HTTP Host 值",
    "One per line. The name the request asks for in its Host header; it has to differ from the SNI. Empty means the group’s own list." to
        "每行一个。请求在 Host 头中使用的名称，必须与 SNI 不同。留空表示使用该组自带的列表。",
    "Parallel probes" to "并发探测",
    "Scan depth" to "扫描深度",
    "Quick" to "快速",
    "Balanced" to "均衡",
    "Deep" to "深入",
    "Find working SNIs" to "查找可用的 SNI",
    "Probing SNIs…" to "正在探测 SNI…",
    "No SNI answered from this network." to "该网络中没有 SNI 响应。",
    "Start scan" to "开始扫描",
    "Stop" to "停止",
    "Scanning…" to "正在扫描…",
    "No address passed the test." to "没有地址通过测试。",
    "Copy all" to "全部复制",
    "Copied" to "已复制",
    "Use these 5" to "使用这 5 个",
    "Use these 20" to "使用这 20 个",
    "Fetch the list" to "获取列表",
    "Reading vpngate.net…" to "正在读取 vpngate.net…",
    "vpngate.net could not be read. Use your VPN, and try again." to
        "无法读取 vpngate.net。请使用 VPN 后再试。",
    "Test which answer" to "测试哪些可用",
    "Testing relays…" to "正在测试中继…",
    "Delete the installed list (use the built-in one)" to "删除已安装的列表（改用内置列表）",
    "Skip Iran, Russia, China and North Korea" to "跳过伊朗、俄罗斯、中国和朝鲜",
    "VPN Gate publishes servers that change every day; a country flag with no relay behind it is normal. The list is fetched without a tunnel, so if the page does not load, open this screen while another core is connected." to
        "VPN Gate 每天都会更换服务器；旗帜下没有在线中继是正常的。该列表不经隧道读取，如果页面打不开，请在连接其他内核时打开此页面。",
    "The built-in list is in use" to "正在使用内置列表",
    "Tor bridges" to "Tor 网桥",
    "Off: Tor dials a public relay. On: it goes through an obfs4 bridge." to
        "关闭：Tor 连接公共中继。开启：通过 obfs4 网桥连接。",
    "Connect through an obfs4 bridge" to "通过 obfs4 网桥连接",
    "Without a bridge, Tor first connects to a relay whose address is public, and a network that blocks Tor simply blocks those. A bridge looks like an ordinary TLS connection instead. Turning this on scans and tests bridges from this device." to
        "没有网桥时，Tor 会先连接地址公开的中继，封锁 Tor 的网络会直接封锁这些地址。而网桥看起来像普通的 TLS 连接。开启此项后，会从本设备扫描并测试网桥。",
    "Scan and test bridges" to "扫描并测试网桥",
    "Scanning bridges…" to "正在扫描网桥…",
    "Your own bridges" to "你自己的网桥",
    "One obfs4 line per row, exactly as you received it: obfs4 1.2.3.4:9001 FINGERPRINT cert=… iat-mode=0. A scan replaces the list it found itself but never these lines." to
        "每行一条 obfs4 配置，与收到时完全一致：obfs4 1.2.3.4:9001 FINGERPRINT cert=… iat-mode=0。扫描只会替换它自己找到的列表，不会动这些行。",
    "Save these bridges" to "保存这些网桥",
    "No bridge answered. Scan again, or connect without bridges." to
        "没有网桥响应。请重新扫描，或不使用网桥连接。",
    "Tor could not reach the network directly — enable «Connect through an obfs4 bridge» in Settings." to
        "Tor 无法直接访问网络——请在设置中开启“通过 obfs4 网桥连接”。",
    "No bridge stored yet" to "尚未保存任何网桥",
    "Share on LAN" to "局域网共享",
    "Share the proxy with devices on this network" to "与本网络中的设备共享代理",
    "Other devices on the same network can use this address as a SOCKS5 proxy." to
        "同一网络中的其他设备可以将此地址用作 SOCKS5 代理。",
    "Turns on automatically when connected." to "连接后会自动开启。",
    "Preparing the share…" to "正在准备共享…",
    "Alerts" to "提醒",
    "Sound when connected" to "连接时播放声音",
    "A short chime plays the moment the tunnel comes up, even if the ringer is muted." to
        "隧道建立时会播放一段短提示音，即使手机已静音。",
    "Retry automatically" to "自动重试",
    "Only a drop the user never asked for is retried: a stalled tunnel or a network change brings the app back with longer waits in between. Turning the connection off yourself, or cancelling, always stays final." to
        "只会重试用户未曾要求的中断：隧道停滞或网络切换时，应用会在更长的间隔后重新连接。你自己关闭或取消连接时，绝不会重试。",
    "Exit" to "退出",
    "Disconnecting and closing Niloofar VPN…" to "正在断开并关闭 Niloofar VPN…",
    "Nothing leaves the network: not even an IP address answers. The connection itself is down." to
        "没有任何数据能离开网络：连 IP 地址也无响应。网络连接本身已中断。",
    "IP addresses still answer but names do not resolve: this network's DNS is blocked." to
        "IP 地址仍有响应，但域名无法解析：该网络的 DNS 被封锁。",
    "Port 443 is closed while port 80 still answers: the cores that ride on TLS port 443 cannot work here." to
        "80 端口仍可用，但 443 端口被关闭：依赖 443 端口 TLS 的内核在此无法工作。",
    "Neither port 443 nor port 80 accepts a connection: the network only carries a little traffic." to
        "443 和 80 端口都无法连接：该网络几乎不传输流量。",
    "TCP is open but the TLS handshake never completes: the network is killing TLS itself." to
        "TCP 可以打开，但 TLS 握手始终无法完成：该网络正在阻断 TLS 本身。",
    "TLS only works with a real SNI, so the cores that hide behind a fake address have the advantage here." to
        "TLS 只在真实 SNI 下可用，因此在此隐藏于伪造地址后面的内核更有优势。",
    "The network looks healthy: the servers of this core are the problem. Try another core." to
        "网络看起来正常：问题出在该内核的服务器上。请尝试其他内核。",
    "About" to "关于",
    "Niloofar VPN — a resilient tunnel for the open internet" to "Niloofar VPN — 通往开放互联网的坚韧隧道",
    "If the connection stalls after switching networks, reconnect." to "如果切换网络后连接停滞，请重新连接。",
    "Connect" to "连接",
    "Cancel" to "取消",
    "Disconnect" to "断开连接",
    "tap to start" to "点击开始",
    "please wait" to "请稍候",
    "uptime" to "已连接",
    "/ 15:00" to "/ 15:00",
    "Ready" to "就绪",
    "Connecting…" to "正在连接…",
    "Connected" to "已连接",
    "Disconnected" to "已断开",
    "Connection failed" to "连接失败",
    "VPN permission revoked" to "VPN 权限已被撤销",
    "VPN permission was not granted" to "未授予 VPN 权限",
    "Connection timed out" to "连接超时",
    "Could not set up the network tunnel" to "无法建立网络隧道",
    "Error" to "错误",
    "The tunnel came up but nothing passed through it" to "隧道已建立，但没有任何流量通过",
    "h" to " 小时",
    "m" to " 分钟",
    "s" to " 秒",
    "under a second" to "不到一秒",
    "Please wait, connecting can take up to 15 minutes" to "请稍候，连接最长可能需要 15 分钟",
    "Trying the current IPs and SNI values" to "正在尝试当前的 IP 和 SNI 值",
    "Scanning for new IPs and SNI values" to "正在扫描新的 IP 和 SNI 值",
    "Scanning MASQUE and HTTP2 for an endpoint" to "正在通过 MASQUE 和 HTTP2 扫描可用端点",
    "Running the automatic scan…" to "正在执行自动扫描…",
    "Scanning and checking the available servers…" to "正在扫描并检查可用服务器…",
    "Finding servers we can connect to" to "正在寻找可连接的服务器",
    "Refreshing the free server list" to "正在刷新免费服务器列表",
    "Reading the free server list" to "正在读取免费服务器列表",
    "Opening the system tunnel" to "正在打开系统隧道",
    "Checking which servers answer" to "正在检查哪些服务器有响应",
    "Starting Tor and building a circuit" to "正在启动 Tor 并构建链路",
    "Scanning and testing obfs4 bridges" to "正在扫描并测试 obfs4 网桥",
    "Proxy ports" to "代理端口",
    "SOCKS5 port" to "SOCKS5 端口",
    "HTTP port" to "HTTP 端口",
    "Every core listens on these ports. Changing them applies on the next connect." to
        "所有内核都在这些端口上监听。修改将在下次连接时生效。",
    "Port number (1024 – 65535)" to "端口号（1024 – 65535）",
    "Pick a free port between 1024 and 65535" to "请选择 1024 到 65535 之间的空闲端口",
    "Tap the button to connect" to "点击按钮即可连接",
    "Continue" to "继续",
    "Proxy is active" to "代理已启用",
    "VPN is active" to "VPN 已启用",
    "Public IP" to "公网 IP",
    "Location" to "位置",
    "Session" to "连接时长"
)

internal fun zhText(en: String): String = ZH[en] ?: en
