#!/usr/bin/env python3
"""Delete .patch / .patch.sha256 / .patch.sha512 assets from all releases
older than the three most recent non-draft, non-prerelease releases.

Environment:
    GITHUB_TOKEN — GitHub REST API token with write access to releases.
    API_URL — GitHub releases list endpoint (paginated).
"""

import json
import os
import urllib.request


PATCH_SUFFIXES = (".patch", ".patch.sha256", ".patch.sha512")


def main() -> int:
    request = urllib.request.Request(
        os.environ["API_URL"],
        headers={
            "Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    with urllib.request.urlopen(request) as response:
        releases = [
            release
            for release in json.load(response)
            if not release.get("draft") and not release.get("prerelease")
        ]
    for release in releases[3:]:
        for asset in release.get("assets", []):
            name = asset.get("name", "")
            if not name.endswith(PATCH_SUFFIXES):
                continue
            delete_request = urllib.request.Request(
                asset["url"],
                method="DELETE",
                headers={
                    "Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
                    "Accept": "application/vnd.github+json",
                    "X-GitHub-Api-Version": "2022-11-28",
                },
            )
            urllib.request.urlopen(delete_request).read()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
