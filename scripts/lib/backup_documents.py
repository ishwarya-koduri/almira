#!/usr/bin/env python3
"""
Where a backup's documents are, decided once for every script that takes,
restores or drills a backup (docs/17 §6 "Documents in object storage").

Owner's decision, 2026-09-15: with documents in S3 a backup neither refuses nor
warns — it requires an explicit acknowledgement. So:

  backup   ALMIRA_STORAGE_PROVIDER in the deployment's env file is `s3`
           → refused unless ALMIRA_BACKUP_DOCUMENTS=external is set in the
             environment of the command. With it, the backup says in its output
             and in manifest.json that the documents are NOT in it, and names
             the bucket (never a credential).
           `filesystem` (or unset) → as before; ALMIRA_BACKUP_DOCUMENTS=external
             with it is a contradiction and refused.

  restore  the manifest's `documents.in_this_backup` says whether the backup
           holds them. A manifest from before this field is treated per the
           target env file's provider. Documents that are external need the
           same acknowledgement; an acknowledgement for a backup that holds its
           documents is refused as a contradiction; and a backup whose
           documents are in a bucket is refused for a target that does not use
           object storage, where every document would be missing.

Every decision here only reads files and the environment, so the scripts call
it before they write anything (docs/known-issues.md, "A guard runs before the
action it guards").

    backup_documents.py backup  ENV_FILE             → shell assignments, or exit 1 with the reason
    backup_documents.py restore MANIFEST ENV_FILE    → the same
    backup_documents.py drill MANIFEST [ENV_FILE]    → the same, for a drill that restores no documents
                                                       itself (no target check; a manifest without
                                                       the field and no env file: documents included)
    backup_documents.py manifest-block ENV_FILE      → the manifest's `documents` object, as JSON
"""
import json
import os
import shlex
import sys
import urllib.parse

ACK_NAME = "ALMIRA_BACKUP_DOCUMENTS"
ACK_VALUE = "external"
PROVIDERS = ("filesystem", "s3")


class Refused(Exception):
    pass


def read_env_file(path):
    """The NAME=value lines of a compose env file; the last one of a name wins, as in compose."""
    values = {}
    with open(path, encoding="utf-8") as f:
        for raw in f:
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            if line.startswith("export "):
                line = line[len("export "):].lstrip()
            name, value = line.split("=", 1)
            name, value = name.strip(), value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
                value = value[1:-1]
            elif " #" in value:
                value = value.split(" #", 1)[0].rstrip()
            values[name] = value
    return values


def provider_of(env):
    """The compose file's default is filesystem, so unset and empty mean filesystem."""
    provider = env.get("ALMIRA_STORAGE_PROVIDER", "").strip().lower() or "filesystem"
    if provider not in PROVIDERS:
        raise Refused(f"ALMIRA_STORAGE_PROVIDER is '{provider}', which is neither filesystem nor s3. "
                      "The application refuses to start with it; this refuses to guess where its documents are.")
    return provider


def acknowledgement():
    """None when unset; the acknowledgement when it is exactly `external`; refused otherwise."""
    value = os.environ.get(ACK_NAME)
    if value is None or value == "":
        return None
    if value.strip().lower() != ACK_VALUE:
        raise Refused(f"{ACK_NAME} is '{value}'. The only value it takes is '{ACK_VALUE}', "
                      "meaning: I know the documents are not in this backup.")
    return ACK_VALUE


def safe_endpoint(endpoint):
    """Scheme, host and port only: an endpoint URL can carry a user, a password or a signed query."""
    if not endpoint:
        return ""
    parts = urllib.parse.urlsplit(endpoint if "://" in endpoint else "https://" + endpoint)
    host = parts.hostname or ""
    port = f":{parts.port}" if parts.port else ""
    return f"{parts.scheme}://{host}{port}" if host else ""


def where(env):
    bucket = env.get("ALMIRA_S3_BUCKET", "").strip()
    prefix = env.get("ALMIRA_S3_PREFIX", "").strip() or "documents/"
    return {
        "bucket": bucket,
        "prefix": prefix,
        "region": env.get("ALMIRA_S3_REGION", "").strip(),
        "endpoint": safe_endpoint(env.get("ALMIRA_S3_ENDPOINT", "").strip()),
    }


def describe(loc):
    at = f"S3 bucket '{loc['bucket'] or '(ALMIRA_S3_BUCKET is not set)'}' under prefix '{loc['prefix']}'"
    if loc["region"]:
        at += f", region {loc['region']}"
    if loc["endpoint"]:
        at += f", endpoint {loc['endpoint']}"
    return at


