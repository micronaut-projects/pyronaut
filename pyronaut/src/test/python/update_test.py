import importlib.util
import io
import os
import sys
import tempfile
import types
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

_CLI_MODULE_PATH = Path(__file__).resolve().parents[2] / "main" / "python" / "pyronaut_cli_v2" / "cli.py"
_CLI_SPEC = importlib.util.spec_from_file_location("pyronaut_cli_v2.cli", _CLI_MODULE_PATH)
if _CLI_SPEC is None or _CLI_SPEC.loader is None:
    raise RuntimeError("Failed loading pyronaut_cli_v2.cli for tests")
if str(_CLI_MODULE_PATH.parent.parent) not in sys.path:
    sys.path.insert(0, str(_CLI_MODULE_PATH.parent.parent))

sys.modules.pop("pyronaut_cli_v2", None)
pkg = types.ModuleType("pyronaut_cli_v2")
pkg.__path__ = [str(_CLI_MODULE_PATH.parent)]
sys.modules["pyronaut_cli_v2"] = pkg

cli = importlib.util.module_from_spec(_CLI_SPEC)
_CLI_SPEC.loader.exec_module(cli)


def _github_release(tag, *, prerelease=False, draft=False, wheel=True):
    version = tag.removeprefix("v")
    assets = [{"name": f"pyronaut-dev-macos-aarch64-{version}.tar.gz", "url": f"https://api.github.com/assets/{tag}-dev"}]
    if wheel:
        wheel_version = version.replace("-RC", "rc")
        assets.append({"name": f"pyronaut-{wheel_version}-py3-none-any.whl", "url": f"https://api.github.com/assets/{tag}-wheel"})
    return {"tag_name": tag, "prerelease": prerelease, "draft": draft, "assets": assets}


def _release(tag, *, prerelease=False, wheel=True):
    version = cli._parse_sdk_version(tag)
    return cli._SdkRelease(
        tag=tag,
        version=version,
        prerelease=prerelease or version.prerelease,
        wheel_name=f"pyronaut-{tag.removeprefix('v')}-py3-none-any.whl" if wheel else None,
        wheel_url=f"https://api.github.com/assets/{tag}" if wheel else None,
    )


class SdkVersionTest(unittest.TestCase):
    def test_orders_release_tags_maven_and_pep440_versions(self):
        ordered = [
            "0.0.4", "0.0.5.dev0", "0.0.5-SNAPSHOT", "1.0.0-M1", "1.0.0-M2",
            "1.0.0rc1", "v1.0.0-RC2", "1.0.0", "1.0.1", "1.10.0",
        ]
        parsed = [cli._parse_sdk_version(value) for value in ordered]
        self.assertNotIn(None, parsed)
        self.assertEqual(parsed[1], parsed[2])
        for lower, higher in zip(parsed, parsed[1:]):
            self.assertLessEqual(lower, higher)
        self.assertLess(parsed[0], parsed[1])
        self.assertLess(parsed[-3], parsed[-2])
        self.assertEqual(cli._parse_sdk_version("1.0"), cli._parse_sdk_version("v1.0.0"))

    def test_marks_qualified_versions_as_prereleases_and_rejects_non_versions(self):
        self.assertTrue(cli._parse_sdk_version("1.0.0-M1").prerelease)
        self.assertTrue(cli._parse_sdk_version("0.0.6.dev0").prerelease)
        self.assertFalse(cli._parse_sdk_version("0.0.5").prerelease)
        self.assertIsNone(cli._parse_sdk_version("current"))
        self.assertIsNone(cli._parse_sdk_version("1.0.0-weird1"))


