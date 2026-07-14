import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("verify_release_inputs.py")
SPEC = importlib.util.spec_from_file_location("verify_release_inputs", SCRIPT)
assert SPEC and SPEC.loader
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class VerifyReleaseInputsTest(unittest.TestCase):
    def make_fixture(self) -> tuple[Path, Path, Path]:
        root = Path(tempfile.mkdtemp())
        dist = root / "dist"
        source_pin = root / "SOURCE_PIN.json"
        source = {
            "name": "scrcpy",
            "version": "4.0",
            "commit": "pinned-commit",
            "license": "Apache-2.0",
        }
        source_pin.write_text(json.dumps(source), encoding="utf-8")
        artifacts = []
        for target, relative_path in VERIFIER.REQUIRED_TARGETS.items():
            executable = dist / relative_path
            executable.parent.mkdir(parents=True, exist_ok=True)
            executable.write_bytes(target.encode("utf-8"))
            artifacts.append(
                {
                    "target": target,
                    "path": relative_path,
                    "sha256": hashlib.sha256(executable.read_bytes()).hexdigest(),
                    "signature": {
                        "verified": True,
                        "kind": "test-signature",
                        "identity": "test identity",
                    },
                }
            )
        (dist / "manifest.json").write_text(
            json.dumps({"schema": 1, "source": source, "artifacts": artifacts}),
            encoding="utf-8",
        )
        return root, dist, source_pin

    def test_accepts_complete_pinned_attested_input_set(self):
        root, dist, source_pin = self.make_fixture()
        self.addCleanup(lambda: __import__("shutil").rmtree(root))
        self.assertEqual([], VERIFIER.verify(dist, source_pin))

    def test_rejects_digest_drift_and_unverified_signature(self):
        root, dist, source_pin = self.make_fixture()
        self.addCleanup(lambda: __import__("shutil").rmtree(root))
        executable = dist / VERIFIER.REQUIRED_TARGETS["linux-x86_64"]
        executable.write_bytes(b"tampered")
        manifest_path = dist / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        for artifact in manifest["artifacts"]:
            if artifact["target"] == "windows-x86_64":
                artifact["signature"]["verified"] = False
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

        errors = VERIFIER.verify(dist, source_pin)

        self.assertIn("SHA-256 mismatch for linux-x86_64/andy-mirror", errors)
        self.assertIn(
            "windows-x86_64 has no verified platform-signature attestation", errors
        )
