#!/usr/bin/env python3
"""Print the browser_download_url of app-release.apk from the most recent
non-draft, non-prerelease GitHub release. Prints an empty line when no
previous release APK is available.

Environment:
    GITHUB_TOKEN — GitHub REST API token with read access to releases.
    API_URL — GitHub releases list endpoint (paginated).
"""

import json
import os
import sys
import urllib.request


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
        releases = json.load(response)
    for release in releases:
        if release.get("draft") or release.get("prerelease"):
            continue
        for asset in release.get("assets", []):
            if asset.get("name") == "app-release.apk":
                print(asset.get("browser_download_url", ""))
                return 0
    print("")
    return 0


if __name__ == "__main__":
    sys.exit(main())