class ReleaseSelectionTest(unittest.TestCase):
    def setUp(self):
        self.releases = [
            _release("v1.1.0-M1"),
            _release("v1.0.1"),
            _release("v1.0.0"),
            _release("v1.2.0", wheel=False),
            _release("v0.9.0", prerelease=True),
        ]

    def test_stable_installation_only_moves_to_stable_releases(self):
        selected = cli._select_update_release(self.releases, installed_prerelease=False, requested=None)
        self.assertEqual("v1.0.1", selected.tag)

    def test_prerelease_installation_may_move_to_a_newer_prerelease(self):
        selected = cli._select_update_release(self.releases, installed_prerelease=True, requested=None)
        self.assertEqual("v1.1.0-M1", selected.tag)

    def test_github_prerelease_flag_makes_a_release_a_prerelease(self):
        releases = [_release("v0.0.5", prerelease=True), _release("v0.0.4", prerelease=True)]
        self.assertIsNone(cli._select_update_release(releases, installed_prerelease=False, requested=None))
        installed = cli._parse_sdk_version("0.0.4")
        self.assertTrue(cli._installed_sdk_is_prerelease(installed, releases))
        self.assertEqual(
            "v0.0.5",
            cli._select_update_release(releases, installed_prerelease=True, requested=None).tag,
        )

    def test_requested_version_matches_tag_with_or_without_prefix(self):
        self.assertEqual("v1.0.0", cli._select_update_release(self.releases, installed_prerelease=False, requested="1.0.0").tag)
        self.assertEqual("v1.0.0", cli._select_update_release(self.releases, installed_prerelease=False, requested="v1.0").tag)
        self.assertEqual("v1.1.0-M1", cli._select_update_release(self.releases, installed_prerelease=False, requested="1.1.0-M1").tag)

    def test_requested_version_must_exist_and_carry_a_wheel(self):
        with self.assertRaisesRegex(RuntimeError, "3.0.0 was not found"):
            cli._select_update_release(self.releases, installed_prerelease=False, requested="3.0.0")
        with self.assertRaisesRegex(RuntimeError, "has no pyronaut wheel"):
            cli._select_update_release(self.releases, installed_prerelease=False, requested="1.2.0")

    def test_lists_releases_across_pages_skipping_drafts_and_non_version_tags(self):
        first_page = [_github_release(f"v0.{index}.0") for index in range(99)] + [_github_release("nightly")]
        second_page = [_github_release("v2.0.0", draft=True), _github_release("v1.0.0-RC1", prerelease=False)]
        pages = {1: first_page, 2: second_page}
        requested_urls = []

        def fake_json(url, headers):
            requested_urls.append(url)
            return pages.get(int(url.rsplit("page=", 1)[1]), [])

        with patch.object(cli, "_github_api_json", side_effect=fake_json), \
                patch.object(cli, "_read_pyronaut_user_settings", return_value={}):
            releases = cli._sdk_releases()
            with_drafts = cli._sdk_releases(allow_draft=True)

        self.assertEqual(2, len(requested_urls) // 2)
        self.assertTrue(requested_urls[0].startswith("https://api.github.com/repos/micronaut-projects/pyronaut/releases?"))
        self.assertEqual(100, len(releases))
        milestone = next(release for release in releases if release.tag == "v1.0.0-RC1")
        self.assertTrue(milestone.prerelease)
        self.assertEqual("pyronaut-1.0.0rc1-py3-none-any.whl", milestone.wheel_name)
        self.assertEqual("1.0.0rc1", milestone.display_version)
        self.assertIn("v2.0.0", {release.tag for release in with_drafts})

    def test_listing_failure_hints_at_a_token(self):
        with patch.object(cli, "_github_api_json", return_value=None), \
                patch.object(cli, "_read_pyronaut_user_settings", return_value={}), \
                patch.object(cli, "_github_token", return_value=None):
            with self.assertRaisesRegex(RuntimeError, "GH_TOKEN"):
                cli._sdk_releases()


class UpdateArgumentsTest(unittest.TestCase):
    def test_accepts_an_optional_version_and_known_options(self):
        self.assertEqual((None, []), cli._parse_update_arguments([]))
        self.assertEqual(
            ("1.0.0", ["--check", "--progress", "off", "--local-repository=/repo"]),
            cli._parse_update_arguments(["1.0.0", "--check", "--progress", "off", "--local-repository=/repo"]),
        )

    def test_rejects_unknown_options_invalid_and_repeated_versions(self):
        for args, message in (
            (["--bogus"], "Unknown pyronaut update option"),
            (["latest"], "Invalid Pyronaut version"),
            (["1.0.0", "1.0.1"], "single version"),
            (["--progress", "loud"], "Invalid value for --progress"),
            (["--progress"], "Missing value"),
        ):
            with self.assertRaisesRegex(ValueError, message):
                cli._parse_update_arguments(args)

    def test_help_does_not_require_an_installed_wheel(self):
        stdout = io.StringIO()
        with redirect_stdout(stdout):
            self.assertEqual(cli.SUCCESS, cli.run(["update", "--help"], platform_name="linux"))
        self.assertIn("Usage: pyronaut update [<version>]", stdout.getvalue())


class RunUpdateTest(unittest.TestCase):
    def setUp(self):
        self._temp = tempfile.TemporaryDirectory()
        self.home = Path(self._temp.name) / "home"
        self.home.mkdir()
        self.repository = self.home / ".m2" / "repository"
        self.commands = []
        self.patches = [
            patch.object(cli.Path, "home", return_value=self.home),
            # These tests cover the ~/.pyronaut layout, whatever the platform.
            patch.dict(os.environ, {"PYRONAUT_XDG": "false"}),
            patch.object(cli, "_setup_is_required", return_value=True),
            patch.object(cli, "_read_pyronaut_user_settings", return_value={}),
            patch.object(cli, "_setup_local_repository", return_value=self.repository),
            patch.object(cli, "_download_url_with_progress", side_effect=self._download),
        ]
        for active in self.patches:
            active.start()
        self.downloads = []

    def tearDown(self):
        for active in reversed(self.patches):
            active.stop()
        self._temp.cleanup()

    def _download(self, url, destination, label, headers=None):
        self.downloads.append((url, destination.name, headers))
        destination.write_bytes(b"wheel")

    def _runner(self, code=0, setup_code=0):
        def run(command_line, env):
            self.commands.append((list(command_line), env))
            if command_line[1:4] == ["-m", "pip", "install"]:
                self.assertTrue(Path(command_line[-1]).is_file())
                return code
            return setup_code
        return run

    def _populate_versions(self, *versions):
        for version in versions:
            maven_version = version.replace(".dev0", "-SNAPSHOT")
            for directory in (
                self.home / ".pyronaut" / "bin" / maven_version / "macos-aarch64",
                self.home / ".pyronaut" / "tools" / version / "current",
                self.home / ".pyronaut" / "setup" / version / "macos-aarch64",
                self.repository / "io" / "micronaut" / "pyronaut" / "micronaut-pyronaut-run" / maven_version,
            ):
                directory.mkdir(parents=True)
                (directory / "payload").write_bytes(b"x" * 100)

    def _run(self, args, releases, installed="0.0.4", runner=None):
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(cli, "_installed_pyronaut_version", return_value=installed), \
                patch.object(cli, "_sdk_releases", return_value=releases), \
                redirect_stdout(stdout), redirect_stderr(stderr):
            code = cli._run_update(args, runner or self._runner())
        return code, stdout.getvalue(), stderr.getvalue()

    def test_installs_wheel_runs_new_setup_and_removes_older_versions(self):
        self._populate_versions("0.0.3", "0.0.4", "0.0.5", "0.0.6.dev0")
        code, stdout, stderr = self._run(["--progress", "off"], [_release("v0.0.5"), _release("v0.0.4")])

        self.assertEqual(cli.SUCCESS, code, stderr)
        self.assertEqual(
            [("https://api.github.com/assets/v0.0.5", "pyronaut-0.0.5-py3-none-any.whl")],
            [download[:2] for download in self.downloads],
        )
        self.assertEqual("application/octet-stream", self.downloads[0][2]["Accept"])
        (pip, _), (setup, setup_env) = self.commands
        self.assertEqual([sys.executable, "-m", "pip", "install"], pip[:4])
        self.assertIn("--no-deps", pip)
        self.assertEqual(
            [sys.executable, "-m", "pyronaut_cli_v2", "setup", "--local-repository", str(self.repository), "--progress", "off"],
            setup,
        )
        self.assertIn(cli._PROGRESS_EPOCH_ENV, setup_env)
        self.assertIn("Pyronaut updated from 0.0.4 to 0.0.5; removed 0.0.3, 0.0.4 and freed 800B", stdout)
        self.assertEqual(
            ["0.0.5", "0.0.6-SNAPSHOT"],
            sorted(path.name for path in (self.home / ".pyronaut" / "bin").iterdir()),
        )
        self.assertEqual(
            ["0.0.5", "0.0.6.dev0"],
            sorted(path.name for path in (self.home / ".pyronaut" / "setup").iterdir()),
        )
        self.assertEqual(
            ["0.0.5", "0.0.6-SNAPSHOT"],
            sorted(path.name for path in (self.repository / "io" / "micronaut" / "pyronaut" / "micronaut-pyronaut-run").iterdir()),
        )
        self.assertEqual([], list((self.home / ".pyronaut" / "update").iterdir()))

    def test_keep_old_skips_cleanup(self):
        self._populate_versions("0.0.4")
        code, stdout, _ = self._run(["--keep-old", "--progress", "off"], [_release("v0.0.5")])
        self.assertEqual(cli.SUCCESS, code)
        self.assertEqual("Pyronaut updated from 0.0.4 to 0.0.5\n", stdout)
        self.assertTrue((self.home / ".pyronaut" / "bin" / "0.0.4").is_dir())

    def test_reports_up_to_date_without_downloading(self):
        code, stdout, _ = self._run(["--progress", "off"], [_release("v0.0.4"), _release("v0.0.3")])
        self.assertEqual(cli.SUCCESS, code)
        self.assertEqual("Pyronaut 0.0.4 is up to date\n", stdout)
        self.assertEqual([], self.downloads)
        self.assertEqual([], self.commands)

    def test_snapshot_newer_than_latest_release_is_up_to_date(self):
        code, stdout, _ = self._run(["--progress", "off"], [_release("v0.0.5")], installed="0.0.6.dev0")
        self.assertEqual(cli.SUCCESS, code)
        self.assertEqual("Pyronaut 0.0.6.dev0 is up to date\n", stdout)

    def test_check_only_reports_the_available_release(self):
        code, stdout, _ = self._run(["--check", "--progress", "off"], [_release("v0.0.5")])
        self.assertEqual(cli.SUCCESS, code)
        self.assertEqual("Pyronaut 0.0.5 is available. Run pyronaut update to install it.\n", stdout)
        self.assertEqual([], self.downloads)

    def test_explicit_version_may_downgrade_and_keeps_newer_versions(self):
        self._populate_versions("0.0.3", "0.0.4")
        code, stdout, stderr = self._run(["v0.0.3", "--progress", "off"], [_release("v0.0.4"), _release("v0.0.3")])
        self.assertEqual(cli.SUCCESS, code, stderr)
        self.assertIn("Pyronaut updated from 0.0.4 to 0.0.3\n", stdout)
        self.assertTrue((self.home / ".pyronaut" / "bin" / "0.0.4").is_dir())

    def test_failed_pip_install_stops_before_setup(self):
        self._populate_versions("0.0.4")
        code, _, stderr = self._run(["--progress", "off"], [_release("v0.0.5")], runner=self._runner(code=1))
        self.assertEqual(cli.PRECONDITION_FAILED, code)
        self.assertIn("pip failed to install pyronaut-0.0.5-py3-none-any.whl", stderr)
        self.assertEqual(1, len(self.commands))
        self.assertTrue((self.home / ".pyronaut" / "bin" / "0.0.4").is_dir())

    def test_failed_setup_keeps_older_versions(self):
        self._populate_versions("0.0.4")
        code, _, stderr = self._run(["--progress", "off"], [_release("v0.0.5")], runner=self._runner(setup_code=8))
        self.assertEqual(cli.PRECONDITION_FAILED, code)
        self.assertIn("Pyronaut 0.0.5 is installed, but its setup failed", stderr)
        self.assertTrue((self.home / ".pyronaut" / "bin" / "0.0.4").is_dir())

    def test_pinned_native_image_version_is_kept(self):
        self._populate_versions("0.0.4")
        with patch.object(cli, "_read_pyronaut_user_settings", return_value={"native-images": {"version": "0.0.4"}}):
            removed, _ = cli._remove_older_sdk_versions(cli._parse_sdk_version("0.0.5"), self.repository)
        self.assertEqual(["0.0.4"], removed)
        self.assertTrue((self.home / ".pyronaut" / "bin" / "0.0.4").is_dir())
        self.assertFalse((self.home / ".pyronaut" / "tools" / "0.0.4").exists())

    def test_source_checkout_cannot_update(self):
        with patch.object(cli, "_setup_is_required", return_value=False):
            code, _, stderr = self._run([], [_release("v0.0.5")])
        self.assertEqual(cli.PRECONDITION_FAILED, code)
        self.assertIn("source checkout", stderr)


if __name__ == "__main__":
    unittest.main()
