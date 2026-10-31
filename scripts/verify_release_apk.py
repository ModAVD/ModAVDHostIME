"""Compare a release APK with a tag build outside the APK signing block."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import re
import struct
import subprocess
from pathlib import Path

MAGIC = b"APK Sig Block 42"
V2_ID = 0x7109871A
V3_ID = 0xF05368C0
VERITY_ID = 0x42726577


@dataclass(frozen=True)
class Layout:
    block_start: int
    block_end: int
    central_directory: int
    eocd: int


def parse_layout(data: bytes) -> Layout:
    eocd = data.rfind(b"PK\x05\x06")
    if eocd < 0 or eocd + 22 > len(data):
        raise ValueError("找不到 EOCD")
    disk, cd_disk, disk_entries, entries, cd_size, cd_offset = struct.unpack_from("<HHHHII", data, eocd + 4)
    if disk or cd_disk or disk_entries != entries:
        raise ValueError("不支持多磁盘 ZIP")
    if eocd + 22 + struct.unpack_from("<H", data, eocd + 20)[0] != len(data):
        raise ValueError("EOCD 不是文件末尾")
    if cd_offset + cd_size != eocd:
        raise ValueError("中央目录与 EOCD 不相邻")
    if cd_offset < 24:
        raise ValueError("签名块空间不足")
    if data[cd_offset - 16:cd_offset] != MAGIC:
        raise ValueError("APK Sig Block 42 魔数不对")
    last_size = struct.unpack_from("<Q", data, cd_offset - 24)[0]
    block_start = cd_offset - last_size - 8
    if block_start < 0 or block_start + 8 > cd_offset - 24:
        raise ValueError("APK 签名块长度不对")
    first_size = struct.unpack_from("<Q", data, block_start)[0]
    if first_size != last_size or block_start + first_size + 8 != cd_offset:
        raise ValueError("APK 签名块首尾长度不一致")
    pair_start = block_start + 8
    pair_end = cd_offset - 24
    ids = set()
    while pair_start < pair_end:
        if pair_start + 8 > pair_end:
            raise ValueError("APK 签名块 pair 长度缺失")
        pair_size = struct.unpack_from("<Q", data, pair_start)[0]
        pair_data = pair_start + 8
        pair_limit = pair_data + pair_size
        if pair_limit > pair_end or pair_size < 4:
            raise ValueError("APK 签名块 pair 越界")
        ids.add(struct.unpack_from("<I", data, pair_data)[0])
        pair_start = pair_limit
    if pair_start != pair_end or not {V2_ID, V3_ID, VERITY_ID}.issubset(ids):
        raise ValueError("APK 签名块缺少 v2/v3/verity pair")
    return Layout(block_start, cd_offset, cd_offset, eocd)


def normalized_central_and_eocd(data: bytes, layout: Layout) -> bytes:
    tail = bytearray(data[layout.central_directory:])
    eocd_in_tail = layout.eocd - layout.central_directory
    # The EOCD central-directory offset necessarily changes with the signing block.
    struct.pack_into("<I", tail, eocd_in_tail + 16, 0)
    return bytes(tail)


def compare_apks(built_signed: bytes, release: bytes) -> None:
    built_layout = parse_layout(built_signed)
    release_layout = parse_layout(release)
    built_block_length = built_layout.block_end - built_layout.block_start
    release_block_length = release_layout.block_end - release_layout.block_start
    if built_block_length != release_block_length or built_block_length != 8192:
        raise ValueError("RSA-4096 签名块长度不是双方相同的 8192 字节")
    if built_signed[:built_layout.block_start] != release[:release_layout.block_start]:
        raise ValueError("签名块之前的 APK 条目字节不同")
    if normalized_central_and_eocd(built_signed, built_layout) != normalized_central_and_eocd(release, release_layout):
        raise ValueError("APK 中央目录或 EOCD 不同")


def verify_certificate(apksigner: str, apk: Path, expected_sha256: str) -> str:
    result = subprocess.run(
        [apksigner, "verify", "--verbose", "--print-certs", "--min-sdk-version", "30", str(apk)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    output = result.stdout + result.stderr
    if result.returncode != 0:
        raise ValueError(f"apksigner verify 失败：\n{output}")
    if not re.search(r"Verified using v3 scheme \(APK Signature Scheme v3\): true", output, re.I):
        raise ValueError("发布 APK 没有通过 v3 验证")
    digests = [m.replace(":", "").lower() for m in re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-f:]+)", output, re.I)]
    if len(digests) != 1:
        raise ValueError(f"发布 APK 的签名者数量不是 1：{digests}")
    expected = expected_sha256.replace(":", "").lower()
    if digests[0] != expected:
        raise ValueError(f"证书摘要不符：{digests[0]} != {expected}")
    return digests[0]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--built-apk", type=Path, required=True)
    parser.add_argument("--release-apk", type=Path, required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--certificate-sha256", required=True)
    args = parser.parse_args()
    compare_apks(args.built_apk.read_bytes(), args.release_apk.read_bytes())
    digest = verify_certificate(args.apksigner, args.release_apk, args.certificate_sha256)
    print(f"签名以外的 APK 字节和中央目录一致；证书 SHA-256 {digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
