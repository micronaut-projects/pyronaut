"""Advance a published release's major.minor.x branch without moving its tag."""

import argparse
import os
from pathlib import Path
import re
import subprocess


def git(*args):
    return subprocess.check_output(["git", *args], text=True)


def bump_release_version(tag, release_commit):
    match = re.fullmatch(r"v?([0-9]+)\.([0-9]+)\.([0-9]+)", tag)
    if not match or not re.fullmatch(r"[0-9a-f]{40}", release_commit):
        raise ValueError("Expected an X.Y.Z release tag and its full commit SHA")
    major, minor, patch = map(int, match.groups())
    release_version = (major, minor, patch)
    branch = f"{major}.{minor}.x"
    next_version = f"{major}.{minor}.{patch + 1}-SNAPSHOT"

    git("fetch", "--no-tags", "origin", f"refs/heads/{branch}")
    git("merge-base", "--is-ancestor", release_commit, "FETCH_HEAD")
    text = git("show", "FETCH_HEAD:gradle.properties")
    versions = re.findall(r"^projectVersion=(.*)$", text, re.MULTILINE)
    current = re.fullmatch(r"([0-9]+)\.([0-9]+)\.([0-9]+)(?:-SNAPSHOT)?", versions[0]) if len(versions) == 1 else None
    if not current:
        raise ValueError(f"{branch} must contain one valid projectVersion")
    current_version = tuple(map(int, current.groups()))
    if current_version[:2] != release_version[:2] or current_version < release_version:
        raise ValueError(f"projectVersion={versions[0]} does not match release {tag} on {branch}")
    if current_version > release_version:
        print(f"{branch} already has projectVersion={versions[0]}; nothing to bump")
        return

    git("checkout", "--detach", "FETCH_HEAD")
    Path("gradle.properties").write_text(
        re.sub(r"^projectVersion=.*$", f"projectVersion={next_version}", text, count=1, flags=re.MULTILINE),
        encoding="utf-8",
    )
    git("add", "gradle.properties")
    email = os.environ.get("MICRONAUT_BUILD_EMAIL") or "41898282+github-actions[bot]@users.noreply.github.com"
    git("-c", "user.name=micronaut-build", "-c", f"user.email={email}",
        "commit", "-m", f"chore: Bump version to {next_version}")
    # A normal push fails if another release updated the branch in the meantime.
    git("push", "origin", f"HEAD:refs/heads/{branch}")
    print(f"Advanced {branch} to {next_version}; {tag} remains on {release_commit}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    parser.add_argument("release_commit")
    args = parser.parse_args()
    try:
        bump_release_version(args.tag, args.release_commit)
    except ValueError as error:
        parser.error(str(error))
