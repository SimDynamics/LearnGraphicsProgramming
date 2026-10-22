"""Read-only resource checks and in-memory fixtures; no model or network writes."""
import copy
from dataclasses import replace
import hashlib
import io
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import warnings
import zipfile

import prepare_english_ai_pack as pack


class MemoryFile:
    def __init__(self, data, *, exists=True, symlink=False):
        self.data, self.exists, self.symlink = data, exists, symlink

    def is_file(self):
        return self.exists

    def is_symlink(self):
        return self.symlink

    def stat(self):
        return SimpleNamespace(st_size=len(self.data))

    def open(self, mode):
        if mode != "rb":
            raise AssertionError("Verification may only read files")
        return io.BytesIO(self.data)


class Response(io.BytesIO):
    def geturl(self):
        return "https://release-assets.githubusercontent.com/existing-resource"


class EnglishAiPackTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.current = pack.contract(pack.DEFAULT_MANIFEST)
        cls.original_metadata = json.loads((pack.DEFAULT_ASSETS / "provenance.json").read_text())
        cls.notices = {name: (pack.DEFAULT_ASSETS / name).read_bytes() for name in pack.REQUIRED_NOTICES}
        cls.data = {a.name: f"in-memory fixture {index}".encode() for index, a in enumerate(cls.current)}
        cls.artifacts = [replace(a, size=len(cls.data[a.name]), sha256=hashlib.sha256(cls.data[a.name]).hexdigest())
                         for a in cls.current]

    def setUp(self):
        self.metadata = copy.deepcopy(self.original_metadata)
        self.metadata["totalArtifactBytes"] = sum(a.size for a in self.artifacts)
        source_by_name = {a["fileName"]: a["source"] for a in self.metadata["artifacts"]}
        self.metadata["artifacts"] = [dict(fileName=a.name, bytes=a.size, sha256=a.sha256,
                                           source=source_by_name[a.name]) for a in self.artifacts]

    def bundle(self, *, extras=None, missing=None, changed=None, duplicate=None):
        entries = {"english_misaki/assets/" + name: data for name, data in self.data.items()}
        entries.update({"english_misaki/assets/" + name: data for name, data in self.notices.items()})
        entries["english_misaki/assets/provenance.json"] = json.dumps(self.metadata).encode()
        entries["english_misaki/manifest/AndroidManifest.xml"] = b"<manifest/>"
        entries["base/res/raw/spacy_pos_attribution.txt"] = self.notices["licenses/SPACY-LOOKUPS-MIT.txt"]
        entries.update(extras or {})
        entries.update(changed or {})
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as target:
            for name, data in entries.items():
                if name != missing:
                    target.writestr(name, data)
            if duplicate:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    target.writestr(duplicate, entries[duplicate])
        archive.seek(0)
        return archive

    def test_current_https_contract_retains_exact_eight_original_resources(self):
        self.assertEqual(8, len(self.current))
        self.assertEqual(12_915_892, sum(a.size for a in self.current))
        self.assertTrue(all(a.url == "https://github.com/SimDynamics/LearnGraphicsProgramming-Resources/releases/download/english-misaki-fp32-v1/" + a.name
                            for a in self.current))
        pack.validate_provenance(self.original_metadata, self.current)

    def test_contract_rejects_missing_duplicate_or_old_local_only_artifacts(self):
        original = pack.DEFAULT_MANIFEST.read_text()
        for text in [original.replace('artifact("en_gb_bart.json"', 'artifact("en_us_bart.json"'),
                     original.replace('artifact("en_gb_bart.json"', 'unused("en_gb_bart.json"'),
                     original.replace('artifact("', 'KokoroModelManifest.Artifact("')]:
            with self.subTest(text=text[:35]), self.assertRaisesRegex(ValueError, "eight distinct"):
                pack.contract(Mock(read_text=Mock(return_value=text)))

    def test_contract_rejects_missing_or_unstable_https_source(self):
        original = pack.DEFAULT_MANIFEST.read_text()
        for text in [original.replace("https://github.com", "http://github.com"),
                     original.replace('$release"', '$release?signature=temporary"'),
                     original.replace('sourceUrl = "$downloadBase/$name"', 'sourceUrl = otherSource')]:
            with self.subTest(text=text[:35]), self.assertRaises(ValueError):
                pack.contract(Mock(read_text=Mock(return_value=text)))

    def test_current_files_and_metadata_validate_offline_without_changes(self):
        paths = [p for p in pack.DEFAULT_ASSETS.rglob("*") if p.is_file()]
        before = {p: (p.stat().st_size, p.stat().st_mtime_ns) for p in paths}
        with patch.object(pack.urllib.request, "build_opener", side_effect=AssertionError("Offline verification")):
            pack.verify_staged(pack.DEFAULT_ASSETS, self.current)
        self.assertEqual(before, {p: (p.stat().st_size, p.stat().st_mtime_ns) for p in paths})

    def test_file_check_rejects_missing_symlink_wrong_size_and_same_size_corruption(self):
        artifact = self.artifacts[0]
        data = self.data[artifact.name]
        pack.verify_file(MemoryFile(data), artifact)
        for file in [MemoryFile(data, exists=False), MemoryFile(data, symlink=True),
                     MemoryFile(data + b"x"), MemoryFile(b"x" * len(data))]:
            with self.subTest(file=file), self.assertRaises(ValueError):
                pack.verify_file(file, artifact)

    def test_provenance_rejects_changed_hash_precision_license_or_missing_source(self):
        mutations = [lambda m: m["artifacts"][0].update(sha256="0" * 64),
                     lambda m: m.update(precision="INT8"),
                     lambda m: m["sources"][0].update(license="unknown"),
                     lambda m: m["sources"].pop(),
                     lambda m: m["notices"].remove("licenses/SPACY-LOOKUPS-MIT.txt")]
        for mutate in mutations:
            changed = copy.deepcopy(self.metadata)
            mutate(changed)
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                pack.validate_provenance(changed, self.artifacts)

    def test_bundle_preserves_exact_models_and_all_notices(self):
        pack.verify_bundle(self.bundle(), self.artifacts)

    def test_bundle_rejects_changed_model_size_and_same_size_hash(self):
        name = self.artifacts[0].name
        for data in [self.data[name] + b"x", b"x" * len(self.data[name])]:
            with self.subTest(data=data), self.assertRaisesRegex(ValueError, "artifact.*mismatch"):
                pack.verify_bundle(self.bundle(changed={"english_misaki/assets/" + name: data}), self.artifacts)

    def test_bundle_rejects_missing_artifact_attribution_and_pack_manifest(self):
        for name in ["english_misaki/assets/" + self.artifacts[0].name,
                     "english_misaki/assets/licenses/SPACY-LOOKUPS-MIT.txt",
                     "english_misaki/manifest/AndroidManifest.xml",
                     "base/res/raw/spacy_pos_attribution.txt"]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                pack.verify_bundle(self.bundle(missing=name), self.artifacts)

    def test_bundle_rejects_incomplete_persistent_mit_attribution(self):
        with self.assertRaisesRegex(ValueError, "full spaCy lookups MIT"):
            pack.verify_bundle(self.bundle(changed={"base/res/raw/spacy_pos_attribution.txt": b"MIT"}), self.artifacts)

    def test_bundle_rejects_unrelated_shared_kokoro_or_duplicate_model(self):
        for name in ["english_misaki/assets/model.onnx", "base/assets/" + self.artifacts[0].name]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                pack.verify_bundle(self.bundle(extras={name: b"not permitted"}), self.artifacts)

    def test_bundle_rejects_duplicate_archive_entries(self):
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            pack.verify_bundle(self.bundle(duplicate="english_misaki/assets/" + self.artifacts[0].name), self.artifacts)

    def test_stable_url_guard_rejects_credentials_queries_fragments_and_http(self):
        for url in ["http://example.invalid/model", "https://name:secret@example.invalid/model",
                    "https://example.invalid/model?signature=expiring", "https://example.invalid/model#fragment",
                    "https://example.invalid/", "https://example.invalid/\\model"]:
            with self.subTest(url=url), patch.object(pack.urllib.request, "build_opener") as network:
                with self.assertRaisesRegex(ValueError, "stable absolute HTTPS"):
                    pack.verify_urls([replace(self.artifacts[0], url=url)])
                network.assert_not_called()

    def test_explicit_url_check_streams_each_pinned_hash_without_files(self):
        with patch.object(pack.urllib.request, "build_opener") as create:
            opener = create.return_value
            opener.open.side_effect = [Response(self.data[a.name]) for a in self.artifacts]
            self.assertEqual(sum(a.size for a in self.artifacts), pack.verify_urls(self.artifacts))
            self.assertEqual(8, opener.open.call_count)
            self.assertEqual([a.url for a in self.artifacts], [call.args[0] for call in opener.open.call_args_list])

    def test_url_check_rejects_corruption_size_and_insecure_response(self):
        artifact = self.artifacts[0]
        class InsecureResponse(Response):
            def geturl(self):
                return "http://example.invalid/model"
        for response in [Response(b"x" * artifact.size), Response(b"x" * (artifact.size + 1)),
                         InsecureResponse(self.data[artifact.name])]:
            with self.subTest(response=response), patch.object(pack.urllib.request, "build_opener") as create:
                create.return_value.open.return_value = response
                with self.assertRaises(ValueError):
                    pack.verify_urls([artifact])

    def test_https_redirect_cannot_downgrade_transport(self):
        handler = pack.HttpsOnlyRedirects()
        request = pack.urllib.request.Request("https://example.invalid/model")
        with self.assertRaisesRegex(ValueError, "remain HTTPS"):
            handler.redirect_request(request, None, 302, "", {}, "http://example.invalid/model")


if __name__ == "__main__":
    unittest.main()
