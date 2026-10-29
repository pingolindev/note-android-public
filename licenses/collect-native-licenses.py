#!/usr/bin/env python3
# Copyright (c) 2026 Pingolin LLC
#
# This file is part of Pingolin Note.
#
# Pingolin Note is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# Pingolin Note is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with Pingolin Note. If not, see <https://www.gnu.org/licenses/>.

"""Inventory the third-party Rust crates that are statically linked into the
prebuilt native libraries this repository ships.

The Gradle licence plugin cannot see any of them. It walks a variant's JVM
runtime classpath and reads each dependency's POM; these crates have no POM and
are on no classpath. They are nonetheless redistributed by this project, because
library/src/main/jniLibs/*/*.so are committed here and packaged into the APK, so
their notices belong in THIRD_PARTY_LICENSES.md.

Two cargo workspaces are involved, both in the sibling `mobile-sdk` checkout:

    libpingolin_mobile.so  <-  mobile-sdk/crates/mobile-sdk  (its own workspace;
                               excluded from the root one over sqlite link conflicts)
    libnote_collab_sdk.so  <-  mobile-sdk                    (pkg note-collab-sdk)

Usage:
    ./licenses/collect-native-licenses.py > licenses/native-licenses.json
    ./licenses/collect-native-licenses.py --mobile-sdk ../mobile-sdk
    MOBILE_SDK_DIR=/path/to/mobile-sdk ./licenses/collect-native-licenses.py

With neither flag nor environment variable, the mobile-sdk checkout is assumed
to sit beside this repository, which is the layout the README documents and the
one the crates' own path-dependencies already require.

Requires `cargo` on PATH. Resolution is offline: everything needed is in the
checked-in Cargo.lock files, so this neither builds nor downloads anything.
"""
import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

# Pingolin's own crates. They are covered by this repository's own licence, so
# they are reported as first-party rather than as third-party attribution.
FIRST_PARTY = {"mobile-sdk", "note-collab-sdk", "pingolin-crypto", "track-proto"}

# Both ABIs in library/src/main/jniLibs/. Taking the union matters: cfg(target)
# gates mean a crate can be linked into one ABI and not the other, and the APK
# carries both.
TARGETS = ["aarch64-linux-android", "armv7-linux-androideabi"]

# "<name> v<version>" with an optional trailing "(...)" note.
CRATE_LINE = re.compile(r"^([A-Za-z0-9_.+-]+) v([^\s(]+)(?:\s+\((.*)\))?$")


def run(cwd, args):
    try:
        proc = subprocess.run(args, cwd=cwd, capture_output=True, text=True,
                              timeout=300)
    except FileNotFoundError:
        sys.exit("error: `cargo` not found on PATH. Install a Rust toolchain "
                 "(https://rustup.rs) to regenerate this inventory.")
    if proc.returncode != 0:
        sys.exit(f"error: {' '.join(args)}\n  in {cwd}\n{proc.stderr.strip()}")
    return proc.stdout


def linked_crates(cwd, package, target):
    """Crates reachable from `package` over normal (non-dev, non-build) edges."""
    out = run(cwd, ["cargo", "tree", "-p", package, "-e", "normal",
                    "--target", target, "--prefix", "none", "--offline"])
    found = set()
    for raw in out.splitlines():
        m = CRATE_LINE.match(raw.strip())
        if not m:
            continue
        name, version, note = m.group(1), m.group(2), m.group(3) or ""
        # A proc-macro runs inside the compiler and contributes no code to the
        # shipped .so, so it is not redistributed and needs no notice here.
        if "proc-macro" in note:
            continue
        found.add((name, version))
    return found


def package_metadata(cwd):
    out = run(cwd, ["cargo", "metadata", "--format-version", "1", "--offline"])
    return {(p["name"], p["version"]): p for p in json.loads(out)["packages"]}


def resolve_mobile_sdk(explicit):
    repo_root = Path(__file__).resolve().parent.parent
    candidate = Path(explicit) if explicit else repo_root.parent / "mobile-sdk"
    candidate = candidate.expanduser().resolve()
    if not (candidate / "crates" / "mobile-sdk" / "Cargo.toml").is_file():
        sys.exit(
            f"error: no mobile-sdk checkout at {candidate}\n"
            f"  (looked for {candidate / 'crates' / 'mobile-sdk' / 'Cargo.toml'})\n"
            "  Clone mobile-sdk beside this repository, or pass --mobile-sdk "
            "PATH / set MOBILE_SDK_DIR."
        )
    return candidate


def main():
    ap = argparse.ArgumentParser(
        description="Inventory the Rust crates linked into the shipped .so files.")
    ap.add_argument("--mobile-sdk", default=os.environ.get("MOBILE_SDK_DIR"),
                    help="path to the mobile-sdk checkout "
                         "(default: $MOBILE_SDK_DIR, else ../mobile-sdk)")
    args = ap.parse_args()

    sdk = resolve_mobile_sdk(args.mobile_sdk)
    libraries = [
        ("libpingolin_mobile.so", sdk / "crates" / "mobile-sdk", "mobile-sdk"),
        ("libnote_collab_sdk.so", sdk, "note-collab-sdk"),
    ]

    shipped_in, metadata = {}, {}
    for so, cwd, package in libraries:
        metadata.update(package_metadata(cwd))
        for target in TARGETS:
            for key in linked_crates(cwd, package, target):
                shipped_in.setdefault(key, set()).add(so)

    entries, first_party, undeclared = [], set(), []
    for key in sorted(shipped_in, key=lambda k: (k[0].lower(), k[1])):
        name, version = key
        if name in FIRST_PARTY:
            first_party.add(name)
            continue
        pkg = metadata.get(key)
        if pkg is None:
            undeclared.append(f"{name} {version}: absent from cargo metadata")
            continue
        licence = (pkg.get("license") or "").strip()
        if not licence:
            undeclared.append(
                f"{name} {version}: no `license` field "
                f"(license_file={pkg.get('license_file')})")
            continue
        entries.append({
            "name": name,
            "version": version,
            "license": licence,
            "url": pkg.get("repository") or pkg.get("homepage"),
            "authors": pkg.get("authors") or [],
            "shipped_in": sorted(shipped_in[key]),
        })

    # A crate whose licence we cannot state is the one case that must not pass
    # silently: it would drop out of the notices with nothing to show for it.
    if undeclared:
        sys.exit("error: cannot determine a licence for:\n  " +
                 "\n  ".join(undeclared))

    json.dump({
        "_comment": (
            "GENERATED by licenses/collect-native-licenses.py — do not edit by hand. "
            "Third-party Rust crates statically linked into the .so files under "
            "library/src/main/jniLibs/. Regenerate whenever those binaries are "
            "rebuilt against changed dependencies."
        ),
        "targets": TARGETS,
        "crates": entries,
    }, sys.stdout, indent=2)
    print()

    print(f"crates: {len(entries)} third-party, "
          f"first-party skipped: {sorted(first_party)}", file=sys.stderr)


if __name__ == "__main__":
    main()
