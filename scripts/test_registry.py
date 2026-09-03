"""Offline tests for the SalooRepo site-registry (scripts/site_registry.py).

Run anywhere:  python3 scripts/test_registry.py   (exit code 0 = all passed)

No network access is required: every HTTP interaction is simulated with an
injectable fetch function, so TEST 1-6 run fully offline and deterministic.

TEST 1: same domain submitted twice   -> second record is rejected
TEST 2: same site with a new domain   -> duplicate / domain-change detected
TEST 3: dead site                     -> INACTIVE (kept in registry)
TEST 4: working site                  -> ACTIVE (probe error -> DEGRADED)
TEST 5: existing site info updated    -> still one record (1 -> 1)
TEST 6: genuinely new site            -> new registry record (0 -> 1)
EXTRA : normalisation, schema validation, save/load, transient recovery,
        lastWorkingDomain fallback.
"""

import copy
import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import site_registry as reg  # noqa: E402
import publish_list as pub  # noqa: E402

RESULTS = []


def run_test(label, description, fn):
    try:
        fn()
        RESULTS.append((label, True))
        print(f"[PASS] {label}: {description}")
    except AssertionError as exc:
        RESULTS.append((label, False))
        print(f"[FAIL] {label}: {description} -> {exc}")
    except Exception as exc:  # unexpected crash counts as a failure
        RESULTS.append((label, False))
        print(f"[FAIL] {label}: {description} -> {type(exc).__name__}: {exc}")


def base_registry():
    """One tracked site: site-a (https://site-a.com)."""
    return {
        "version": 2,
        "providers": [
            {
                "siteId": "site-a",
                "name": "Site A",
                "url": "https://site-a.com",
                "canonicalDomain": "site-a.com",
                "lastWorkingDomain": "site-a.com",
                "aliases": [],
                "status": "active",
                "category": "community",
                "enabled": True,
                "probePath": "/",
                "titleSignature": "Site A - online izle",
                "lastChecked": None,
                "lastError": None,
            }
        ],
    }


def fake_fetch(html="", status=200, final=None):
    def _fetch(url, *args, **kwargs):
        return (final or url, status, html)
    return _fetch


def failing_fetch(url, *args, **kwargs):
    raise OSError("connection refused")


def flaky_fetch(html="", fail_times=1):
    """Fails the first N calls, then succeeds (transient error simulation)."""
    state = {"calls": 0}

    def _fetch(url, *args, **kwargs):
        state["calls"] += 1
        if state["calls"] <= fail_times:
            raise OSError("transient network error")
        return (url, 200, html)
    return _fetch


def test_1_duplicate_domain_rejected():
    data = base_registry()
    assert reg.duplicate_exists(data, domain="site-a.com")
    assert reg.duplicate_exists(data, domain="https://www.site-a.com/dizi/")
    raised = False
    try:
        reg.add_provider(data, "Site A Kopya", "https://site-a.com")
    except reg.DuplicateSiteError:
        raised = True
    assert raised, "same domain accepted a second provider"
    # a different display name with the SAME domain must also be rejected
    raised2 = False
    try:
        reg.add_provider(data, "Bambaska Bir Isim", "https://www.site-a.com/")
    except reg.DuplicateSiteError:
        raised2 = True
    assert raised2, "same domain with a different name was accepted"
    assert len(data["providers"]) == 1, "registry grew on a duplicate submission"


