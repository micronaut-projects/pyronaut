"""Run with python3 .github/scripts/test_bump_release_version.py."""

import os
from pathlib import Path
import subprocess
import sys
import tempfile


script = Path(__file__).with_name("bump_release_version.py").resolve()
env = dict(os.environ, GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1")


def git(path, *args):
    return subprocess.check_output(["git", "-C", str(path), *args], env=env, text=True, stderr=subprocess.PIPE).strip()


for version, current, expected in [
    ("0.0.9", "0.0.9-SNAPSHOT", "0.0.10-SNAPSHOT"),
    ("0.1.1", "0.1.1-SNAPSHOT", "0.1.2-SNAPSHOT"),
    ("1.0.99", "1.0.99", "1.0.100-SNAPSHOT"),
    ("0.1.1", "0.1.3-SNAPSHOT", "0.1.3-SNAPSHOT"),
    ("0.1.1", "0.1.0-SNAPSHOT", None),
    ("0.1.1", "0.0.1-SNAPSHOT", None),
]:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        origin = root / "origin.git"
        origin.mkdir()
        branch = version.rsplit(".", 1)[0] + ".x"
        git(origin, "init", "--bare", f"--initial-branch={branch}")
        source = root / "source"
        source.mkdir()
        git(source, "init", f"--initial-branch={branch}")
        git(source, "config", "user.name", "Release test")
        git(source, "config", "user.email", "test@example.com")
        properties = source / "gradle.properties"
        original = f"projectVersion={current}\nprojectGroup=example\n\n"
        properties.write_text(original)
        git(source, "add", "gradle.properties")
        git(source, "commit", "-m", "Build source")
        release_commit = git(source, "rev-parse", "HEAD")
        tag = "v" + version
        git(source, "tag", tag)
        # A branch may receive unrelated changes while OraHub builds the draft.
        (source / "later.txt").write_text("keep this later change\n")
        git(source, "add", "later.txt")
        git(source, "commit", "-m", "Later change")
        git(source, "remote", "add", "origin", str(origin))
        git(source, "push", "origin", branch, f"refs/tags/{tag}")
        initial_head = git(origin, "rev-parse", branch)
        runner = root / "runner"
        git(root, "clone", str(origin), str(runner))
        command = [sys.executable, str(script), tag, release_commit]
        result = subprocess.run(command, cwd=runner, env=env, capture_output=True, text=True)
        if expected is None:
            assert result.returncode != 0, result.stdout
            assert git(origin, "rev-parse", branch) == initial_head
        else:
            assert result.returncode == 0, result.stderr
            assert git(origin, "show", f"{branch}:gradle.properties") == original.replace(current, expected).strip()
            assert git(origin, "show", f"{branch}:later.txt") == "keep this later change"
            bumped_head = git(origin, "rev-parse", branch)
            assert (bumped_head != initial_head) == (expected != current)
            assert subprocess.run(command, cwd=runner, env=env, capture_output=True).returncode == 0
            assert git(origin, "rev-parse", branch) == bumped_head
            invalid = subprocess.run([sys.executable, str(script), tag, "0" * 40], cwd=runner, env=env, capture_output=True)
            assert invalid.returncode != 0
            assert git(origin, "rev-parse", branch) == bumped_head
        assert git(origin, "rev-parse", tag) == release_commit

print("Release bump checks passed: branch selection, patch carry, retries, source validation, and unchanged release tags.")
