#!/usr/bin/env python3
"""Inventory Git publication boundaries; findings contain locations, not secrets.

This complements a dedicated secret scanner. It does not certify security or
license compliance and deliberately does not modify refs, files, or history.
"""

import argparse
import collections
import datetime
import hashlib
import json
import pathlib
import re
import subprocess


def git(*args):
    return subprocess.check_output(["git", *args])


def object_set(*args):
    return {line.split(b" ", 1)[0].decode() for line in git("rev-list", "--objects", *args).splitlines()}


PATTERNS = {
    "private-key-marker": rb"-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----",
    "credential-url": rb"https?://[^\s/<>\"']+:[^\s/@<>\"']+@",
    "home-directory": rb"/(?:Users|home)/[A-Za-z0-9_.-]+/",
    "private-ip-address": rb"\b(?:192\.168\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})\b",
    "email-address": rb"[A-Za-z0-9_.+%-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}",
    "known-token-prefix": rb"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|sk-(?:proj-|ant-)?[A-Za-z0-9_-]{32,}|AKIA[A-Z0-9]{16})\b",
}
SENSITIVE_PATH = re.compile(r"(?:^|/)(?:\.env(?:\..*)?|id_(?:rsa|ed25519)|credentials|store-password|local\.properties|[^/]+\.(?:jks|keystore|p12|pfx|pem|pcap|pcapng|pdf))$", re.I)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=pathlib.Path, required=True)
    args = parser.parse_args()
    root = pathlib.Path(git("rev-parse", "--show-toplevel").decode().strip())
    if pathlib.Path.cwd().resolve() != root:
        parser.error("Run this from the repository root")
    report_path = args.report.resolve()
    private_root = root / "build" / "public-release-audit"
    if not report_path.is_relative_to(private_root):
        parser.error("Reports must stay under ignored build/public-release-audit/")
    publication = object_set("--branches", "--tags", "--remotes")
    refs = object_set("--all")
    reflog = object_set("--all", "--reflog")
    head = object_set("HEAD")
    current = {}
    paths = collections.defaultdict(set)
    for entry in git("ls-tree", "-rz", "--full-tree", "HEAD").split(b"\0"):
        if not entry:
            continue
        attributes, name = entry.split(b"\t", 1)
        mode, kind, oid = attributes.decode().split()
        current[name.decode(errors="replace")] = {"mode": mode, "kind": kind, "oid": oid}
    # Walk each historical tree, not only rev-list's one suggested name per blob.
    roots = set(git("rev-list", "--all", "--reflog").decode().splitlines())
    for line in git("for-each-ref", "--format=%(objecttype) %(objectname)").decode().splitlines():
        kind, oid = line.split()
        if kind == "tree":
            roots.add(oid)
    for tree in sorted(roots):
        for entry in git("ls-tree", "-rz", "--full-tree", tree).split(b"\0"):
            if not entry:
                continue
            attributes, name = entry.split(b"\t", 1)
            _, kind, oid = attributes.decode().split()
            if kind == "blob":
                paths[oid].add(name.decode(errors="replace"))
    stats = {}
    for line in git("cat-file", "--batch-all-objects", "--batch-check=%(objectname) %(objecttype) %(objectsize)").decode().splitlines():
        oid, kind, size = line.split()
        stats[oid] = {"kind": kind, "size": int(size)}

    def scope(oid):
        if oid in publication:
            return "branches-tags-remotes"
        if oid in refs:
            return "local-ref-only"
        if oid in reflog:
            return "reflog-only"
        return "unreachable-local-object"

    current_oids = {entry["oid"] for entry in current.values()}
    findings, skipped, oversized, suspicious = [], [], [], []
    scanned = 0
    patterns = {name: re.compile(value) for name, value in PATTERNS.items()}
    batch = subprocess.Popen(["git", "cat-file", "--batch"], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    try:
        for oid, stat in sorted(stats.items()):
            if stat["kind"] != "blob":
                continue
            names = sorted(paths[oid])
            metadata = {"oid": oid, "scope": scope(oid), "in_head": oid in current_oids, "paths": names, "bytes": stat["size"]}
            if stat["size"] > 50 * 1024 * 1024:
                oversized.append(metadata)
            if any(SENSITIVE_PATH.search(name) for name in names):
                suspicious.append(metadata)
            if stat["size"] > 4 * 1024 * 1024:
                skipped.append({**metadata, "reason": "over-4-MiB-content-limit"})
                continue
            batch.stdin.write((oid + "\n").encode())
            batch.stdin.flush()
            header = batch.stdout.readline().decode().split()
            if len(header) != 3 or header[0] != oid or int(header[2]) != stat["size"]:
                raise RuntimeError("Unexpected git cat-file response")
            content = batch.stdout.read(stat["size"])
            if len(content) != stat["size"] or batch.stdout.read(1) != b"\n":
                raise RuntimeError("Truncated git cat-file response")
            if b"\0" in content[:8192]:
                skipped.append({**metadata, "reason": "binary-content; inspect-format-separately"})
                continue
            scanned += 1
            for rule, regex in patterns.items():
                lines = sorted({content.count(b"\n", 0, match.start()) + 1 for match in regex.finditer(content)})
                if lines:
                    findings.append({**metadata, "rule": rule, "lines": lines})
    finally:
        batch.stdin.close()
        batch.stdout.close()
        if batch.wait() != 0:
            raise RuntimeError("git cat-file failed")
    identities = collections.Counter()
    for line in git("log", "--all", "--reflog", "--format=%aN%x00%aE%x00%cN%x00%cE").splitlines():
        parts = line.split(b"\0")
        if len(parts) == 4:
            for email in (parts[1], parts[3]):
                identities[(hashlib.sha256(email).hexdigest()[:16], b"noreply" in email)] += 1
    ignored = git("ls-files", "-z", "--others", "--ignored", "--exclude-standard").split(b"\0")
    report = {
        "generated_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "revision": git("rev-parse", "HEAD").decode().strip(),
        "tracked_files": len(current),
        "all_local_object_counts": dict(collections.Counter(stat["kind"] for stat in stats.values())),
        "object_counts_by_scope": dict(collections.Counter(scope(oid) for oid in stats)),
        "head_history_object_count": len(head),
        "historical_trees_inspected": len(roots),
        "text_blobs_inspected": scanned,
        "current_tree": current,
        "metadata_findings": findings,
        "large_blobs": oversized,
        "sensitive_path_inventory": suspicious,
        "unscanned_binary_or_large_blobs": skipped,
        "commit_emails": [{"sha256_prefix": fingerprint, "noreply": noreply, "occurrences": count} for (fingerprint, noreply), count in identities.items()],
        "ignored_files_count": sum(bool(path) for path in ignored),
        "ignored_sensitive_paths": [path.decode(errors="replace") for path in ignored if path and SENSITIVE_PATH.search(path.decode(errors="replace"))],
        "limitations": ["Not a replacement for Gitleaks or credential rotation", "Binary and large blob contents need format-specific inspection", "Unreachable objects may have no recoverable filename", "Remote-only refs, caches, issues, Actions and releases require separate GitHub inspection"],
    }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    with report_path.open("x", encoding="utf-8") as output:
        json.dump(report, output, indent=2)
        output.write("\n")
    report_path.chmod(0o600)
    print(json.dumps({key: report[key] for key in ("revision", "tracked_files", "all_local_object_counts", "object_counts_by_scope", "historical_trees_inspected", "text_blobs_inspected", "ignored_files_count")}))
    print(f"Redacted location-only report: {args.report}")


if __name__ == "__main__":
    main()