def backup_decision(env_file):
    env = read_env_file(env_file)
    provider = provider_of(env)
    ack = acknowledgement()
    if provider == "s3":
        loc = where(env)
        if ack is None:
            raise Refused(
                f"Refusing to back up: this deployment keeps its documents in object storage "
                f"(ALMIRA_STORAGE_PROVIDER=s3 in {env_file}, {describe(loc)}), and this backup cannot "
                f"contain them. Set {ACK_NAME}={ACK_VALUE} to take a backup of everything else, knowing the "
                "documents must come back from the bucket itself (its versioning or replication). "
                "Nothing has been written.")
        return {"DOCS_EXTERNAL": "1", "DOCS_PROVIDER": provider, "DOCS_WHERE": describe(loc)}
    if ack is not None:
        raise Refused(
            f"Refusing to back up: {ACK_NAME}={ACK_VALUE} says the documents are outside this backup, but "
            f"{env_file} keeps them on the filesystem (ALMIRA_STORAGE_PROVIDER is "
            f"'{env.get('ALMIRA_STORAGE_PROVIDER', '') or 'unset'}'), so they are in it. Unset "
            f"{ACK_NAME}, or fix the env file. Nothing has been written.")
    return {"DOCS_EXTERNAL": "0", "DOCS_PROVIDER": provider, "DOCS_WHERE": "documents.tgz in this backup"}


def manifest_block(env_file):
    env = read_env_file(env_file)
    provider = provider_of(env)
    if provider == "s3":
        loc = where(env)
        return {
            "in_this_backup": False,
            "provider": "s3",
            **loc,
            "acknowledged_with": f"{ACK_NAME}={ACK_VALUE}",
            "note": f"Documents are NOT in this backup. They live in {describe(loc)}. Restoring this backup "
                    "brings back the document rows only; the bytes must come from that bucket.",
        }
    return {"in_this_backup": True, "provider": "filesystem", "file": "documents.tgz"}


def restore_decision(manifest_path, env_file, check_target=True):
    with open(manifest_path, encoding="utf-8") as f:
        manifest = json.load(f)
    env = read_env_file(env_file) if env_file else {}
    target = provider_of(env) if env_file else None
    ack = acknowledgement()
    block = manifest.get("documents")
    if isinstance(block, dict) and "in_this_backup" in block:
        external = block["in_this_backup"] is False
        if external:
            at = describe({k: block.get(k, "") for k in ("bucket", "prefix", "region", "endpoint")})
            said = f"the manifest says the documents are NOT in this backup; they live in {at}"
        else:
            said = "the manifest says the documents are in this backup (documents.tgz)"
    else:
        # A manifest from before the field: per the target's provider (a drill
        # with no env file restores no documents, so it has none to be wrong about).
        external = target == "s3"
        at = describe(where(env)) if external else ""
        said = ("the manifest does not say where the documents are, and the target keeps them in object storage "
                f"({at}), so documents.tgz is not what it will read" if external
                else "the manifest does not say where the documents are, and the target keeps them on the "
                     "filesystem, so they are restored from documents.tgz")
    if external and ack is None:
        raise Refused(f"Refusing to restore: {said}. Set {ACK_NAME}={ACK_VALUE} to restore everything else, "
                      "knowing every document must already be in that bucket. Nothing has been written.")
    if not external and ack is not None:
        raise Refused(f"Refusing to restore: {ACK_NAME}={ACK_VALUE} is set, but {said}. Unset {ACK_NAME}. "
                      "Nothing has been written.")
    if check_target and external and target != "s3":
        raise Refused(f"Refusing to restore: {said}, but {env_file} keeps documents on the filesystem, where "
                      "none of them are: every document would be missing. Point the target at the bucket "
                      "(ALMIRA_STORAGE_PROVIDER=s3 and its ALMIRA_S3_* settings). Nothing has been written.")
    return {"DOCS_EXTERNAL": "1" if external else "0", "DOCS_PROVIDER": target or "", "DOCS_WHERE": said}


def main(argv):
    try:
        if len(argv) == 3 and argv[1] == "backup":
            out = backup_decision(argv[2])
        elif len(argv) == 4 and argv[1] == "restore":
            out = restore_decision(argv[2], argv[3])
        elif len(argv) in (3, 4) and argv[1] == "drill":
            out = restore_decision(argv[2], argv[3] if len(argv) == 4 else None, check_target=False)
        elif len(argv) == 3 and argv[1] == "manifest-block":
            print(json.dumps(manifest_block(argv[2])))
            return 0
        else:
            print("\n".join(__doc__.strip().splitlines()[-3:]), file=sys.stderr)
            return 2
    except Refused as e:
        print(str(e), file=sys.stderr)
        return 1
    for k, v in out.items():
        print(f"{k}={shlex.quote(v)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
