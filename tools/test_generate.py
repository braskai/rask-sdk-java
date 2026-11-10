#!/usr/bin/env python3
"""Generator checks that need no JDK: S14's language marker, and the generated tree matching the contract."""
import copy
import json
import os
import subprocess
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GENERATOR = os.path.join(ROOT, "tools", "generate.py")
SPEC = os.path.join(ROOT, "spec", "openapi.json")


def generate(spec, out):
    subprocess.run([sys.executable, GENERATOR, "--spec", spec, "--out", out], check=True, capture_output=True)


class GeneratorTest(unittest.TestCase):
    def test_deprecated_operation_gets_the_java_marker(self):
        """S14.language-markers: the contract has no deprecated operation yet, so mark one in a copy."""
        with open(SPEC, encoding="utf-8") as handle:
            data = json.load(handle)
        marked = copy.deepcopy(data)
        marked["paths"]["/me"]["get"]["deprecated"] = True
        with tempfile.TemporaryDirectory() as directory:
            spec = os.path.join(directory, "openapi.json")
            with open(spec, "w", encoding="utf-8") as handle:
                json.dump(marked, handle)
            out = os.path.join(directory, "out")
            generate(spec, out)
            with open(os.path.join(out, "ai/rask/sdk/resources/AccountResource.java"), encoding="utf-8") as handle:
                source = handle.read()
        self.assertEqual(source.count("public RaskResponse<Me> getMe("), 2)
        self.assertEqual(source.count("@Deprecated\n    public RaskResponse<Me> getMe("), 2)
        self.assertEqual(source.count("@deprecated"), 2)
        self.assertNotIn("@Deprecated\n    public RaskResponse<Plan> getPlan(", source)

    def test_generated_tree_matches_the_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            generate(SPEC, directory)
            base = "ai/rask/sdk"
            for folder, _, files in os.walk(os.path.join(directory, base)):
                relative = os.path.relpath(folder, directory)
                for name in files:
                    with open(os.path.join(folder, name), encoding="utf-8") as fresh:
                        expected = fresh.read()
                    path = os.path.join(ROOT, "src/main/java", relative, name)
                    self.assertTrue(os.path.exists(path), f"{path} is missing: run make generate")
                    with open(path, encoding="utf-8") as current:
                        self.assertEqual(current.read(), expected, f"{path} is stale: run make generate")
            for folder in ("model", "params", "resources"):
                current = set()
                for name in os.listdir(os.path.join(ROOT, "src/main/java", base, folder)):
                    with open(os.path.join(ROOT, "src/main/java", base, folder, name), encoding="utf-8") as handle:
                        if handle.readline().startswith("// Generated"):
                            current.add(name)
                fresh = set(os.listdir(os.path.join(directory, base, folder)))
                self.assertEqual(current, fresh, f"stale files in {folder}")

    def test_all_operations_are_generated(self):
        with open(SPEC, encoding="utf-8") as handle:
            data = json.load(handle)
        ids = [o["operationId"] for item in data["paths"].values() for m, o in item.items()
               if m in ("get", "post", "put", "patch", "delete")]
        self.assertEqual(len(ids), 82)
        sources = ""
        folder = os.path.join(ROOT, "src/main/java/ai/rask/sdk/resources")
        for name in os.listdir(folder):
            with open(os.path.join(folder, name), encoding="utf-8") as handle:
                sources += handle.read()
        for operation in ids:
            self.assertIn(f" {operation}(", sources)


if __name__ == "__main__":
    unittest.main()
