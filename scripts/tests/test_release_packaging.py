import copy
import importlib.util
import pathlib
import unittest


SPEC = importlib.util.spec_from_file_location("release", pathlib.Path(__file__).resolve().parents[1] / "package-release.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ReleasePackagingTests(unittest.TestCase):
    def setUp(self):
        self.metadata = {"applicationId": "com.sal7one.tvtestproject", "variantName": "release", "elements": [
            {"outputFile": f"app-{abi}-release.apk", "filters": [] if abi == "universal" else [{"filterType": "ABI", "value": abi}],
             "versionCode": 8, "versionName": "1.1.0-alpha.7"}
            for abi in sorted(MODULE.ABIS | {"universal"})]}

    def test_accepts_complete_consistent_release(self):
        version, outputs = MODULE.release_outputs(self.metadata)
        self.assertEqual(("1.1.0-alpha.7", 8), version)
        self.assertEqual(MODULE.ABIS | {"universal"}, set(outputs))

    def test_rejects_debug_missing_and_duplicate_outputs(self):
        for mutate in (lambda m: m.update(variantName="debug"),
                       lambda m: m["elements"].pop(),
                       lambda m: m["elements"].append(copy.deepcopy(m["elements"][0]))):
            with self.subTest(mutate=mutate):
                metadata = copy.deepcopy(self.metadata)
                mutate(metadata)
                with self.assertRaises(ValueError):
                    MODULE.release_outputs(metadata)

    def test_rejects_unsafe_paths_versions_and_mismatches(self):
        for key, value in (("outputFile", "../private.apk"), ("versionName", "../../private"), ("versionCode", 9)):
            with self.subTest(key=key):
                metadata = copy.deepcopy(self.metadata)
                metadata["elements"][0][key] = value
                with self.assertRaises(ValueError):
                    MODULE.release_outputs(metadata)

    def test_rejects_unknown_filters(self):
        self.metadata["elements"][0]["filters"] = [{"filterType": "DENSITY", "value": "xxhdpi"}]
        with self.assertRaises(ValueError):
            MODULE.release_outputs(self.metadata)
