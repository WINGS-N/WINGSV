# Mock-сервер обновлений приложения

Локальная заглушка GitHub Releases API для `AppUpdateManager`. Позволяет
прогнать полный цикл «проверка → скачивание → патч → установка» на
подключённом устройстве, не выкатывая реальный релиз.

Учитывается только в **debug**-сборках (`BuildConfig.DEBUG`); release-APK
override игнорирует и всегда ходит на `api.github.com`.

## Что нужно

- Подключённое устройство с включённой USB-отладкой
- Root на устройстве (только для `setprop`; `adb reverse` работает без него)
- На хосте: `python3`, `zstd`, `adb`, `sha256sum`, `sha512sum`

## Полный сценарий

```bash
# 1. Поставьте debug-сборку базовой версии на устройство, например 4.4.1:
./gradlew :app:assembleDebug -Pver=4.4.1
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. Сгенерируйте фикстуры (вытянет установленный apk как базу,
#    соберёт new.apk на TARGET_VERSION, сделает zstd-патч и releases.json):
TARGET_VERSION=4.4.99 ./scripts/app-updates-server/prepare-fixtures.sh

# 3. Запустите mock-сервер (порт по умолчанию 8080):
python3 scripts/app-updates-server/mock-update-server.py &

# 4. Прокиньте loopback устройства на хост и направьте AppUpdateManager
#    на mock:
adb reverse tcp:8080 tcp:8080
adb shell "su -c 'setprop debug.wingsv.releases_url http://127.0.0.1:8080/releases'"

# 5. Откройте приложение → «О приложении» → «Скачать обновление».
#    Сработает patch-путь; AboutAppActivity покажет download → patching → ready.

# 6. Уборка по окончании:
adb shell "su -c 'setprop debug.wingsv.releases_url \"\"'"
adb reverse --remove tcp:8080
kill %1   # python-сервер
```

## Структура

```
scripts/app-updates-server/
├── mock-update-server.py     # отдаёт /releases и /assets/<file> из fixtures/
├── prepare-fixtures.sh       # тянет базовый apk, собирает целевой, делает патч+checksums+json
├── fixtures/                 # в .gitignore, наполняется prepare-fixtures.sh
└── README.md
```

`prepare-fixtures.sh` принимает `BASE_VERSION` (по умолчанию — то, что сейчас
установлено на устройстве), `TARGET_VERSION` (по умолчанию `99.99.99`) и
`WINGSV_MOCK_PORT` (по умолчанию `8080`).

