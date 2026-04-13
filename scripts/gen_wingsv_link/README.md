# gen_wingsv_link

Генерация `wingsv://` ссылок для импорта конфигурации в WINGS V.

Ссылки кодируют конфигурацию VK TURN + WireGuard через protobuf + zlib + base64url — тот же формат, что использует встроенный импорт/экспорт приложения.

## Зависимости

```bash
pip install -r requirements.txt
```

- **protobuf** — сериализация protobuf-сообщений
- **grpcio-tools** — встроенный Python-компилятор protobuf (системный `protoc` не нужен)

Если `grpcio-tools` не установлен, скрипт использует системный `protoc` как фоллбэк.

## Использование

```bash
python3 scripts/gen_wingsv_link/gen_wingsv_link.py \
  --server 1.2.3.4:56000 \
  --vk-link "https://vk.com/call/join/..." \
  --wg-privkey "приватный_ключ_base64=" \
  --wg-address "10.0.0.2/32" \
  --wg-peer-pubkey "публичный_ключ_base64="
```

На выходе — `wingsv://` ссылка для импорта в WINGS V через буфер обмена или intent.

## Параметры

| Параметр | Обязательный | По умолчанию | Описание |
|----------|:------------:|:------------:|----------|
| `--server` | да | — | VK TURN прокси-сервер `host:port` |
| `--vk-link` | да | — | Ссылка на VK-звонок |
| `--wg-privkey` | да | — | Приватный ключ WireGuard (base64) |
| `--wg-address` | да | — | Адрес интерфейса WireGuard (CIDR) |
| `--wg-peer-pubkey` | да | — | Публичный ключ peer WireGuard (base64) |
| `--wg-dns` | нет | `1.1.1.1` | DNS-сервер |
| `--threads` | нет | `4` | Количество параллельных TURN-потоков |
| `--proto-path` | нет | авто | Путь к `wingsv.proto` |

## Автоопределение proto

Скрипт автоматически находит `app/src/main/proto/wingsv.proto` относительно своего расположения в репозитории — поднимается вверх по дереву каталогов до корня (маркер: `settings.gradle.kts`). Для ручного указания используйте `--proto-path`.

## Формат ссылки

```
wingsv://{base64url(0x12 + zlib(protobuf))}
```

- `0x12` — маркер формата (`FORMAT_PROTOBUF_DEFLATE`)
- zlib — RFC 1950 (с заголовком), совместим с Java `Deflater()`/`Inflater()` по умолчанию
- protobuf — сообщение `Config` из `wingsv.proto`
- base64url — RFC 4648 §5, без паддинга
