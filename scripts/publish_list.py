"""Registry-driven publish filter for the CloudStream plugins.json list.

SalooRepo v2 publish policy (see docs/automation.md):

  1. INACTIVE sites are NEVER published: their plugin entries are dropped
     from plugins.json even when the .cs3 file still exists on the builds
     branch (the stale file stays reachable by direct URL, but the list
     CloudStream reads no longer offers it).
  2. Records whose status is in PUBLISH_STATUSES are published. The default
     is "active,degraded" (degraded = reachable but the probe answered with
     an HTTP error; the app usually still works). Tighten to active-only
     with --statuses active.
  3. enabled: false records are never published (manual off-switch).
  4. Entry -> record mapping uses the record's optional "internalNames"
     list plus the automatic siteId -> CamelCase convention used by the
     scaffolder ("internet-archive" -> "InternetArchive"). Entries with NO
     mapping pass through untouched: manual modules without a registry
     record are not affected by the policy.
  5. When several eligible records would publish plugins that share one
     titleSignature (mirror sites), only the healthiest / most recently
     checked record publishes: active > degraded, then newest lastChecked.
  6. Content-type policy: records whose contentTypes declaration (or name /
     url / page title) violates the SalooRepo allowlist (movie, series,
     anime, cartoon, documentary) are never published (see site_registry).

CLI:
  python3 scripts/publish_list.py build/plugins.json providers.json \
      --out build/plugins.json [--statuses active,degraded]
  python3 scripts/publish_list.py plugins.json providers.json --check

  --out   write the filtered list here ("-" = stdout; omitted -> stdout)
  --check exit 1 when any entry violates the policy (CI enforcement)
"""

from __future__ import annotations

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import site_registry as reg  # noqa: E402

DEFAULT_STATUSES = ("active", "degraded")
_STATUS_RANK = {"active": 0, "degraded": 1, "inactive": 2}


def plugin_camel(site_id):
    """siteId -> scaffolded class name ("internet-archive" -> "InternetArchive")."""
    return "".join(part.capitalize() for part in str(site_id or "").split("-"))


def build_internal_name_mapping(registry):
    """Map internalName -> siteId (explicit "internalNames" win, camel fallback)."""
    mapping = {}
    for provider in reg.providers(registry):
        site_id = provider.get("siteId")
        explicit = provider.get("internalNames")
        names = [str(name) for name in explicit] if explicit else [plugin_camel(site_id)]
        for name in names:
            if name and name not in mapping:
                mapping[name] = site_id
    return mapping


def filter_plugins(entries, registry, publish_statuses=DEFAULT_STATUSES):
    """Apply the publish policy. Returns (kept_entries, decisions).

    decisions is a list of (index, entry, action, reason) covering every
    input entry, with action in {"keep", "drop"}.
    """
    statuses = set(publish_statuses)
    mapping = build_internal_name_mapping(registry)

    site_of_entry = []
    for entry in entries:
        name = entry.get("internalName") or entry.get("name")
        site_of_entry.append(mapping.get(str(name)) if name else None)

    eligible = {}
    blocked = {}  # index -> drop reason (direct policy violations)
    for index, entry in enumerate(entries):
        site_id = site_of_entry[index]
        if not site_id:
            continue  # no mapping -> keep (manual module)
        record = reg.get_provider(registry, site_id)
        if record is None:
            continue  # dangling mapping -> keep (never silently hide plugins)
        if record.get("enabled") is False:
            blocked[index] = f"{site_id}: elle devre disi (enabled=false)"
        elif record.get("status") == "inactive":
            blocked[index] = f"{site_id}: INACTIVE -> listede yayinlanmaz"
        elif record.get("status") not in statuses:
            blocked[index] = (
                f"{site_id}: status {record.get('status')!r} publish listesi disinda"
            )
        else:
            violation = reg.content_policy_violation(record)
            if violation:
                # content-type policy: never publish (and never let such a
                # record win a mirror group, since it is not eligible)
                blocked[index] = f"{site_id}: icerik-tipi politikasi ({violation})"
            else:
                eligible[site_id] = record

    # mirror dedupe: records sharing one titleSignature publish only once
    mirror_winner = {}
    groups = {}
    for site_id, record in eligible.items():
        key = reg.title_key(record.get("titleSignature"))
        if key:
            groups.setdefault(key, []).append(site_id)
    for key, site_ids in groups.items():
        if len(site_ids) < 2:
            continue
        # newest lastChecked first (stable), then active before degraded
        ordered = sorted(
            site_ids,
            key=lambda s: eligible[s].get("lastChecked") or "",
            reverse=True,
        )
        ordered = sorted(
            ordered, key=lambda s: _STATUS_RANK.get(eligible[s].get("status"), 3)
        )
        winner, rest = ordered[0], ordered[1:]
        for site_id in rest:
            mirror_winner[site_id] = winner

    kept, decisions = [], []
    for index, entry in enumerate(entries):
        site_id = site_of_entry[index]
        if index in blocked:
            decisions.append((index, entry, "drop", blocked[index]))
        elif site_id in mirror_winner:
            decisions.append((
                index, entry, "drop",
                f"{site_id}: ayna kayit; {mirror_winner[site_id]} yayinda",
            ))
        else:
            kept.append(entry)
            if not site_id:
                reason = "registry eslemesi yok (manuel modul)"
            elif site_id in eligible:
                reason = f"{site_id}: {eligible[site_id].get('status')}"
            else:
                reason = f"registry kaydi yok: {site_id}"
            decisions.append((index, entry, "keep", reason))
    return kept, decisions


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Registry-driven plugins.json publish filter"
    )
    parser.add_argument("plugins_path", help="plugins.json to filter")
    parser.add_argument("registry_path", help="providers.json registry document")
    parser.add_argument(
        "--out", default=None,
        help="write the filtered list here ('-' = stdout; default: stdout)",
    )
    parser.add_argument(
        "--statuses", default=",".join(DEFAULT_STATUSES),
        help="registry statuses that may be published (default: active,degraded)",
    )
    parser.add_argument(
        "--check", action="store_true",
        help="exit 1 when any entry would be dropped (CI enforcement)",
    )
    args = parser.parse_args(argv)

    with open(args.plugins_path, encoding="utf-8") as handle:
        entries = json.load(handle)
    if not isinstance(entries, list):
        print("publish_list: plugins file must be a JSON array", file=sys.stderr)
        return 2
    registry = reg.load_registry(args.registry_path)

    statuses = [s.strip() for s in args.statuses.split(",") if s.strip()]
    kept, decisions = filter_plugins(entries, registry, statuses)

    for index, entry, action, reason in decisions:
        label = entry.get("internalName") or entry.get("name") or f"plugins[{index}]"
        print(f"[{action}] plugins[{index}] {label}: {reason}")

    dropped_count = sum(1 for _, _, action, _ in decisions if action == "drop")
    if args.check:
        if dropped_count:
            print(f"publish check FAILED: {dropped_count} entry(ies) violate the policy")
            return 1
        print("publish check OK: every entry satisfies the publish policy")
        return 0

    payload = json.dumps(kept, indent=2, ensure_ascii=False) + "\n"
    if args.out and args.out != "-":
        parent = os.path.dirname(os.path.abspath(args.out))
        os.makedirs(parent, exist_ok=True)
        with open(args.out, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(payload)
        print(f"publish_list: {len(entries)} -> {len(kept)} entries (written: {args.out})")
    else:
        sys.stdout.write(payload)
    return 0


if __name__ == "__main__":
    sys.exit(main())