def test_2_domain_move_is_change_not_duplicate():
    data = base_registry()
    provider = data["providers"][0]
    # (a) unconfirmed moves are refused -> issue path, registry untouched
    refused = False
    try:
        reg.update_domain(data, "site-a", "https://site-a.net", confirmed=False)
    except reg.UnconfirmedDomainChangeError:
        refused = True
    assert refused, "unconfirmed domain move was accepted"
    assert provider["url"] == "https://site-a.com" and len(data["providers"]) == 1
    # (b) confirmed move updates the SAME record (1 -> 1) and keeps the old domain
    reg.update_domain(data, "site-a", "site-a.net", confirmed=True)
    assert len(data["providers"]) == 1, "domain move created a duplicate provider"
    assert provider["url"] == "https://site-a.net"
    assert provider["canonicalDomain"] == "site-a.net"
    assert "site-a.com" in provider["aliases"], "old domain was lost"
    # (c) old + new domains all resolve to the SAME site
    for domain in ("site-a.com", "www.site-a.net", "https://m.site-a.net/dizi"):
        owner, match = reg.find_provider_by_domain(data, domain)
        assert owner is provider, f"{domain} did not resolve to the same site ({match})"
    # (d) confirmation heuristic: matching title confirms, foreign title refuses
    ok, _ = reg.confirm_same_site(
        provider, "https://site-a.net",
        fetch_fn=fake_fetch("<title>Site A - online izle</title>"),
    )
    assert ok, "matching title signature did not confirm the same site"
    bad, _ = reg.confirm_same_site(
        provider, "https://baska-site.org",
        fetch_fn=fake_fetch("<title>Totally Different Site</title>"),
    )
    assert not bad, "a different site was confirmed as the same site"

def test_3_dead_site_inactive():
    data = base_registry()
    provider = data["providers"][0]
    result = reg.check_site(provider, retries=1, fetch_fn=failing_fetch)
    assert result["status"] == "inactive", result
    reg.apply_check_result(provider, result)
    assert len(data["providers"]) == 1, "dead site was removed from the registry"
    assert provider["status"] == "inactive"
    assert provider["lastError"], "no error recorded"
    # a transient error followed by success brings the site back (rule 6)
    recover = reg.check_site(
        provider, retries=1,
        fetch_fn=flaky_fetch("<html><title>Site A</title></html>", fail_times=1),
    )
    assert recover["status"] == "active", "transient failure was treated as permanent"
    reg.apply_check_result(provider, recover)
    assert provider["status"] == "active"


def test_4_working_site_active():
    data = base_registry()
    provider = data["providers"][0]
    result = reg.check_site(provider, fetch_fn=fake_fetch("<html><title>Site A</title></html>", 200))
    assert result["status"] == "active", result
    reg.apply_check_result(provider, result)
    assert provider["status"] == "active"
    assert provider["lastError"] is None
    # reachable but the probe answers with an HTTP error -> DEGRADED
    degraded = reg.check_site(provider, fetch_fn=fake_fetch("<html><title>x</title></html>", 403))
    assert degraded["status"] == "degraded"
    reg.apply_check_result(provider, degraded)
    assert provider["status"] == "degraded" and len(data["providers"]) == 1


def test_5_update_existing_no_new_record():
    data = base_registry()
    before = len(data["providers"])
    reg.update_provider(data, "site-a", name="Site A Yeni Ad", probePath="/yeni-probe")
    reg.update_provider(data, "site-a", lastError=None)
    provider = reg.get_provider(data, "site-a")
    assert len(data["providers"]) == before == 1, "update created a second record"
    assert provider["name"] == "Site A Yeni Ad"
    assert provider["probePath"] == "/yeni-probe"
    # the add path also refuses to grow the registry for a known site
    raised = False
    try:
        reg.add_provider(data, "Site A", "https://site-a.com")
    except reg.DuplicateSiteError:
        raised = True
    assert raised, "known site was added again"
    assert len(data["providers"]) == 1


def test_6_new_site_added():
    data = {"version": 2, "providers": []}
    entry = reg.add_provider(data, "Yeni Site", "https://yeni-site.org", category="community")
    assert len(data["providers"]) == 1, "0 -> 1 failed"
    assert entry["siteId"] == "yeni-site"
    assert entry["canonicalDomain"] == "yeni-site.org"
    second = reg.add_provider(data, "Diger Site", "https://baska-site.net")
    assert len(data["providers"]) == 2, "N -> N+1 failed"
    assert second["siteId"] != entry["siteId"]
    problems = reg.validate_registry(data)
    assert not problems, problems
    # a subdomain of an existing site is NOT a new site (rule 5)
    owner, match = reg.find_provider_by_domain(data, "https://cdn.yeni-site.org/v2")
    assert owner is entry, f"subdomain did not resolve to its site ({match})"

