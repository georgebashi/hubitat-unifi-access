#!/usr/bin/env python3
import argparse
import json
import os
from pathlib import Path
import ssl
import sys
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(description="Read-only Access inventory; private responses stay in .secrets.")
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=12445)
    parser.add_argument("--allow-self-signed", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    private = root / ".secrets"
    token = (private / "access-token").read_text().strip()
    context = ssl._create_unverified_context() if args.allow_self_signed else ssl.create_default_context()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context))
    failed = False
    for resource in ("doors", "devices"):
        request = urllib.request.Request(
            f"https://{args.host}:{args.port}/api/v1/developer/{resource}",
            headers={"Authorization": f"Bearer {token}", "Accept": "application/json"},
        )
        try:
            with opener.open(request, timeout=15) as response:
                payload = json.load(response)
            destination = private / f"{resource}.json"
            descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(descriptor, "w") as output:
                json.dump(payload, output, indent=2)
            data = payload.get("data")
            if payload.get("code") != "SUCCESS":
                failed = True
            count = len(data) if isinstance(data, list) else "non-list"
            print(f"{resource}: success={payload.get('code') == 'SUCCESS'}, records={count}")
            if isinstance(data, list) and data and isinstance(data[0], dict):
                print(f"{resource} fields: {', '.join(sorted(data[0].keys()))}")
        except urllib.error.HTTPError as error:
            failed = True
            print(f"{resource}: HTTP {error.code}")
        except (OSError, ValueError):
            failed = True
            print(f"{resource}: transport or response error; details suppressed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
