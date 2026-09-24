#!/usr/bin/env python3
"""Write the ``pip install`` command for a release into its GitHub release notes.

Pyronaut is not published to PyPI yet. The SDK wheel is attached to each GitHub
release instead, and pip can install it straight from the release page: the
``--find-links`` option accepts any HTML page and collects the distribution
links it contains. ``--no-index`` keeps pip off PyPI, which is safe because the
wheel declares no dependencies.

The script resolves the wheel attached to a release, renders the matching
install snippet, and replaces the marked block in the release body. It is
idempotent: running it again rewrites the same block instead of appending a new
one.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tempfile
import time
from pathlib import Path

START_MARKER = "<!-- pyronaut:pip-install:start -->"
END_MARKER = "<!-- pyronaut:pip-install:end -->"
WHEEL_SUFFIX = ".whl"
WHEEL_PREFIX = "pyronaut-"


def gh(*args: str, capture: bool = True) -> str:
    """Runs the GitHub CLI and returns its standard output."""
    result = subprocess.run(
        ("gh",) + args,
        check=True,
        text=True,
        stdout=subprocess.PIPE if capture else None,
    )
    return (result.stdout or "").strip()


def fetch_release(repo: str, tag: str) -> dict:
    """Returns the release for a tag, including its current assets."""
    return json.loads(gh("api", f"/repos/{repo}/releases/tags/{tag}"))


def find_wheel(release: dict) -> dict | None:
    """Returns the SDK wheel asset of a release, or ``None`` while it is missing."""
    wheels = [
        asset
        for asset in release.get("assets", [])
        if asset["name"].startswith(WHEEL_PREFIX) and asset["name"].endswith(WHEEL_SUFFIX)
    ]
    if len(wheels) > 1:
        names = ", ".join(sorted(asset["name"] for asset in wheels))
        raise SystemExit(f"Expected one Pyronaut wheel on the release, found: {names}")
    return wheels[0] if wheels else None


def await_wheel(repo: str, tag: str, timeout: int, interval: int) -> tuple[dict, dict]:
    """Waits for the wheel to be attached to the release and returns both."""
    deadline = time.monotonic() + timeout
    while True:
        release = fetch_release(repo, tag)
        wheel = find_wheel(release)
        if wheel is not None:
            return release, wheel
        if time.monotonic() >= deadline:
            raise SystemExit(
                f"Release {tag} has no {WHEEL_PREFIX}*{WHEEL_SUFFIX} asset after {timeout}s. "
                "Attach the SDK wheel to the release, then re-run this workflow."
            )
        print(f"Waiting for the SDK wheel on {tag}...", flush=True)
        time.sleep(interval)


def wheel_version(name: str) -> str:
    """Returns the distribution version encoded in a wheel file name."""
    # Wheel names are ``<distribution>-<version>(-<build>)?-<python>-<abi>-<platform>.whl``.
    parts = name[: -len(WHEEL_SUFFIX)].split("-")
    if len(parts) < 5:
        raise SystemExit(f"Cannot read a version from the wheel name: {name}")
    return parts[1]


def is_private(repo: str) -> bool:
    """Returns whether the repository requires authentication to download assets."""
    return json.loads(gh("api", f"/repos/{repo}", "--jq", ".private"))


def render_block(repo: str, tag: str, version: str, private: bool) -> str:
    """Renders the install section for the release notes."""
    if private:
        install = "\n".join(
            [
                "```bash",
                'tmp="$(mktemp -d)" \\',
                f"  && gh release download {tag} --repo {repo} --pattern '{WHEEL_PREFIX}*{WHEEL_SUFFIX}' --dir \"$tmp\" \\",
                f'  && python3 -m pip install --upgrade --no-index "$tmp"/{WHEEL_PREFIX}*{WHEEL_SUFFIX}',
                "```",
                "",
                "The wheel is attached to a private repository, so pip cannot download it directly. "
                "The [GitHub CLI](https://cli.github.com) supplies the credentials.",
            ]
        )
    else:
        install = "\n".join(
            [
                "```bash",
                "python3 -m pip install --upgrade --no-index \\",
                f"  --find-links https://github.com/{repo}/releases/expanded_assets/{tag} \\",
                f"  'pyronaut=={version}'",
                "```",
                "",
                "Pyronaut is not on PyPI yet. `--find-links` reads the wheel from this release page "
                "and `--no-index` keeps pip off PyPI.",
            ]
        )
    return "\n".join(
        [
            START_MARKER,
            "## Install the Pyronaut CLI",
            "",
            f"Pyronaut {version} needs Python 3.10 or later.",
            "",
            install,
            "",
            "Then provision the SDK that runs Pyronaut applications:",
            "",
            "```bash",
            "pyronaut setup",
            "```",
            END_MARKER,
        ]
    )


def apply_block(body: str, block: str) -> str:
    """Replaces the marked block in the body, or adds it above the existing notes."""
    start = body.find(START_MARKER)
    end = body.find(END_MARKER)
    if start != -1 and end != -1:
        return body[:start] + block + body[end + len(END_MARKER) :]
    if not body.strip():
        return block + "\n"
    return block + "\n\n" + body.lstrip("\n")


def update_release(repo: str, release_id: int, body: str) -> None:
    """Writes the release body back to GitHub."""
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as handle:
        json.dump({"body": body}, handle)
        payload = Path(handle.name)
    try:
        gh("api", "-X", "PATCH", f"/repos/{repo}/releases/{release_id}", "--input", str(payload))
    finally:
        payload.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, help="Repository in OWNER/NAME form.")
    parser.add_argument("--tag", required=True, help="Release tag, for example v0.0.4.")
    parser.add_argument(
        "--timeout", type=int, default=600, help="Seconds to wait for the wheel asset."
    )
    parser.add_argument("--interval", type=int, default=15, help="Seconds between wheel checks.")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print the rendered notes instead of updating the release.",
    )
    args = parser.parse_args()

    release, wheel = await_wheel(args.repo, args.tag, args.timeout, args.interval)
    version = wheel_version(wheel["name"])
    block = render_block(args.repo, args.tag, version, is_private(args.repo))
    body = apply_block(release.get("body") or "", block)

    if args.dry_run:
        print(body)
        return 0

    if body == (release.get("body") or ""):
        print(f"Release notes for {args.tag} already document {wheel['name']}.")
        return 0

    update_release(args.repo, release["id"], body)
    print(f"Documented {wheel['name']} in the release notes for {args.tag}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
