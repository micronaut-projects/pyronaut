#!/usr/bin/env python3
"""Document PyPI and GitHub-release installation choices in release notes."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time

START_MARKER = "<!-- pyronaut:pip-install:start -->"
END_MARKER = "<!-- pyronaut:pip-install:end -->"
WHEEL_PREFIX = "pyronaut-"
WHEEL_SUFFIX = ".whl"


def gh(*args: str, input_text: str | None = None) -> str:
    result = subprocess.run(
        ("gh",) + args,
        check=True,
        text=True,
        input=input_text,
        stdout=subprocess.PIPE,
    )
    return result.stdout.strip()


def fetch_release(repo: str, tag: str) -> dict:
    return json.loads(gh("api", f"/repos/{repo}/releases/tags/{tag}"))


def find_wheel(release: dict) -> dict | None:
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
    deadline = time.monotonic() + timeout
    while True:
        release = fetch_release(repo, tag)
        wheel = find_wheel(release)
        if wheel is not None:
            return release, wheel
        if time.monotonic() >= deadline:
            raise SystemExit(
                f"Release {tag} has no {WHEEL_PREFIX}*{WHEEL_SUFFIX} asset after {timeout}s"
            )
        print(f"Waiting for the SDK wheel on {tag}...", flush=True)
        time.sleep(interval)


def wheel_version(name: str) -> str:
    parts = name[: -len(WHEEL_SUFFIX)].split("-")
    if len(parts) < 5:
        raise SystemExit(f"Cannot read a version from the wheel name: {name}")
    return parts[1]


def github_install(repo: str, tag: str, version: str, private: bool) -> str:
    if private:
        return "\n".join(
            [
                "```bash",
                'tmp="$(mktemp -d)" \\',
                f"  && gh release download {tag} --repo {repo} --pattern '{WHEEL_PREFIX}*{WHEEL_SUFFIX}' --dir \"$tmp\" \\",
                f'  && python3 -m pip install --upgrade --no-index "$tmp"/{WHEEL_PREFIX}*{WHEEL_SUFFIX}',
                "```",
                "",
                "The GitHub CLI supplies the credentials needed to download a private release asset.",
            ]
        )
    return "\n".join(
        [
            "```bash",
            "python3 -m pip install --upgrade --no-index \\",
            f"  --find-links https://github.com/{repo}/releases/expanded_assets/{tag} \\",
            f"  'pyronaut=={version}'",
            "```",
        ]
    )


def render_block(repo: str, tag: str, version: str, private: bool) -> str:
    return "\n".join(
        [
            START_MARKER,
            "## Install the Pyronaut CLI",
            "",
            f"Pyronaut {version} needs Python 3.10 or later.",
            "",
            "Install from PyPI:",
            "",
            "```bash",
            f"python3 -m pip install --upgrade 'pyronaut=={version}'",
            "```",
            "",
            "Or install the wheel directly from this GitHub release:",
            "",
            github_install(repo, tag, version, private),
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
    start = body.find(START_MARKER)
    end = body.find(END_MARKER)
    if start != -1 and end != -1:
        return body[:start] + block + body[end + len(END_MARKER) :]
    if not body.strip():
        return block + "\n"
    return block + "\n\n" + body.lstrip("\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--interval", type=int, default=15)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    release, wheel = await_wheel(args.repo, args.tag, args.timeout, args.interval)
    version = wheel_version(wheel["name"])
    private = json.loads(gh("api", f"/repos/{args.repo}", "--jq", ".private"))
    body = apply_block(
        release.get("body") or "",
        render_block(args.repo, args.tag, version, private),
    )
    if args.dry_run:
        print(body)
    elif body != (release.get("body") or ""):
        gh(
            "api",
            "-X",
            "PATCH",
            f"/repos/{args.repo}/releases/{release['id']}",
            "--input",
            "-",
            input_text=json.dumps({"body": body}),
        )
        print(f"Updated release notes for {args.tag} with PyPI and GitHub install options.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
