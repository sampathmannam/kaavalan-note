"""Exercise the actual Gradle signing guard using disposable, synthetic fixtures.

Only the allowlisted public build files are copied. Never copy local.properties,
user Gradle configuration, a keystore, or any other release material. The fixture
task only reports the signing configuration NAME; it never signs an APK.
Run with JAVA_HOME, ANDROID_HOME, and a repo-local GRADLE_USER_HOME set.
"""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
ERROR = "release signing was requested, but the existing signing configuration is unavailable or incomplete"
PUBLIC_BUILD_FILES = (
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle.properties",
    "gradle/libs.versions.toml",
    "app/build.gradle.kts",
    "app/proguard-rules.pro",
)


class ReleaseSigningIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        gradle_home = Path(os.environ["GRADLE_USER_HOME"]).resolve()
        if not gradle_home.is_relative_to(ROOT):
            raise RuntimeError("Use an isolated GRADLE_USER_HOME inside the development project")
        # Do not inherit real signing environment variables or Gradle project
        # properties. Preserve only the runtime locations needed for the test.
        cls.environment = {
            key: os.environ[key]
            for key in (
                "PATH", "HOME", "JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT",
                "GRADLE_USER_HOME", "ANDROID_USER_HOME", "TMPDIR", "LANG",
            )
            if key in os.environ
        }
        cls.fixture_parent = ROOT / "app/build/signing-contract-tests"
        cls.fixture_parent.mkdir(parents=True, exist_ok=True)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=self.fixture_parent)
        self.addCleanup(self.temporary.cleanup)
        self.fixture = Path(self.temporary.name)
        for relative in PUBLIC_BUILD_FILES:
            target = self.fixture / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, target)
        build = self.fixture / "app/build.gradle.kts"
        with build.open("a") as output:
            output.write('''
tasks.register("assertSigningMode") {
    doLast {
        println("TEST_RELEASE_CONFIG=" + android.buildTypes.getByName("release").signingConfig?.name)
    }
}
''')

    def run_gradle(self, *properties):
        return subprocess.run(
            [str(ROOT / "gradlew"), "-p", str(self.fixture),
             ":app:assertSigningMode", "--offline", "--no-daemon", "--max-workers=1",
             "--console=plain", "-Pkotlin.compiler.execution.strategy=in-process",
             *properties],
            cwd=self.fixture, env=self.environment, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180,
        )

    def assert_missing_signing(self, result):
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(ERROR, result.stdout)
        self.assertNotIn("BUILD SUCCESSFUL", result.stdout)
        self.assertNotIn("TEST_RELEASE_CONFIG=release", result.stdout)

    def test_default_development_ignores_local_signing_configuration(self):
        # Android/Kotlin plugins read local.properties independently of our
        # signing resolver. Use valid synthetic properties and verify they
        # cannot enable signing or cause missing-key failures without opt-in.
        (self.fixture / "local.properties").write_text(
            "KAAVALAN_RELEASE_STORE_FILE=synthetic-missing-key.fixture\n"
            "KAAVALAN_RELEASE_STORE_PASSWORD=synthetic-test-only\n"
        )
        result = self.run_gradle()
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("TEST_RELEASE_CONFIG=null", result.stdout)

    def test_requested_signing_without_configuration_fails(self):
        self.assert_missing_signing(self.run_gradle("-Pkaavalan.enableReleaseSigning=true"))

    def test_missing_key_file_fails_without_disclosing_configured_path(self):
        marker = "SYNTHETIC_PRIVATE_PATH_MUST_NOT_APPEAR"
        result = self.run_gradle(
            "-Pkaavalan.enableReleaseSigning=true",
            f"-PKAAVALAN_RELEASE_STORE_FILE={marker}",
            "-PKAAVALAN_RELEASE_STORE_PASSWORD=synthetic-test-only",
        )
        self.assert_missing_signing(result)
        self.assertNotIn(marker, result.stdout)
        self.assertNotIn("synthetic-test-only", result.stdout)

    def test_key_file_without_password_fails(self):
        (self.fixture / "not-a-real-key.fixture").write_text("synthetic fixture; not a keystore")
        self.assert_missing_signing(self.run_gradle(
            "-Pkaavalan.enableReleaseSigning=true",
            "-PKAAVALAN_RELEASE_STORE_FILE=not-a-real-key.fixture",
        ))

    def test_complete_synthetic_configuration_selects_release(self):
        # No signing task is executed and this file is not a usable key.
        (self.fixture / "not-a-real-key.fixture").write_text("synthetic fixture; not a keystore")
        result = self.run_gradle(
            "-Pkaavalan.enableReleaseSigning=true",
            "-PKAAVALAN_RELEASE_STORE_FILE=not-a-real-key.fixture",
            "-PKAAVALAN_RELEASE_STORE_PASSWORD=synthetic-test-only",
        )
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("TEST_RELEASE_CONFIG=release", result.stdout)
        self.assertNotIn("synthetic-test-only", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
