#!/usr/bin/env python3
"""Генерация wingsv:// ссылок для импорта в WINGS V Android.

Формат: wingsv://{base64url(0x12 + zlib(protobuf))}

Схема protobuf: app/src/main/proto/wingsv.proto
Репозиторий: https://github.com/WINGS-N/WINGSV

Использование:
    python3 gen_wingsv_link.py \\
        --server 1.2.3.4:56000 \\
        --vk-link "https://vk.com/call/join/..." \\
        --wg-privkey "base64key=" \\
        --wg-address "192.168.24.50/32" \\
        --wg-peer-pubkey "base64key="

Зависимости:
    pip install protobuf grpcio-tools
"""

import argparse
import base64
import importlib
import subprocess
import sys
import tempfile
import zlib
from pathlib import Path
from types import ModuleType
        
from grpc_tools import protoc as grpc_protoc


FORMAT_PROTOBUF_DEFLATE = 0x12
CURRENT_VERSION = 1

_DEFAULT_PROTO_REL = Path("app", "src", "main", "proto", "wingsv.proto")


def _find_proto(explicit_path: str | None) -> Path:
    """Определяет расположение wingsv.proto.

    Без явного пути ищет вверх по дереву каталогов от расположения скрипта
    до корня репозитория (ищет settings.gradle.kts как маркер).
    """
    if explicit_path:
        p = Path(explicit_path)
        if not p.is_file():
            msg = f"Proto-файл не найден: {p}"
            raise FileNotFoundError(msg)
        return p

    cur = Path(__file__).resolve().parent
    for _ in range(6):
        candidate = cur / _DEFAULT_PROTO_REL
        if candidate.is_file():
            return candidate
        if (cur / "settings.gradle.kts").is_file():
            break
        cur = cur.parent

    script_dir = Path(__file__).resolve().parent
    msg = (
        f"Не удалось найти wingsv.proto. Поиск от {script_dir}. "
        "Используйте --proto-path для указания пути."
    )
    raise FileNotFoundError(msg)


def _compile_proto(proto_path: Path) -> str:
    """Компилирует .proto в _pb2.py модуль, возвращает имя модуля."""
    proto_dir = str(proto_path.parent)
    proto_name = proto_path.stem
    out_dir = tempfile.mkdtemp(prefix="wingsv_proto_")
    out_file = Path(out_dir) / f"{proto_name}_pb2.py"

    # grpcio-tools: встроенный protoc на Python, системный protoc не нужен
    try:

        result = grpc_protoc.main(
            [
                "grpc_tools.protoc",
                f"--proto_path={proto_dir}",
                f"--python_out={out_dir}",
                proto_path.name,
            ]
        )
        if result != 0:
            msg = f"grpc_tools.protoc завершился с кодом {result}"
            raise RuntimeError(msg)
    except ImportError:
        # Фоллбэк на системный protoc
        proc = subprocess.run(
            [
                "protoc",
                f"--proto_path={proto_dir}",
                f"--python_out={out_dir}",
                proto_path.name,
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        if proc.returncode != 0:
            msg = (
                f"protoc завершился с ошибкой: {proc.stderr}\n"
                "Установите grpcio-tools (pip install grpcio-tools) "
                "или системный protoc."
            )
            raise RuntimeError(msg)

    if not out_file.is_file():
        msg = f"Скомпилированный proto не найден: {out_file}"
        raise RuntimeError(msg)

    sys.path.insert(0, out_dir)
    return f"{proto_name}_pb2"


def _load_proto(proto_path: Path) -> ModuleType:
    """Загружает скомпилированный protobuf-модуль."""
    return importlib.import_module(_compile_proto(proto_path))


def encode_wingsv_link(config: object) -> str:
    """Кодирует protobuf Config в wingsv:// ссылку.

    Формат: 0x12 (маркер) + zlib (RFC 1950, с заголовком) + base64url без паддинга.
    zlib-формат совместим с Java Deflater()/Inflater() по умолчанию.
    """
    protobuf_payload = config.SerializeToString()
    compressed = zlib.compress(protobuf_payload, zlib.Z_BEST_COMPRESSION)
    framed = bytes([FORMAT_PROTOBUF_DEFLATE]) + compressed
    encoded = base64.urlsafe_b64encode(framed).decode("ascii").rstrip("=")
    return f"wingsv://{encoded}"


def generate(
    pb: ModuleType,
    *,
    server_host: str,
    server_port: int,
    vk_link: str,
    wg_private_key: str,
    wg_address: str,
    wg_peer_pubkey: str,
    wg_dns: str = "1.1.1.1",
    threads: int = 4,
) -> str:
    """Собирает wingsv:// ссылку для конфигурации VK TURN + WireGuard."""
    config = pb.Config()
    config.ver = CURRENT_VERSION
    config.type = pb.CONFIG_TYPE_VK
    config.backend = pb.BACKEND_TYPE_VK_TURN_WIREGUARD

    config.turn.endpoint.host = server_host
    config.turn.endpoint.port = server_port
    config.turn.link = vk_link
    config.turn.threads = threads

    config.wg.iface.private_key = base64.b64decode(wg_private_key)
    config.wg.iface.addrs.append(wg_address)
    config.wg.iface.dns.append(wg_dns)
    config.wg.peer.public_key = base64.b64decode(wg_peer_pubkey)

    return encode_wingsv_link(config)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Генерация wingsv:// ссылки для импорта в WINGS V",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "Пример:\n"
            '  %(prog)s --server 1.2.3.4:56000 --vk-link "https://vk.com/call/join/..." \\\n'
            '    --wg-privkey "key=" --wg-address 10.0.0.2/32 --wg-peer-pubkey "key="'
        ),
    )
    parser.add_argument("--server", required=True, help="VK TURN сервер host:port")
    parser.add_argument("--vk-link", required=True, help="Ссылка на VK-звонок")
    parser.add_argument(
        "--wg-privkey", required=True, help="Приватный ключ WireGuard (base64)"
    )
    parser.add_argument("--wg-address", required=True, help="Адрес WireGuard (CIDR)")
    parser.add_argument(
        "--wg-peer-pubkey", required=True, help="Публичный ключ peer WireGuard (base64)"
    )
    parser.add_argument(
        "--wg-dns", default="1.1.1.1", help="DNS-сервер (по умолчанию: 1.1.1.1)"
    )
    parser.add_argument(
        "--threads",
        type=int,
        default=4,
        help="Количество TURN-потоков (по умолчанию: 4)",
    )
    parser.add_argument(
        "--proto-path",
        default=None,
        help="Путь к wingsv.proto (по умолчанию: автоопределение)",
    )

    args = parser.parse_args()
    host, port = args.server.rsplit(":", 1)

    proto_path = _find_proto(args.proto_path)
    pb = _load_proto(proto_path)

    link = generate(
        pb,
        server_host=host,
        server_port=int(port),
        vk_link=args.vk_link,
        wg_private_key=args.wg_privkey,
        wg_address=args.wg_address,
        wg_peer_pubkey=args.wg_peer_pubkey,
        wg_dns=args.wg_dns,
        threads=args.threads,
    )
    print(link)


if __name__ == "__main__":
    main()
