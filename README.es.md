# نیلوفر وی‌پی‌ان (Niloofar VPN)

[English](README.md) · [فارسی](README.fa.md) · [Русский](README.ru.md) · **Español**

Cliente Android para el motor de Firefox VPN de `../firefox-vpn-go`. El motor
en Go se compila de forma cruzada en `libffvpn.so`, se ejecuta desde
`nativeLibraryDir` y se apunta a una salida libre de Firefox.

## Cómo se conecta

- **Modo VPN (predeterminado).** La app abre una interfaz TUN y `libc2tun.so`
  (tun2socks) convierte cada paquete de este teléfono en una conexión SOCKS5 al
  motor y de ahí a la salida de Firefox elegida. Desde el teléfono esto es una
  VPN de sistema normal: cubre todas las apps y no hay que escribir ningún
  proxy en ningún sitio.
- **Modo proxy.** Sin VPN de sistema y sin diálogo de permiso: solo corren los
  escuchas locales del motor, SOCKS (1080) y HTTP (1081), para las apps que
  apuntes tú mismo a ellos.

### Por qué compartir con otros dispositivos usa los puertos de proxy

`VpnService` solo transporta el tráfico de las apps que corren en el dispositivo
que creó el túnel. Android no permite que una app enrute un portátil conectado
por anclaje o un segundo teléfono a través de su VPN, así que al activar
Compartir los escuchas del motor se enlazan a `0.0.0.0` y el otro dispositivo
configura `<phone-ip>:1080` (SOCKS) o `<phone-ip>:1081` (HTTP) como su proxy. El
tráfico sigue saliendo del teléfono por el mismo túnel; solo la entrega a ese
dispositivo es un proxy, porque es la única entrega que Android permite. O sea
que esto es una VPN, no "solo un proxy": el modo proxy es una opción aparte que
puedes elegir en su lugar.

## Funciones

- Conexión con un toque: la app pide la lista de salidas, prueba cada borde,
  marca el más rápido que de verdad transporta tráfico y solo entonces muestra
  "Conectado".
- **Modo VPN** — se abre una interfaz TUN y `libc2tun.so` (tun2socks) envía el
  tráfico del dispositivo al escucha SOCKS5 del motor.
- **Modo proxy** — sin VPN de sistema y sin diálogo de permiso: solo corren los
  escuchas locales SOCKS/HTTP, para las apps que apuntes tú mismo al proxy.
- **Compartir en la red** — los escuchas se enlazan a `0.0.0.0` para que otros
  dispositivos en la misma Wi-Fi/anclaje usen `SOCKS <ip>:1080` o
  `HTTP <ip>:1081`.
- Keep-alive: el escucha local se prueba cada minuto y una sesión que deja de
  responder se reconstruye sola, en vez de dejar un túnel muerto en pantalla.
- Inicio de sesión en la propia página de Mozilla dentro de la app: correo,
  contraseña, código de dos factores, el correo de confirmación y el desafío del
  borde de Mozilla los maneja su página, y la sesión se mantiene hasta que
  Mozilla la confirma.
- Varias cuentas guardadas en el dispositivo con cambio de un toque, cuota por
  cuenta y una guía de creación de cuenta que abre la página de registro de
  Mozilla.
- Lectura en vivo de velocidad y latencia, y una tarjeta de registro en
  ajustes: muestra su tamaño, lo copia al portapapeles o lo borra; las líneas se
  quedan en memoria y nunca se muestran ni se escriben en disco.
- Interfaz en inglés/persa/ruso/español con selector de idioma en la app, e
  icono de roseta de arenisca.

## Compilación

Requisitos: JDK 17+ (aquí se usa 21), Android SDK con build-tools 35 y
NDK 26.3.11579264, Go para el motor.

```sh
./tools/build-apks.sh
```

Ejecuta `assembleRelease` y copia los APK divididos en `dist/`. La misma
compilación corre en GitHub: `.github/workflows/build-apks.yml` compila cada
push, pull request y etiqueta. Los push a `main` refrescan la versión
**Latest build** y cada etiqueta crea una numerada, así que los APK de Releases
nunca son más viejos que `main`; además cada ejecución los adjunta como
artefacto `NiloofarVPN-apks`. Los binarios nunca viven en el repositorio.

| Archivo | ABI |
| --- | --- |
| `dist/NiloofarVPN-1.1.1-armeabi-v7a.apk` | ARM de 32 bits |
| `dist/NiloofarVPN-1.1.1-arm64-v8a.apk` | ARM de 64 bits |
| `dist/NiloofarVPN-1.1.1-universal.apk` | ambas |

Todas las compilaciones de versión se firman con la clave fija
`signing/niloofar.keystore`, así que cada compilación —local o de CI— se instala
encima de la anterior. Los APK de las versiones hasta la 1.1.0 se firmaban con
una clave que se generaba de nuevo en cada ejecución de CI; si tienes uno
instalado, desinstálalo una vez antes de instalar la 1.1.1.

### Dependencias y red

`settings.gradle.kts` resuelve primero por los espejos Maven de Tencent y Myket,
porque `dl.google.com` no es fiable desde esta máquina. El
`~/.gradle/gradle.properties` del usuario fija un proxy SOCKS que Java no sabe
usar, así que el script de compilación pasa `-DsocksProxyHost=` para apagarlo y
además limpia las variables de entorno de proxy.

### Recompilar el motor

```sh
./tools/ffvpn/build-android.sh
```

El script copia `../firefox-vpn-go` a un directorio temporal, aplica los parches
de `tools/ffvpn/` (sal quick-stretch v2, token de sesión en caché,
`--list-countries --json`, tiempo límite del saludo TLS, pregunta de imagen) y
compila de forma cruzada para ambas ABI con el clang del NDK y cgo, para usar el
resolver de Android.

## Estructura

```
app/src/main/java/com/ffvpn/client/
  MainActivity.kt            anfitrión de la interfaz, consentimiento VPN, permiso de notificaciones
  SignInActivity.kt          formulario de la cuenta de Firefox, retransmite las preguntas del motor
  WebSignInActivity.kt       página de inicio de sesión de Mozilla en un WebView
  AppLocale.kt               idioma elegido dentro de la app
  ui/                        pantallas y tema Compose
  vpn/FirefoxVpnService.kt   servicio en primer plano, TUN, keep-alive, compartir
  vpn/FirefoxEngine.kt       proceso del motor, pruebas, comprobación de tráfico
  vpn/FxaLogin.kt            inicio de sesión, token en caché para conexiones posteriores
  vpn/ServerList.kt          lectura de la lista de salidas, pruebas y orden
  vpn/Logs.kt                registro en memoria que la tarjeta copia o borra
  vpn/Store.kt, State.kt     preferencias y estado observable de la interfaz
  vpn/NetInfo.kt             dirección LAN que se muestra al compartir
app/src/main/java/com/multicore/vpn/tunnel/
  Tun2Socks.kt               puente JNI a libc2tun (paquete fijado por el .so)
```

## Limitaciones

- Los dispositivos que comparten la conexión pasan por los puertos de proxy, no
  por el túnel: la VPN de Android no cubre a los clientes del anclaje, así que un
  dispositivo en tu punto de acceso debe poner el proxy a mano en
  `<phone-ip>:1080` (SOCKS) o `<phone-ip>:1081` (HTTP). Ver "Por qué compartir
  con otros dispositivos usa los puertos de proxy".
- Cambiar de modo o el interruptor de compartir se aplica en la siguiente
  conexión; la sesión en curso no se reinicia sola.
- El modo proxy no tiene kill switch ni enrutado por app.