def extra_normalization_and_validation():
    assert reg.normalize_domain("HTTPS://WWW.Site-A.com/dizi/") == "site-a.com"
    assert reg.registered_domain("a.b.site-a.co.uk") == "site-a.co.uk"
    assert reg.registered_domain("m.site-a.com.tr") == "site-a.com.tr"
    assert reg.slugify("Site A!") == "site-a"
    assert reg.compute_status(False, None) == "inactive"
    assert reg.compute_status(True, True) == "active"
    assert reg.compute_status(True, False) == "degraded"
    data = base_registry()
    assert reg.validate_registry(data) == []
    broken = base_registry()
    broken["providers"][0]["status"] = "dead"
    assert any("status" in p for p in reg.validate_registry(broken))
    broken2 = base_registry()
    broken2["providers"].append(copy.deepcopy(broken2["providers"][0]))
    assert any("siteId" in p for p in reg.validate_registry(broken2))


def extra_save_load_roundtrip():
    data = base_registry()
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "providers.json")
        reg.save_registry(path, data)
        loaded = reg.load_registry(path)
        assert loaded["providers"][0]["siteId"] == "site-a"
        assert reg.validate_registry(loaded) == []
        with open(path, encoding="utf-8") as handle:
            json.loads(handle.read())  # still valid JSON on disk


def extra_last_working_domain_fallback():
    # canonical domain dead, but lastWorkingDomain answers -> ACTIVE (rule 2)
    data = {
        "version": 2,
        "providers": [
            {
                "siteId": "site-a",
                "name": "Site A",
                "url": "https://site-a.com",
                "canonicalDomain": "site-a.com",
                "lastWorkingDomain": "site-a.net",
                "aliases": ["site-a.net"],
                "status": "inactive",
                "category": "community",
                "enabled": True,
                "probePath": "/",
                "titleSignature": None,
                "lastChecked": None,
                "lastError": "old failure",
            }
        ],
    }
    provider = data["providers"][0]
    dead = reg.check_site(provider, retries=0, fetch_fn=failing_fetch)
    assert dead["status"] == "inactive"
    # custom fetcher: fails on site-a.com, succeeds on site-a.net
    def selective_fetch(url, *args, **kwargs):
        if "site-a.com" in url:
            raise OSError("canonical down")
        return (url, 200, "<html><title>Site A</title></html>")
    result = reg.check_site(provider, retries=0, fetch_fn=selective_fetch)
    assert result["status"] == "active", result
    assert result["final_domain"] == "site-a.net"


def _publish_record(site_id, status="active", enabled=True,
                    title=None, checked=None):
    """Minimal registry record for publish-filter tests."""
    return {
        "siteId": site_id,
        "name": site_id,
        "url": f"https://{site_id}.example",
        "canonicalDomain": f"{site_id}.example",
        "lastWorkingDomain": f"{site_id}.example",
        "aliases": [],
        "status": status,
        "category": "community",
        "enabled": enabled,
        "probePath": "/",
        "titleSignature": title,
        "lastChecked": checked,
        "lastError": None,
    }


def test_7_cross_record_domain_overlap_rejected():
    """Manual edits cannot give one domain to two records (CI-level guard)."""
    data = base_registry()
    clone = copy.deepcopy(data["providers"][0])
    clone["siteId"] = "site-a-clone"
    clone["url"] = "https://site-a-clone.org"
    clone["canonicalDomain"] = "site-a-clone.org"
    clone["lastWorkingDomain"] = "site-a-clone.org"
    clone["aliases"] = ["site-a.com"]  # domain stolen from site-a
    data["providers"].append(clone)
    problems = reg.validate_registry(data)
    assert any("site-a.com" in problem for problem in problems), problems
    # the API layer refuses the stolen domain for a second record, too
    raised = False
    try:
        reg.add_alias(data, "site-a-clone", "https://www.site-a.com/")
    except reg.DuplicateSiteError:
        raised = True
    assert raised, "a domain tracked by site-a was attached to site-a-clone"


