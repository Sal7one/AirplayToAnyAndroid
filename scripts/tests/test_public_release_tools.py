import importlib.util
import os
import pathlib
import subprocess
import tempfile
import unittest
from unittest import mock


SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "prepare-public-source.py"
SPEC = importlib.util.spec_from_file_location("public_source", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class PublicSourceTests(unittest.TestCase):
    @unittest.skipUnless(os.environ.get("GITLEAKS_BIN"), "Set GITLEAKS_BIN for the real scanner rejection check")
    def test_real_scanner_rejects_a_nonissued_canary_in_copied_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            # Never authenticate with this synthetic, non-issued test string.
            canary = ("gh" + "p_") + "Ab3Cd4Ef5Gh6Ij7Kl8Mn9Op0Qr1St2Uv3Wx4"
            (root / "README.md").write_text("token = " + canary)
            manifest = root / "app/src/main/AndroidManifest.xml"
            manifest.parent.mkdir(parents=True)
            manifest.write_text("<manifest />")
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            subprocess.run(["git", "-C", str(root), "-c", "user.name=Fixture",
                            "-c", "user.email=fixture@example.invalid",
                            "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit",
                            "--allow-empty", "-qm", "fixture"], check=True)
            output = root / "export"
            with self.assertRaises(subprocess.CalledProcessError) as failure:
                MODULE.export(root, output, pathlib.Path(os.environ["GITLEAKS_BIN"]))
            self.assertEqual(failure.exception.cmd[0], os.environ["GITLEAKS_BIN"])
            self.assertEqual(failure.exception.returncode, 1)
            self.assertFalse(output.exists())
            self.assertEqual((root / "README.md").read_text(), "token = " + canary)

    def test_allowed_source_and_licenses(self):
        for name in ("README.md", "LICENSE", "app/.gitignore", "app/src/main/cpp/CMakeLists.txt",
                     "app/src/main/assets/licenses/apple-alac.txt", "app/src/main/cpp/audio_alac/LICENSE",
                     "app/vendor/NOTICE", "app/vendor/COPYING", "app/vendor/AUTHORS", "gradle/wrapper/gradle-wrapper.jar",
                     "docs/dependency-inventory.json", "scripts/prepare-public-source.py"):
            self.assertTrue(MODULE.allowed(name), name)

    def test_private_and_unrelated_paths_cannot_enter_export(self):
        for name in (".git/config", ".idea/gradle.xml", "local.properties", "app/local.properties",
                     "app/src/main/assets/.env", "app/src/main/assets/secrets.properties",
                     "docs/spike_logs/private/frame.h264", "app/build/outputs/app.apk",
                     "lan-anyflip/main.go", "releases/release.p12", "gradle/keys/release.jks",
                     "gradle/release-signing.properties", "docs/capture.pcapng", "142",
                     "app/src/main/cpp/receiver_jni.cpp", "app/src/main/cpp/airplay_server.cpp",
                     "/etc/passwd", "app/../../private.txt", "app/lib/unknown.jar"):
            self.assertFalse(MODULE.allowed(name), name)

    def test_refuses_existing_destination_without_touching_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            output = root / "existing"
            output.mkdir()
            sentinel = output / "private.txt"
            sentinel.write_text("keep")
            with self.assertRaises(ValueError):
                MODULE.export(root, output, pathlib.Path("unused"))
            self.assertEqual(sentinel.read_text(), "keep")

    def test_source_symlink_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / "README.md").symlink_to(root / "outside")
            with mock.patch.object(subprocess, "check_output", return_value=b"README.md\0"):
                with self.assertRaises(ValueError):
                    MODULE.export(root, root / "output", pathlib.Path("unused"))

    def test_scanner_failure_removes_only_own_staging_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / "README.md").write_text("source")
            manifest = root / "app/src/main/AndroidManifest.xml"
            manifest.parent.mkdir(parents=True)
            manifest.write_text("<manifest />")
            output = root / "export"
            listing = b"README.md\0app/src/main/AndroidManifest.xml\0"
            with mock.patch.object(subprocess, "check_output", side_effect=[listing, b"revision\n"]), \
                 mock.patch.object(subprocess, "run", side_effect=subprocess.CalledProcessError(1, "scanner")):
                with self.assertRaises(subprocess.CalledProcessError):
                    MODULE.export(root, output, pathlib.Path("scanner"))
            self.assertFalse(output.exists())
            self.assertFalse(list(root.glob(".fossmirror-source-*")))
            self.assertEqual((root / "README.md").read_text(), "source")

    def test_successful_snapshot_has_hashes_and_no_history(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / "README.md").write_text("source")
            manifest = root / "app/src/main/AndroidManifest.xml"
            manifest.parent.mkdir(parents=True)
            manifest.write_text("<manifest />")
            listing = b"README.md\0app/src/main/AndroidManifest.xml\0.git/config\0lan-anyflip/main.go\0"
            output = root / "export"
            with mock.patch.object(subprocess, "check_output", side_effect=[listing, b"revision\n"]), \
                 mock.patch.object(subprocess, "run") as scanner:
                result = MODULE.export(root, output, pathlib.Path("scanner"))
            self.assertEqual(len(result["files"]), 2)
            self.assertFalse(result["history_included"])
            self.assertFalse(result["publication_approved"])
            self.assertFalse((output / ".git").exists())
            self.assertFalse((output / "lan-anyflip").exists())
            self.assertTrue((output / "SOURCE_MANIFEST.json").is_file())
            self.assertTrue(all(len(entry["sha256"]) == 64 for entry in result["files"]))
            self.assertTrue(scanner.call_args.kwargs["check"])


if __name__ == "__main__":
    unittest.main()
