"""Read-only verification of the existing optional English FP32 resources.

The historical filename is retained; this tool does not prepare, copy, export,
install or publish models. EnglishAuxResources.kt remains the only runtime
authority for filenames, sizes, SHA-256 and HTTPS sources. Examples:
  python scripts/narration/prepare_english_ai_pack.py --verify-staged
  python scripts/narration/prepare_english_ai_pack.py --verify-source <existing directory>
  python scripts/narration/prepare_english_ai_pack.py --verify-bundle <existing app.aab>
  python scripts/narration/prepare_english_ai_pack.py --verify-urls

The first three checks are offline. Only the explicit --verify-urls action
reads the public HTTPS sources; it streams hashes without saving any payload.
Validating an AAB does not prove installation or delivery from Google Play.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import sys
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MANIFEST = ROOT / "app/src/main/java/com/learngraphicsprogramming/app/narration/EnglishAuxResources.kt"
DEFAULT_ASSETS = ROOT / "english_misaki/src/main/assets"
PACK_NAME = "english_misaki"
REQUIRED_NOTICES = frozenset({
    "licenses/NOTICE.txt", "licenses/APACHE-2.0.txt", "licenses/BART-ATTRIBUTION.txt",
    "licenses/SPACY-POS-ATTRIBUTION.txt", "licenses/SPACY-MODEL-MIT.txt",
    "licenses/SPACY-MIT.txt", "licenses/THINC-MIT.txt", "licenses/SPACY-LOOKUPS-MIT.txt",
})
REQUIRED_SOURCES = {
    "bart-us": "Apache-2.0", "bart-gb": "Apache-2.0", "spacy-pos": "MIT",
    "spacy-frontend": "MIT", "thinc-architecture": "MIT", "spacy-lexeme-normalization": "MIT",
}


@dataclass(frozen=True)
class Artifact:
    name: str
    size: int
    sha256: str
    url: str


def stable_https_url(url: str) -> bool:
    parsed = urllib.parse.urlsplit(url)
    return (parsed.scheme == "https" and bool(parsed.hostname) and not parsed.username
            and not parsed.password and not parsed.query and not parsed.fragment
            and parsed.path not in ("", "/") and "\\" not in url)


def contract(manifest: Path) -> list[Artifact]:
    text = manifest.read_text(encoding="utf-8-sig")
    release = re.search(r'const\s+val\s+release\s*=\s*"([^"$\\]+)"', text)
    base = re.search(r'const\s+val\s+downloadBase\s*=\s*"([^"\\]+)"', text)
    if release is None or base is None or 'sourceUrl = "$downloadBase/$name"' not in text:
        raise ValueError("Unsupported EnglishAuxResources HTTPS source contract")
    base_url = base[1].replace("$release", release[1])
    if "$" in base_url or not stable_https_url(base_url):
        raise ValueError("English resources require a stable absolute HTTPS source")
    matches = re.findall(r'\bartifact\("([^"\\/]+)",\s*(\d+)L,\s*"([a-f0-9]{64})"\)', text)
    artifacts = [Artifact(name, int(size), digest, base_url + "/" + name)
                 for name, size, digest in matches]
    if len(artifacts) != 8 or len({a.name for a in artifacts}) != 8:
        raise ValueError("EnglishAuxResources must declare exactly eight distinct artifacts")
    if sum(a.name.endswith(".onnx") for a in artifacts) != 5 or sum(a.name.endswith(".json") for a in artifacts) != 3:
        raise ValueError("Expected the existing five ONNX graphs and three JSON configurations")
    if any(a.size <= 0 or a.name in (".", "..") for a in artifacts):
        raise ValueError("Empty or invalid model artifacts are forbidden")
    return artifacts


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(64 * 1024):
            result.update(chunk)
    return result.hexdigest()


def verify_file(path: Path, artifact: Artifact) -> None:
    if not path.is_file() or path.is_symlink():
        raise ValueError(f"Missing original regular file: {path}")
    if path.stat().st_size != artifact.size or digest(path) != artifact.sha256:
        raise ValueError(f"Size/SHA-256 mismatch: {path}")


def validate_provenance(provenance: dict, artifacts: list[Artifact]) -> None:
    if provenance.get("format") != 1 or provenance.get("packName") != PACK_NAME:
        raise ValueError("Unsupported English resource provenance")
    if provenance.get("artifactContract") != "EnglishAuxResources.artifacts" or provenance.get("precision") != "FP32":
        raise ValueError("Provenance must preserve the existing FP32 artifact contract")
    if provenance.get("totalArtifactBytes") != sum(a.size for a in artifacts):
        raise ValueError("Provenance size differs from EnglishAuxResources")
    expected = {a.name: (a.size, a.sha256) for a in artifacts}
    entries = provenance.get("artifacts", [])
    actual = {a.get("fileName"): (a.get("bytes"), a.get("sha256")) for a in entries}
    if len(entries) != 8 or actual != expected:
        raise ValueError("Provenance hashes differ from EnglishAuxResources")
    sources = provenance.get("sources", [])
    source_licenses = {source.get("id"): source.get("license") for source in sources}
    if source_licenses != REQUIRED_SOURCES or len(sources) != len(REQUIRED_SOURCES):
        raise ValueError("Missing audited original sources/licenses")
    if any(a.get("source") not in REQUIRED_SOURCES for a in entries):
        raise ValueError("Unattributed artifact source")
    if any(not stable_https_url(source.get("sourceUrl", "")) or
           not stable_https_url(source.get("licenseDeclarationUrl", "")) for source in sources):
        raise ValueError("Missing original source/license references")
    notices = provenance.get("notices", [])
    if set(notices) != REQUIRED_NOTICES or len(notices) != len(REQUIRED_NOTICES):
        raise ValueError("Incomplete license/attribution notices")


def validate_metadata(assets: Path, artifacts: list[Artifact]) -> None:
    provenance = json.loads((assets / "provenance.json").read_text(encoding="utf-8-sig"))
    validate_provenance(provenance, artifacts)
    for name in REQUIRED_NOTICES:
        path = assets / name
        if not path.is_file() or path.is_symlink() or not path.read_text(encoding="utf-8-sig").strip():
            raise ValueError(f"Missing license notice: {name}")


def verify_source(source: Path, artifacts: list[Artifact]) -> None:
    for artifact in artifacts:
        verify_file(source / artifact.name, artifact)


def verify_staged(assets: Path, artifacts: list[Artifact]) -> None:
    validate_metadata(assets, artifacts)
    expected = {a.name for a in artifacts} | REQUIRED_NOTICES | {"provenance.json"}
    actual = {p.relative_to(assets).as_posix() for p in assets.rglob("*") if p.is_file()}
    if actual != expected:
        raise ValueError(f"Unexpected or missing resource assets: {sorted(actual ^ expected)}")
    verify_source(assets, artifacts)


class HttpsOnlyRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        if urllib.parse.urlsplit(new_url).scheme != "https":
            raise ValueError("A resource redirect must remain HTTPS")
        return super().redirect_request(request, response, code, message, headers, new_url)


def verify_urls(artifacts: list[Artifact]) -> int:
    if any(not stable_https_url(a.url) for a in artifacts):
        raise ValueError("Every resource needs a stable absolute HTTPS source")
    opener = urllib.request.build_opener(HttpsOnlyRedirects())
    total = 0
    for artifact in artifacts:
        result = hashlib.sha256()
        received = 0
        with opener.open(artifact.url, timeout=30) as response:
            if urllib.parse.urlsplit(response.geturl()).scheme != "https":
                raise ValueError("A resource response must remain HTTPS")
            while chunk := response.read(64 * 1024):
                received += len(chunk)
                if received > artifact.size:
                    raise ValueError("Hosted resource exceeds the pinned size: " + artifact.name)
                result.update(chunk)
        if received != artifact.size or result.hexdigest() != artifact.sha256:
            raise ValueError("Hosted resource size/SHA-256 mismatch: " + artifact.name)
        total += received
    return total


def verify_bundle(bundle: Path, artifacts: list[Artifact]) -> None:
    expected = {a.name for a in artifacts} | REQUIRED_NOTICES | {"provenance.json"}
    prefix = f"{PACK_NAME}/assets/"
    with zipfile.ZipFile(bundle) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate bundle ZIP entries")
        packaged = {name.removeprefix(prefix) for name in names if name.startswith(prefix) and not name.endswith("/")}
        if packaged != expected:
            raise ValueError(f"Missing or unexpected English pack assets: {sorted(packaged ^ expected)}")
        validate_provenance(json.loads(archive.read(prefix + "provenance.json")), artifacts)
        for name in REQUIRED_NOTICES:
            if not archive.read(prefix + name).decode("utf-8-sig").strip():
                raise ValueError(f"Empty license notice: {name}")
        # Keep the derived-data MIT notice in the base licenses screen even if a
        # future distribution provider removes its own delivered source files.
        persistent_notice = "base/res/raw/spacy_pos_attribution.txt"
        if persistent_notice not in names:
            raise ValueError("Missing persistent base spaCy lookups MIT attribution")
        def normalized(text: str) -> str:
            return text.replace("\r\n", "\n").replace("\r", "\n").strip()
        lookup_license = normalized(archive.read(prefix + "licenses/SPACY-LOOKUPS-MIT.txt").decode("utf-8-sig"))
        base_attribution = normalized(archive.read(persistent_notice).decode("utf-8-sig"))
        if not lookup_license or lookup_license not in base_attribution:
            raise ValueError("Persistent base attribution must retain the full spaCy lookups MIT notice")
        for artifact in artifacts:
            entry = archive.getinfo(prefix + artifact.name)
            if entry.file_size != artifact.size:
                raise ValueError(f"Packaged artifact size mismatch: {artifact.name}")
            result = hashlib.sha256()
            with archive.open(entry) as stream:
                while chunk := stream.read(64 * 1024):
                    result.update(chunk)
            if result.hexdigest() != artifact.sha256:
                raise ValueError(f"Packaged artifact SHA-256 mismatch: {artifact.name}")
            duplicates = [name for name in names if name != prefix + artifact.name
                          and PurePosixPath(name).name == artifact.name]
            if duplicates:
                raise ValueError(f"English resource duplicated outside AI pack: {duplicates}")
        if f"{PACK_NAME}/manifest/AndroidManifest.xml" not in names:
            raise ValueError("Missing AI pack manifest")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--assets", type=Path, default=DEFAULT_ASSETS)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--verify-staged", action="store_true")
    action.add_argument("--verify-source", type=Path)
    action.add_argument("--verify-bundle", type=Path)
    action.add_argument("--verify-urls", action="store_true")
    args = parser.parse_args()
    try:
        artifacts = contract(args.manifest)
        if args.verify_urls:
            total = verify_urls(artifacts)
            print(f"Eight public HTTPS responses match pinned sizes/SHA-256: {total} bytes; no files saved")
        elif args.verify_bundle:
            verify_bundle(args.verify_bundle, artifacts)
            print("Existing AAB: eight exact resources and attribution; Play installation has not been tested")
        elif args.verify_source:
            verify_source(args.verify_source, artifacts)
            print("Eight existing source files match pinned sizes/SHA-256; no files changed")
        else:
            verify_staged(args.assets, artifacts)
            print("Eight existing FP32 files and source/license metadata verified; no files changed")
        return 0
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        print(f"English resource verification FAILED: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