def test_8_mirror_detection_and_alias_attach():
    """Same content on a DIFFERENT registered domain -> mirror, not new site."""
    data = base_registry()
    matches = reg.find_provider_by_title(data, "site a   online izle!")  # loose
    assert matches and matches[0]["siteId"] == "site-a", matches
    assert reg.find_provider_by_title(data, "Baska Baska") == []
    before_url = data["providers"][0]["url"]
    reg.add_alias(data, "site-a", "https://mirror1.site-a-ayna.net/")
    provider = data["providers"][0]
    assert provider["url"] == before_url, "alias attach changed the primary url"
    assert len(data["providers"]) == 1, "mirror attach created a second record"
    owner, match = reg.find_provider_by_domain(data, "mirror1.site-a-ayna.net")
    assert owner is provider and match == "alias", (match,)
    assert reg.validate_registry(data) == []
    # a mirror domain owned by ANOTHER record is refused
    other = base_registry()
    reg.add_provider(other, "Baska Site", "https://baska.net")
    raised = False
    try:
        reg.add_alias(other, "site-a", "https://baska.net")
    except reg.DuplicateSiteError:
        raised = True
    assert raised, "add_alias attached another record's domain"


def test_9_publish_filter_status_rules():
    registry = {
        "version": 2,
        "providers": [
            _publish_record("aktif-site", "active", enabled=True),
            _publish_record("pasif-site", "inactive", enabled=True),
            _publish_record("kapali-site", "active", enabled=False),
            _publish_record("kritik-site", "degraded", enabled=True),
        ],
    }
    entries = [
        {"internalName": "AktifSite", "name": "Aktif"},
        {"internalName": "PasifSite", "name": "Pasif"},
        {"internalName": "KapaliSite", "name": "Kapali"},
        {"internalName": "KritikSite", "name": "Kritik"},
        {"internalName": "ManuelModul", "name": "Manuel"},  # no mapping
    ]
    kept, decisions = pub.filter_plugins(entries, registry)
    names = [entry["internalName"] for entry in kept]
    assert names == ["AktifSite", "KritikSite", "ManuelModul"], names
    dropped = {reason for _, _, action, reason in decisions if action == "drop"}
    assert any("INACTIVE" in reason for reason in dropped), dropped
    assert any("enabled=false" in reason for reason in dropped), dropped
    # tightened policy: active-only publishes
    kept2, _ = pub.filter_plugins(entries, registry, publish_statuses=["active"])
    assert [entry["internalName"] for entry in kept2] == ["AktifSite", "ManuelModul"]


def test_10_publish_mirror_dedupe():
    """Same site on several domains -> only the most current/working is listed."""
    registry = {
        "version": 2,
        "providers": [
            _publish_record("ayna-b", "active",
                            title="Ayni Site", checked="2026-09-01T00:00:00Z"),
            _publish_record("ayna-a", "active",
                            title="Ayni  Site !", checked="2026-09-02T00:00:00Z"),
            _publish_record("ayna-c", "degraded",
                            title="AyniSite", checked="2026-09-03T00:00:00Z"),
        ],
    }
    entries = [
        {"internalName": "AynaB", "name": "B"},
        {"internalName": "AynaA", "name": "A"},
        {"internalName": "AynaC", "name": "C"},
    ]
    kept, decisions = pub.filter_plugins(entries, registry)
    assert [entry["internalName"] for entry in kept] == ["AynaA"], kept
    dropped = {reason for _, _, action, reason in decisions if action == "drop"}
    assert len(dropped) == 2 and all("ayna" in reason for reason in dropped), dropped


def test_11_publish_filter_file_roundtrip():
    registry = {
        "version": 2,
        "providers": [
            _publish_record("aktif-site", "active"),
            _publish_record("pasif-site", "inactive"),
        ],
    }
    entries = [
        {"internalName": "AktifSite", "url": "https://x/aktif.cs3"},
        {"internalName": "PasifSite", "url": "https://x/pasif.cs3"},
    ]
    with tempfile.TemporaryDirectory() as tmp:
        reg_path = os.path.join(tmp, "providers.json")
        list_path = os.path.join(tmp, "plugins.json")
        out_path = os.path.join(tmp, "filtered.json")
        reg.save_registry(reg_path, registry)
        with open(list_path, "w", encoding="utf-8") as handle:
            json.dump(entries, handle)
        kept, _ = pub.filter_plugins(entries, reg.load_registry(reg_path))
        assert [entry["internalName"] for entry in kept] == ["AktifSite"]
        with open(out_path, "w", encoding="utf-8") as handle:
            json.dump(kept, handle, indent=2)
        with open(out_path, encoding="utf-8") as handle:
            assert len(json.load(handle)) == 1, "filtered file kept a dropped entry"


