"""Verify retained upstream bytes and production CMake source-list coverage; no build/network."""
from hashlib import sha256
import json
from pathlib import Path
import re


def main():
    root = Path(__file__).resolve().parent
    manifest = json.loads((root / "source-manifest.json").read_text(encoding="utf-8"))
    checked = 0
    for record in [item for source in manifest["sources"] for item in source["files"]] + manifest["notices"]:
        path = (root / record["path"]).resolve()
        if root not in path.parents:
            raise ValueError("Manifest path escaped its source directory")
        data = path.read_bytes()
        if len(data) != record["bytes"] or sha256(data).hexdigest() != record["sha256"]:
            raise ValueError("Retained upstream bytes differ: " + record["path"])
        checked += 1
    selected = {"BCM_SOURCES", "BCM_INTERNAL_HEADERS", "BCM_SOURCES_ASM", "BCM_SOURCES_NASM",
                "CRYPTO_SOURCES", "CRYPTO_INTERNAL_HEADERS", "CRYPTO_HEADERS", "CRYPTO_SOURCES_ASM", "CRYPTO_SOURCES_NASM",
                "SSL_SOURCES", "SSL_INTERNAL_HEADERS", "SSL_HEADERS", "DECREPIT_SOURCES", "PKI_SOURCES", "BSSL_SOURCES"}
    cmake = (root / "boringssl/gen/sources.cmake").read_text(encoding="utf-8")
    coverage = []
    for variable, body in re.findall(r"set\(\s*(\w+)\s+(.*?)\)", cmake, re.S):
        if variable not in selected:
            continue
        for name in body.split():
            if not (root / "boringssl" / name).is_file():
                raise ValueError("Missing production CMake file: " + name)
            coverage.append(name)
    if len(coverage) != manifest["productionCoverage"]["boringsslCmakeFilesChecked"]:
        raise ValueError("Production CMake coverage count changed")
    for name in manifest["productionCoverage"]["conscryptJniSourcesChecked"]:
        if not (root / "conscrypt" / name).is_file():
            raise ValueError("Missing Conscrypt JNI file: " + name)
    print(f"Verified {checked} retained source/notice files and {len(coverage)} production CMake entries; no compilation performed.")


if __name__ == "__main__":
    main()
