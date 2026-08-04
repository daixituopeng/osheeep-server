#!/usr/bin/env python3
"""Download reviewed Commons ingredient photos and build app-sized WebP assets."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import urllib.parse
import urllib.request
from datetime import date
from pathlib import Path


LICENSE_PREFIXES = ("CC0", "Public domain", "CC BY", "CC-BY")


def download(url: str, destination: Path) -> None:
    curl = shutil.which("curl")
    if curl:
        subprocess.run(
            [
                curl,
                "-L",
                "-sS",
                "--fail",
                "--retry",
                "3",
                "--connect-timeout",
                "20",
                "--max-time",
                "120",
                "-A",
                "osheeep-image-sync/1.0 (image asset maintenance)",
                "-o",
                str(destination),
                url,
            ],
            check=True,
        )
        return
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "osheeep-image-sync/1.0 (image asset maintenance)"},
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        destination.write_bytes(response.read())


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def jpeg_dimensions(path: Path) -> tuple[int, int]:
    data = path.read_bytes()
    if data[:2] != b"\xff\xd8":
        raise RuntimeError(f"Expected a JPEG image: {path}")
    offset = 2
    while offset + 9 < len(data):
        if data[offset] != 0xFF:
            offset += 1
            continue
        marker = data[offset + 1]
        offset += 2
        if marker in {0xD8, 0xD9}:
            continue
        segment_length = int.from_bytes(data[offset : offset + 2], "big")
        if marker in {0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF}:
            height = int.from_bytes(data[offset + 3 : offset + 5], "big")
            width = int.from_bytes(data[offset + 5 : offset + 7], "big")
            return width, height
        offset += segment_length
    raise RuntimeError(f"Could not read JPEG dimensions: {path}")


def crop_box(width: int, height: int) -> tuple[int, int, int, int]:
    target_ratio = 8 / 5
    source_ratio = width / height
    if source_ratio > target_ratio:
        crop_height = height
        crop_width = int(round(height * target_ratio))
    else:
        crop_width = width
        crop_height = int(round(width / target_ratio))
    return (
        max(0, (width - crop_width) // 2),
        max(0, (height - crop_height) // 2),
        crop_width,
        crop_height,
    )


def encode_webp(
    cwebp: str,
    source: Path,
    destination: Path,
    source_width: int,
    source_height: int,
    width: int,
    height: int,
) -> None:
    left, top, crop_width, crop_height = crop_box(source_width, source_height)
    destination.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            cwebp,
            "-quiet",
            "-q",
            "78",
            "-metadata",
            "none",
            "-crop",
            str(left),
            str(top),
            str(crop_width),
            str(crop_height),
            "-resize",
            str(width),
            str(height),
            str(source),
            "-o",
            str(destination),
        ],
        check=True,
    )


def sync_asset(
    entry: dict,
    output_root: Path,
    static_root: Path,
    should_download: bool,
    should_process: bool,
    cwebp: str | None,
    force: bool,
) -> dict:
    slug = entry["slug"]
    asset_dir = output_root / slug
    asset_dir.mkdir(parents=True, exist_ok=True)
    metadata_path = asset_dir / "metadata.json"
    existing_original = asset_dir / "original.jpg"
    if should_download and not force and metadata_path.exists() and existing_original.exists():
        should_download = False

    if should_download:
        license_name = entry["licenseName"]
        if not license_name.startswith(LICENSE_PREFIXES):
            raise RuntimeError(
                f"Unsupported license for {entry['fileTitle']}: {license_name}"
            )
        encoded_title = urllib.parse.quote(entry["fileTitle"].replace(" ", "_"), safe="()_',")
        source_page_url = f"https://commons.wikimedia.org/wiki/File:{encoded_title}"
        redirect_url = (
            "https://commons.wikimedia.org/wiki/Special:Redirect/file/"
            + encoded_title
        )
        original_file_url = entry.get("downloadUrl", redirect_url)
        proxy_url = (
            "https://images.weserv.nl/?url="
            + original_file_url.removeprefix("https://")
            + "&output=jpg&q=96"
        )
        original_path = asset_dir / "original.jpg"
        download(proxy_url, original_path)
        original_width, original_height = jpeg_dimensions(original_path)
        metadata = {
            "provider": "WIKIMEDIA_COMMONS",
            "displayName": entry["displayName"],
            "ingredientNames": entry["ingredientNames"],
            "fileTitle": entry["fileTitle"],
            "sourcePageUrl": source_page_url,
            "originalFileUrl": original_file_url,
            "author": entry["author"],
            "licenseName": license_name,
            "licenseUrl": entry["licenseUrl"],
            "acquiredOn": date.today().isoformat(),
            "sha256": sha256(original_path),
            "originalWidth": original_width,
            "originalHeight": original_height,
            "originalObjectKey": f"internal/ingredients/{slug}/{original_path.name}",
            "listObjectKey": f"media/ingredients/{slug}-list.webp",
            "detailObjectKey": f"media/ingredients/{slug}-detail.webp",
            "status": "APPROVED",
        }
        metadata_path.write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
    elif not metadata_path.exists():
        raise RuntimeError(f"Missing metadata for {slug}: {metadata_path}")

    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    if should_process:
        if not cwebp:
            raise RuntimeError("cwebp is required to process ingredient images")
        original_path = asset_dir / Path(metadata["originalObjectKey"]).name
        if not original_path.exists():
            raise RuntimeError(f"Missing original for {slug}: {original_path}")
        encode_webp(
            cwebp,
            original_path,
            static_root / f"{slug}-list.webp",
            metadata["originalWidth"],
            metadata["originalHeight"],
            640,
            400,
        )
        encode_webp(
            cwebp,
            original_path,
            static_root / f"{slug}-detail.webp",
            metadata["originalWidth"],
            metadata["originalHeight"],
            1280,
            800,
        )
    return metadata


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output-root", type=Path, required=True)
    parser.add_argument("--static-root", type=Path)
    parser.add_argument("--download", action="store_true")
    parser.add_argument("--process", action="store_true")
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--slug", action="append", default=[])
    args = parser.parse_args()
    if not args.download and not args.process:
        parser.error("at least one of --download or --process is required")
    if args.process and not args.static_root:
        parser.error("--static-root is required with --process")

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    cwebp = shutil.which("cwebp") if args.process else None
    requested_slugs = set(args.slug)
    entries = [
        entry for entry in manifest
        if not requested_slugs or entry["slug"] in requested_slugs
    ]
    results = [
        sync_asset(
            entry,
            args.output_root,
            args.static_root,
            args.download,
            args.process,
            cwebp,
            args.force,
        )
        for entry in entries
    ]
    print(json.dumps({"count": len(results)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