def test_12_status_transitions_full_cycle():
    """ACTIVE -> DEGRADED -> INACTIVE -> ACTIVE, record always kept."""
    data = base_registry()
    provider = data["providers"][0]
    degraded = reg.check_site(
        provider, fetch_fn=fake_fetch("<html><title>t</title></html>", 403))
    assert degraded["status"] == "degraded", degraded
    reg.apply_check_result(provider, degraded)
    assert provider["status"] == "degraded"
    dead = reg.check_site(provider, retries=0, fetch_fn=failing_fetch)
    assert dead["status"] == "inactive", dead
    reg.apply_check_result(provider, dead)
    assert provider["status"] == "inactive"
    assert len(data["providers"]) == 1, "INACTIVE record was removed"
    assert provider["lastError"], "no error recorded for INACTIVE"
    back = reg.check_site(
        provider, retries=0,
        fetch_fn=fake_fetch("<html><title>Site A</title></html>", 200))
    assert back["status"] == "active", back
    reg.apply_check_result(provider, back)
    assert provider["status"] == "active" and provider["lastError"] is None
    assert len(data["providers"]) == 1
    # and an INACTIVE record must not leak into the publish list
    registry_inactive = base_registry()
    registry_inactive["providers"][0]["status"] = "inactive"
    kept, _ = pub.filter_plugins(
        [{"internalName": "SiteA", "name": "Site A"}], registry_inactive)
    assert kept == [], "INACTIVE site leaked into the publish list"


def main():
    print("SalooRepo site-registry tests")
    print("=" * 64)
    run_test("TEST 1", "Ayni domain iki kere -> ikinci kayit reddedilir",
             test_1_duplicate_domain_rejected)
    run_test("TEST 2", "Ayni site farkli domain -> duplicate/domain-change algilanir",
             test_2_domain_move_is_change_not_duplicate)
    run_test("TEST 3", "Olu site -> INACTIVE (registry'den silinmez, recovery calisir)",
             test_3_dead_site_inactive)
    run_test("TEST 4", "Calisan site -> ACTIVE (probe hatasi -> DEGRADED)",
             test_4_working_site_active)
    run_test("TEST 5", "Mevcut site guncellenir -> yeni kayit olusmaz (1 -> 1)",
             test_5_update_existing_no_new_record)
    run_test("TEST 6", "Yeni site -> yeni registry kaydi (0 -> 1, N -> N+1)",
             test_6_new_site_added)
    run_test("EXTRA 1", "Normalizasyon + sema dogrulama",
             extra_normalization_and_validation)
    run_test("EXTRA 2", "Save/load roundtrip",
             extra_save_load_roundtrip)
    run_test("EXTRA 3", "lastWorkingDomain fallback (canonical cokerse)",
             extra_last_working_domain_fallback)
    run_test("TEST 7", "Capraz kayit domain cakismasi validate_registry ile yakalanir",
             test_7_cross_record_domain_overlap_rejected)
    run_test("TEST 8", "Ayna tespiti (title) + add_alias birincil url'i bozmaz",
             test_8_mirror_detection_and_alias_attach)
    run_test("TEST 9", "Publish filtresi: INACTIVE/enabled=false dusurulur, manuel gecer",
             test_9_publish_filter_status_rules)
    run_test("TEST 10", "Publish ayna dedup: tek en guncel/saglikli kayit yayinlanir",
             test_10_publish_mirror_dedupe)
    run_test("TEST 11", "Publish filtresi dosya roundtrip (load_registry ile)",
             test_11_publish_filter_file_roundtrip)
    run_test("TEST 12", "ACTIVE->DEGRADED->INACTIVE->ACTIVE dongusu + listede yok",
             test_12_status_transitions_full_cycle)
    failed = [label for label, ok in RESULTS if not ok]
    print("=" * 64)
    print(f"{len(RESULTS) - len(failed)}/{len(RESULTS)} tests passed")
    if failed:
        print("FAILED: " + ", ".join(failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())


