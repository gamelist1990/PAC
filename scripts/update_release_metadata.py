#!/usr/bin/env python3
"""Keep the public release summary in sync with Gradle and Paper metadata."""

from __future__ import annotations

import argparse
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PROPERTIES = ROOT / "gradle.properties"
PLUGIN = ROOT / "src/main/resources/plugin.yml"
BUILD = ROOT / "build.gradle"
README = ROOT / "README.md"
START = "<!-- pac-release-status:start -->"
END = "<!-- pac-release-status:end -->"


def metadata() -> tuple[re.Match[str], str, str, str]:
    properties = PROPERTIES.read_text(encoding="utf-8")
    version = re.search(r"(?m)^version[ \t]*=[ \t]*(\d+)\.(\d+)\.(\d+)[ \t]*$", properties)
    if version is None:
        raise ValueError("gradle.properties needs a numeric major.minor.patch version")
    plugin = PLUGIN.read_text(encoding="utf-8")
    minecraft = re.search(r"(?m)^api-version:[ \t]*['\"]?([0-9]+(?:\.[0-9]+)+)['\"]?[ \t]*$", plugin)
    if minecraft is None:
        raise ValueError("plugin.yml needs one numeric api-version")
    paper = re.search(r"paperDevBundle\(['\"]([0-9]+(?:\.[0-9]+)+)\.build\.",
                      BUILD.read_text(encoding="utf-8"))
    if paper is None or paper.group(1) != minecraft.group(1):
        raise ValueError("plugin.yml api-version and Paper dev bundle disagree")
    return version, minecraft.group(1), properties, plugin


def render_status(version: str, minecraft: str, repository: str | None) -> str:
    lines = [START,
             f"**最新版:** `v{version}` · **対応Minecraft:** `{minecraft}`（Paper）  "]
    if repository:
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise ValueError("repository must be the GitHub owner/repo slug")
        url = f"https://github.com/{repository}"
        badge = f"https://img.shields.io/github/downloads/{repository}/total?label=Releases%20downloads"
        lines.append(f"[![Releases downloads]({badge})]({url}/releases) · "
                     f"[最新版をダウンロード]({url}/releases/latest)")
    else:
        lines.append("**GitHub Releases 累計ダウンロード:** 初回リリース後に表示されます。")
    lines.append(END)
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bump-commits", type=int, default=0,
                        help="advance patch once for each pushed source commit")
    parser.add_argument("--repository", help="GitHub owner/repo for live release links")
    parser.add_argument("--github-output", type=Path,
                        help="append version and Minecraft values to GITHUB_OUTPUT")
    parser.add_argument("--check", action="store_true", help="verify files without editing")
    args = parser.parse_args()
    if args.bump_commits < 0 or args.check and args.bump_commits:
        parser.error("--bump-commits must be nonnegative and cannot be combined with --check")

    match, minecraft, properties, _ = metadata()
    major, minor, patch = map(int, match.groups())
    version = f"{major}.{minor}.{patch + args.bump_commits}"
    readme = README.read_text(encoding="utf-8")
    region = re.compile(re.escape(START) + r".*?" + re.escape(END), re.DOTALL)
    if len(region.findall(readme)) != 1:
        raise ValueError("README.md needs exactly one release-status region")
    updated = region.sub(render_status(version, minecraft, args.repository), readme)
    if args.check:
        if updated != readme:
            raise ValueError("README release status is out of sync")
    else:
        PROPERTIES.write_text(properties[:match.start()] + f"version={version}"
                              + properties[match.end():], encoding="utf-8")
        README.write_text(updated, encoding="utf-8")
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as output:
            output.write(f"version={version}\nminecraft={minecraft}\n")
    print(f"PAC v{version} / Minecraft {minecraft}")


if __name__ == "__main__":
    main()
