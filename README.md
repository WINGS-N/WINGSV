# WINGS V
`vk-turn-proxy` + WireGuard-клиент в интерфейсе Samsung One UI

## Скриншоты
![Главная](readme-res/screenshots/main.jpg)
![Роутинг приложений](readme-res/screenshots/appsrouting.jpg)
![VPN Hotspot](readme-res/screenshots/vpnhotspot.jpg)
![Настройки](readme-res/screenshots/settings.jpg)

## Что умеет

- запускать и останавливать `vk-turn-proxy` и WireGuard
- показывать статус подключения, IP, страну, провайдера и сетевую статистику
- работать в обычном VPN режиме через `VpnService`
- работать в root режиме через kernel WireGuard
- настраивать маршрутизацию по приложениям
- раздавать VPN через Wi‑Fi, USB, Bluetooth и Ethernet
- показывать live логи `vk-turn-proxy`
- импортить/экспортить конфигурации через wingsv:// ссылки

## `wingsv://` ссылки
- `wingsv://` ссылки представляют собой json, закодированный в base64, идущий после `://`
- Схема JSON ниже:

```json
  {
  "ver": 1,
  "type": "vk",
  "turn": {
    "endpoint": "turn-proxy-server-ip:56000",
    "link": "https://vk.com/call/join/.......",
    "threads": 4,
    "use_udp": true,
    "no_obfuscation": false,
    "local_endpoint": "127.0.0.1:9000",
    "host": "",
    "port": ""
  },
  "wg": {
    "if": {
      "private_key": "wireguard private key",
      "addrs": "10.0.0.2/32 [[ WireGuard allowed IPs ]]",
      "dns": "1.1.1.1, 1.0.0.1",
      "mtu": 1280
    },
    "peer": {
      "public_key": "WireGuard peer public key",
      "allowed_ips": "0.0.0.0/0, ::/0"
    }
  }
}

```

## кратко, что и откуда используется

- Java для основного приложения
- OneUI / SESL 8 для интерфейса
- `com.wireguard.android:tunnel` для WireGuard
- `external/vk-turn-proxy` источник vk-turn-proxy native binary
- `external/VPNHotspot` источник root tethering runtime

## Сборка

Нужно задать credentials для SESL GitHub Packages вне репозитория:

- `seslUser`
- `seslToken`

Локально:

```bash
# debug сборка
./gradlew :app:assembleDebug

# релизная сборка
./gradlew :app:assembleRelease
```

## Release

GitHub Actions собирают:

- CI debug build на `main`
- release APK по тегам `v*`



