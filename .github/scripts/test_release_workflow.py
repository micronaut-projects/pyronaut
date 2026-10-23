"""Run with python3 .github/scripts/test_release_workflow.py (requires PyYAML)."""

from pathlib import Path

import yaml


workflows = Path(__file__).resolve().parents[1] / "workflows"
publish = yaml.load((workflows / "publish-python-release.yml").read_text(), Loader=yaml.BaseLoader)
release = yaml.load((workflows / "release.yml").read_text(), Loader=yaml.BaseLoader)

# Keep the existing PyPI trusted-publisher identity and release event context.
assert publish["on"] == {"release": {"types": ["published"]}}
assert set(release["on"]) == {"workflow_call"}
jobs = publish["jobs"]
assert jobs["release-artifacts"]["uses"] == "./.github/workflows/release.yml"
assert jobs["release-artifacts"]["secrets"] == "inherit"
assert jobs["publish-pypi"]["environment"] == "pypi"
assert jobs["publish-pypi"]["permissions"]["id-token"] == "write"


def prerequisites(name, jobs):
    needs = jobs[name].get("needs", [])
    if isinstance(needs, str):
        needs = [needs]
    return set(needs).union(*(prerequisites(need, jobs) for need in needs))


# Every publication, including docs and provenance in the called workflow,
# must succeed before advancing the release branch.
assert prerequisites("advance-release-version", jobs) == set(jobs) - {"advance-release-version"}
assert prerequisites("github_release", release["jobs"]) == set(release["jobs"]) - {"github_release"}
bump = jobs["advance-release-version"]
assert "!contains(github.event.release.tag_name, '-')" in bump["if"]
assert bump["permissions"]["contents"] == "write"
checkout, advance = bump["steps"]
assert checkout["with"] == {"token": "${{ secrets.GH_TOKEN }}", "fetch-depth": "0"}
assert advance["env"]["RELEASE_TAG"] == "${{ github.event.release.tag_name }}"
assert advance["env"]["RELEASE_COMMIT"] == "${{ github.sha }}"
assert advance["run"] == 'python3 .github/scripts/bump_release_version.py "${RELEASE_TAG}" "${RELEASE_COMMIT}"'

print("Release workflow checks passed: publication ordering, PyPI identity, and bump inputs.")
