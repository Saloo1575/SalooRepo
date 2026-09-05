"""SalooRepo site-registry library.

The registry tracks SITES, not provider names. Every site has exactly one
stable record identified by ``siteId``. Domains (url / canonicalDomain /
lastWorkingDomain / aliases) are attributes of that record: when a site moves
to a new domain the SAME record is updated (registry count stays 1 -> 1) and
the old domain is kept as an alias. Independent sites get independent records
(site-a.com / site-a.net / site-a.org become one provider only after a
confirmed same-site check, never three providers by accident).

Registry schema (version 2)::

    {
      "version": 2,
      "providers": [
        {
          "siteId": "internet-archive",        # stable identity (primary key)
          "name": "Internet Archive",          # display name
          "url": "https://archive.org",        # ACTIVE base url (https)
          "canonicalDomain": "archive.org",    # registered domain of url
          "lastWorkingDomain": "archive.org",  # last domain that answered OK
          "aliases": [],                       # previous / alternate domains
          "status": "active",                  # active | degraded | inactive
          "category": "official",              # official | community | other
          "contentTypes": ["movie"],           # >=1 allowed type; deny categories may coexist
          "enabled": true,                     # manual on/off switch
          "probePath": "/",                    # functional probe path
          "titleSignature": null,              # <title> baseline for same-site checks
          "lastChecked": null,                 # UTC ISO timestamp
          "lastError": null                    # last probe error (null = healthy)
        }
      ]
    }

Consumers: health-check.yml, domain-check.yml, discovery.yml,
validate-repository.yml and scripts/test_registry.py.

Python 3 standard library only (no third-party dependencies).
"""

from __future__ import annotations

import datetime
import json
import re
import urllib.request
from urllib.parse import urlparse

REGISTRY_VERSION = 2
STATUSES = ("active", "degraded", "inactive")
USER_AGENT = "SalooRepo-Registry/2.0"

# ---------------------------------------------------------------------------
# content-type policy: what SalooRepo is willing to track and publish
# ---------------------------------------------------------------------------
# Allowlist -- the content types a provider/site may serve. A site is
# acceptable when it declares at least ONE of these types; anything that
# does not normalize into this tuple contributes no allowed type (the
# acceptance rule lives in content_policy_violation).
ALLOWED_CONTENT_TYPES = ("movie", "series", "anime", "cartoon", "documentary")

# Denied content categories: recognized but NOT allowed on their own. Their
# presence on a site is informational only and never rejects it; a site is
# rejected only when it declares NO allowed content type at all.
DENIED_CONTENT_TYPES = ("live_tv", "camera", "radio", "nsfw", "sports", "betting")

# Common synonyms -> canonical policy label (an allowed type or a denied
# category), checked case-insensitively; most specific alias first for the
# substring fallback.
CONTENT_TYPE_ALIASES = {
    "tv series": "series",
    "documentaries": "documentary",
    "cizgi film": "cartoon",
    "çizgi film": "cartoon",
    "belgesel": "documentary",
    "documentary": "documentary",
    "cartoon": "cartoon",
    "anime": "anime",
    "series": "series",
    "movies": "movie",
    "film": "movie",
    "movie": "movie",
    "dizi": "series",
    "iptv": "live_tv",
    "canlı tv": "live_tv",
    "canli tv": "live_tv",
    "canlitv": "live_tv",
    "live tv": "live_tv",
    "livetv": "live_tv",
    "livestream": "live_tv",
    "live stream": "live_tv",
    "webcam": "camera",
    "kamera": "camera",
    "camera": "camera",
    "radyo": "radio",
    "radio": "radio",
    "18+": "nsfw",
    "+18": "nsfw",
    "nsfw": "nsfw",
    "porn": "nsfw",
    "erotik": "nsfw",
    "yetişkin": "nsfw",
    "yetiskin": "nsfw",
    "spor": "sports",
    "sports": "sports",
    "maç": "sports",
    "bahis": "betting",
    "betting": "betting",
    "iddaa": "betting",
}

# Deny-category keywords (substring match, case-insensitive) used ONLY for
# INFORMATIONAL reporting (denied_content_flags / discovery issue notes):
# live TV, IPTV, cameras, radio, adult content, sports and betting. A match
# NEVER rejects a site under the current policy; it only classifies it.
DENY_CONTENT_KEYWORDS = (
    "iptv",
    "canlı tv", "canli tv", "canlitv",
    "live tv", "livetv", "livestream", "live stream",
    "webcam", "kamera", "camera",
    "radyo", "radio",
    "18+", "+18", "nsfw", "porn", "xxx", "erotik", "yetişkin", "yetiskin",
    "spor", "sports", "maç", "canli mac", "canlı maç",
    "bahis", "betting", "iddaa",
)

_DENY_CONTENT_RE = re.compile(
    "|".join(re.escape(keyword) for keyword in DENY_CONTENT_KEYWORDS),
    re.IGNORECASE,
)

# CloudStream TvType names used by the scaffolder (discovery.yml S9).
# Verified against CloudStream master (library/src/commonMain/.../MainAPI.kt):
# enum entries are Movie/TvSeries/Anime/Cartoon/Documentary -- "Documentaries"
# is only the app's display label, not an enum value.
CLOUDSTREAM_TV_TYPES = {
    "movie": "Movie",
    "series": "TvSeries",
    "anime": "Anime",
    "cartoon": "Cartoon",
    "documentary": "Documentary",
}

# Common multi-part public suffixes for the registered-domain heuristic.
_MULTI_PART_SUFFIXES = {
    "co.uk", "org.uk", "ac.uk", "gov.uk",
    "com.tr", "net.tr", "org.tr", "edu.tr", "gov.tr",
    "com.au", "net.au", "org.au", "co.nz", "co.za",
    "com.br", "com.mx", "com.ar", "com.co", "com.pe", "com.uy",
    "co.jp", "ne.jp", "or.jp", "co.kr", "com.cn", "com.tw",
    "com.hk", "com.sg", "co.in", "com.sa",
}


class RegistryError(Exception):
    """Base class for registry errors."""


class DuplicateSiteError(RegistryError):
    """A site with this siteId or domain is already tracked."""


class NotFoundError(RegistryError):
    """No registry record with this siteId."""


class UnconfirmedDomainChangeError(RegistryError):
    """A domain move could not be confirmed as the same site."""


def utc_now_iso():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def normalize_domain(value):
    """'HTTPS://WWW.Site-A.com/path' -> 'site-a.com' (host only, no www)."""
    if not value:
        return None
    text = str(value).strip().lower()
    if "://" not in text:
        text = "http://" + text
    host = urlparse(text).netloc or text.split("/")[0]
    host = host.split("@")[-1].split(":")[0].strip(".")
    if host.startswith("www."):
        host = host[4:]
    return host or None


def registered_domain(value):
    """Best-effort registered domain: 'a.b.site-a.co.uk' -> 'site-a.co.uk'."""
    host = normalize_domain(value)
    if not host:
        return None
    parts = host.split(".")
    if len(parts) >= 3 and ".".join(parts[-2:]) in _MULTI_PART_SUFFIXES:
        return ".".join(parts[-3:])
    if len(parts) >= 2:
        return ".".join(parts[-2:])
    return host


def slugify(name):
    slug = re.sub(r"[^a-z0-9]+", "-", str(name).lower()).strip("-")
    return slug or "site"


def https_base(url_or_domain):
    host = normalize_domain(url_or_domain)
    return "https://" + host if host else ""


def title_key(title):
    """Normalised <title> key used for loose comparison and grouping."""
    return re.sub(r"[^a-z0-9]+", "", (title or "").lower())


def titles_match(a, b):
    """Loose <title> comparison used for same-site confirmation."""
    left, right = title_key(a), title_key(b)
    return bool(left) and left == right


# --------------------------------------------------------------------------
# content-type policy helpers
# --------------------------------------------------------------------------

def normalize_content_type(value):
    """Map a raw label ("Film", "Canlı TV", "Belgesel") to a policy category.

    Returns an allowlist type (movie/series/anime/cartoon/documentary) or a
    denied category (live_tv/camera/radio/nsfw/sports/betting), or None when
    the value is empty or unknown (unknown contributes no allowed type).
    """
    text = re.sub(r"[^a-zçğıöşü0-9+]+", " ", str(value or "").lower()).strip()
    if not text:
        return None
    if text in CONTENT_TYPE_ALIASES:
        return CONTENT_TYPE_ALIASES[text]
    for alias, canonical in CONTENT_TYPE_ALIASES.items():
        if alias in text:
            return canonical
    return None


def record_content_types(provider, default=ALLOWED_CONTENT_TYPES):
    """Normalized allowlist types declared on a record.

    Records without an explicit ``contentTypes`` field (or with an empty
    list) are treated as potentially serving the whole allowlist: the policy
    only REJECTS records whose declared types normalize to NO allowed type
    at all. Deny categories are informational flags only (see
    denied_content_flags) and never reject a record on their own. This keeps
    older registries (and the offline test fixtures) valid;
    discovery-created records always declare explicit types.
    """
    raw = provider.get("contentTypes")
    if raw is None or raw == []:
        return tuple(default)
    if isinstance(raw, str):
        raw = [raw]
    if not isinstance(raw, (list, tuple)):
        return ()
    types = []
    for value in raw:
        item = normalize_content_type(value)
        if item and item not in types:
            types.append(item)
    return tuple(types)


def content_policy_violation(provider):
    """Content-policy check for a registry record or a discovery candidate.

    A site is acceptable when it declares (or, for legacy untyped records,
    may declare) at least one allowed content type. Denied categories (live
    TV/IPTV, cameras, radio, 18+ content, sports, betting) never reject a
    site on their own -- they only matter when the declaration contains NO
    allowed type at all. Untyped records are permissive (backwards
    compatible). Returns a human-readable rejection reason, or None.
    """
    raw = provider.get("contentTypes")
    if raw is None or raw == []:
        return None  # untyped record: permissive default (backwards compatible)
    if isinstance(raw, str):
        raw = [raw]
    if not isinstance(raw, (list, tuple)):
        return f"contentTypes must be a list, got: {raw!r}"
    normalized = [normalize_content_type(value) for value in raw]
    if any(item in ALLOWED_CONTENT_TYPES for item in normalized):
        return None
    return f"no allowed content type in {raw!r}"


def denied_content_flags(provider):
    """INFORMATIONAL only: deny-category keywords found on the record.

    Reports which denied categories (live TV/IPTV, cameras, radio, adult
    content, sports, betting) appear in the record's name / url / title /
    titleSignature. Flags NEVER reject a site under the current policy --
    they exist so discovery issues and publish logs can describe the site.
    Returns a list of "key: matched-keyword" strings (empty = no flags).
    """
    flags = []
    for key in ("name", "url", "title", "titleSignature"):
        value = provider.get(key)
        if not value:
            continue
        for match in _DENY_CONTENT_RE.findall(str(value)):
            entry = f"{key}: {match}"
            if entry not in flags:
                flags.append(entry)
    return flags


def tv_types_for(content_types):
    """Map normalized policy types to CloudStream TvType names.

    Unknown items are skipped; None/empty input falls back to the whole
    allowlist (same permissive default as record_content_types). The result
    keeps allowlist order so scaffolded modules are deterministic.
    """
    items = content_types or ALLOWED_CONTENT_TYPES
    result = []
    for item in items:
        name = CLOUDSTREAM_TV_TYPES.get(item)
        if name and name not in result:
            result.append(name)
    return result


# --------------------------------------------------------------------------
# registry I/O and lookups
# --------------------------------------------------------------------------

def load_registry(path="providers.json"):
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    if not isinstance(data, dict) or "providers" not in data:
        raise RegistryError(f"{path} is not a valid registry document")
    return data


def save_registry(path, data):
    data["version"] = REGISTRY_VERSION
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, indent=2, ensure_ascii=False)
        handle.write("\n")


def providers(data):
    return data.setdefault("providers", [])


def get_provider(data, site_id):
    for provider in providers(data):
        if provider.get("siteId") == site_id:
            return provider
    return None


def find_provider_by_domain(data, domain):
    """Map a domain/url to a tracked site.

    Returns (provider, match_type) with match_type in
    {"canonical", "last-working", "alias"}, or (None, None) when unknown.
    Sub-domain moves (m.site-a.com vs site-a.com) resolve to the same record
    via the registered-domain comparison; truly different registered domains
    stay independent sites until a confirmed same-site merge happens.
    """
    dom = normalize_domain(domain)
    if not dom:
        return (None, None)
    reg_dom = registered_domain(dom)
    for provider in providers(data):
        if dom == normalize_domain(provider.get("url")) or reg_dom == registered_domain(provider.get("url")):
            return (provider, "canonical")
    for provider in providers(data):
        if dom == normalize_domain(provider.get("lastWorkingDomain")) or reg_dom == registered_domain(provider.get("lastWorkingDomain") or ""):
            return (provider, "last-working")
    for provider in providers(data):
        for alias in provider.get("aliases") or []:
            if dom == normalize_domain(alias) or reg_dom == registered_domain(alias):
                return (provider, "alias")
    return (None, None)


def duplicate_exists(data, site_id=None, domain=None):
    """True when a siteId or a domain is already tracked (any match type)."""
    if site_id and get_provider(data, site_id) is not None:
        return True
    if domain:
        owner, _ = find_provider_by_domain(data, domain)
        return owner is not None
    return False


def all_domains(provider):
    """Every domain owned by a record: url, canonical, lastWorking, aliases.

    Includes the registered-domain form of each entry, so www./m. host
    variants and protocol/path differences all collapse into one key.
    Used by validate_registry to guarantee a domain can never belong to
    two different records at the same time.
    """
    domains = set()
    values = [provider.get("url"), provider.get("canonicalDomain"),
              provider.get("lastWorkingDomain")]
    values.extend(provider.get("aliases") or [])
    for value in values:
        host = normalize_domain(value)
        if host:
            domains.add(host)
        registered = registered_domain(host or "")
        if registered:
            domains.add(registered)
    return domains


def find_provider_by_title(data, title):
    """Records whose stored titleSignature loosely matches `title`.

    Mirrors / same-site clones on DIFFERENT registered domains never match
    find_provider_by_domain; this lookup catches them via the <title>
    baseline captured by health-check. Returns a (possibly empty) list.
    """
    if not title:
        return []
    matches = []
    for provider in providers(data):
        signature = provider.get("titleSignature")
        if signature and titles_match(signature, title):
            matches.append(provider)
    return matches


def add_alias(data, site_id, domain):
    """Attach a mirror domain to an EXISTING record (never a second record).

    Unlike update_domain the primary url is NOT changed: a mirror stays a
    secondary access path. Raises DuplicateSiteError when the domain is
    tracked by a DIFFERENT record, so mirrors can never fork the registry.
    """
    provider = get_provider(data, site_id)
    if provider is None:
        raise NotFoundError(f"site not in registry: {site_id}")
    dom = normalize_domain(domain)
    if not dom:
        raise RegistryError(f"invalid domain: {domain!r}")
    owner, _ = find_provider_by_domain(data, dom)
    if owner is not None and owner.get("siteId") != site_id:
        raise DuplicateSiteError(f"domain already tracked by {owner['siteId']}: {dom}")
    canonical_host = normalize_domain(provider.get("url"))
    aliases = set(provider.get("aliases") or [])
    if dom != canonical_host:
        aliases.add(dom)
    registered = registered_domain(dom)
    if registered and registered != canonical_host:
        aliases.add(registered)
    provider["aliases"] = sorted(aliases)
    provider["lastChecked"] = utc_now_iso()
    return provider


def validate_registry(data):
    """Return a list of schema errors (empty list = valid)."""
    problems = []
    if not isinstance(data, dict):
        return ["registry must be a JSON object"]
    if data.get("version") != REGISTRY_VERSION:
        problems.append(f"version must be {REGISTRY_VERSION}")
    if not isinstance(data.get("providers"), list):
        problems.append("providers must be a list")
        return problems
    seen_ids = set()
    seen_canonical = {}
    seen_domains = {}
    for index, provider in enumerate(data["providers"]):
        where = f"providers[{index}]"
        for field in ("siteId", "name", "url", "canonicalDomain", "lastWorkingDomain", "status"):
            if not provider.get(field):
                problems.append(f"{where}.{field} is missing")
        status = provider.get("status")
        if status not in STATUSES:
            problems.append(f"{where}.status invalid: {status!r}")
        violation = content_policy_violation(provider)
        if violation:
            problems.append(f"{where}: content policy violation ({violation})")
        url = provider.get("url") or ""
        if not url.startswith("https://"):
            problems.append(f"{where}.url must start with https:// ({url!r})")
        site_id = provider.get("siteId")
        if site_id:
            if site_id in seen_ids:
                problems.append(f"{where}.siteId duplicates {site_id!r}")
            seen_ids.add(site_id)
        canonical = registered_domain(url)
        if provider.get("canonicalDomain") and canonical and provider["canonicalDomain"] != canonical:
            problems.append(
                f"{where}.canonicalDomain ({provider['canonicalDomain']}) does not match url domain ({canonical})"
            )
        if canonical:
            if canonical in seen_canonical and seen_canonical[canonical] != site_id:
                problems.append(
                    f"{where}.canonicalDomain {canonical!r} also used by {seen_canonical[canonical]!r}"
                )
            seen_canonical[canonical] = site_id
        # hard guarantee: one domain may belong to exactly one record
        for domain in sorted(all_domains(provider)):
            owner = seen_domains.get(domain)
            if owner and owner != site_id:
                problems.append(
                    f"{where}: domain {domain!r} is also tracked by {owner!r}"
                )
            elif not owner:
                seen_domains[domain] = site_id
    return problems


# --------------------------------------------------------------------------
# mutations (create / update / move)
# --------------------------------------------------------------------------

def add_provider(data, name, url, category="community", probe_path="/", extra=None):
    """Create a NEW site record (0 -> 1 / N -> N+1).

    Raises DuplicateSiteError when the siteId or the domain is already tracked,
    so the same site can never be registered twice.
    """
    dom = normalize_domain(url)
    if not dom:
        raise RegistryError(f"invalid url: {url!r}")
    if duplicate_exists(data, domain=dom):
        raise DuplicateSiteError(f"domain already tracked: {dom}")
    site_id = slugify(name)
    if get_provider(data, site_id) is not None:
        site_id = f"{site_id}-{dom.replace('.', '-')}"
        if get_provider(data, site_id) is not None:
            raise DuplicateSiteError(f"siteId already tracked: {site_id}")
    entry = {
        "siteId": site_id,
        "name": name,
        "url": https_base(dom),
        "canonicalDomain": registered_domain(dom),
        "lastWorkingDomain": registered_domain(dom),
        "aliases": [],
        "status": "active",
        "category": category,
        "enabled": True,
        "probePath": probe_path or "/",
        "titleSignature": None,
        "lastChecked": utc_now_iso(),
        "lastError": None,
    }
    if extra:
        entry.update(extra)
    providers(data).append(entry)
    return entry


def update_provider(data, site_id, **fields):
    """Update an EXISTING record in place (never creates a second record)."""
    provider = get_provider(data, site_id)
    if provider is None:
        raise NotFoundError(f"site not in registry: {site_id}")
    for key, value in fields.items():
        provider[key] = value
    provider["lastChecked"] = utc_now_iso()
    return provider


def update_domain(data, site_id, new_url_or_domain, confirmed):
    """Move a tracked site to a new domain WITHOUT creating a duplicate.

    The old domain is preserved as an alias, so the same site can never grow a
    second provider record (registry count stays 1 -> 1). Unconfirmed moves
    raise UnconfirmedDomainChangeError: the caller must open an issue and wait
    for a human decision instead of merging automatically.
    """
    provider = get_provider(data, site_id)
    if provider is None:
        raise NotFoundError(f"site not in registry: {site_id}")
    new_dom = normalize_domain(new_url_or_domain)
    if not new_dom:
        raise RegistryError(f"invalid domain: {new_url_or_domain!r}")
    old_dom = normalize_domain(provider.get("url"))
    if not confirmed:
        raise UnconfirmedDomainChangeError(
            f"{site_id}: cannot confirm {old_dom} -> {new_dom} is the same site"
        )
    if new_dom == old_dom:
        return provider
    aliases = set(provider.get("aliases") or [])
    aliases.add(old_dom)
    old_registered = registered_domain(old_dom)
    if old_registered and old_registered != old_dom:
        aliases.add(old_registered)
    provider["aliases"] = sorted(aliases)
    provider["url"] = https_base(new_dom)
    provider["canonicalDomain"] = registered_domain(new_dom)
    provider["lastWorkingDomain"] = registered_domain(new_dom)
    provider["lastChecked"] = utc_now_iso()
    return provider


# --------------------------------------------------------------------------
# status model (ACTIVE / DEGRADED / INACTIVE) + probing
# --------------------------------------------------------------------------

def compute_status(site_reachable, probe_ok):
    if not site_reachable:
        return "inactive"
    return "active" if probe_ok else "degraded"


def fetch(url, timeout=20, user_agent=USER_AGENT):
    """GET a url, following redirects. Returns (final_url, http_status, body)."""
    request = urllib.request.Request(url, headers={"User-Agent": user_agent})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.geturl(), response.status, response.read(262144).decode("utf-8", "ignore")


def extract_title(html):
    match = re.search(r"<title[^>]*>(.*?)</title>", html or "", re.IGNORECASE | re.DOTALL)
    if not match:
        return None
    return re.sub(r"\s+", " ", match.group(1)).strip() or None


def check_site(provider, retries=2, timeout=20, fetch_fn=None):
    """Probe one site (works on a bare dict or a full registry record).

    ACTIVE   : site reachable and the probe responded 2xx/3xx
    DEGRADED : site reachable, but the probe answered with an HTTP error
    INACTIVE : unreachable after retries (DNS / connection / timeout)

    The canonical url is tried first; if it fails, lastWorkingDomain from the
    registry is used as a fallback. Network errors are retried to absorb
    transient failures. A site is NEVER removed from the registry here.
    """
    fetch = fetch_fn or fetch
    base = (provider.get("url") or "").rstrip("/")
    probe_path = provider.get("probePath") or "/"
    targets = [base]
    fallback = normalize_domain(provider.get("lastWorkingDomain") or "")
    if fallback and https_base(fallback) != base:
        targets.append(https_base(fallback))
    last_error = None
    for target_base in targets:
        target = target_base if probe_path in ("", "/") else target_base + probe_path
        for _ in range(max(1, retries + 1)):
            try:
                final_url, http_status, body = fetch(target)
                final_domain = normalize_domain(final_url)
                title = extract_title(body)
                if 200 <= http_status < 400:
                    return {"status": "active", "final_domain": final_domain,
                            "title": title, "error": None}
                return {"status": "degraded", "final_domain": final_domain,
                        "title": title, "error": f"HTTP {http_status} on {probe_path}"}
            except Exception as exc:  # any network error -> retry, then next target
                last_error = f"{type(exc).__name__}: {exc}"
    return {"status": "inactive", "final_domain": None, "title": None, "error": last_error}


def apply_check_result(provider, result):
    """Write a check_site() result back onto the record.

    Returns True when anything the registry persists changed (status, error,
    lastWorkingDomain or a freshly captured titleSignature).
    """
    changed = False
    if provider.get("status") != result["status"]:
        provider["status"] = result["status"]
        changed = True
    if provider.get("lastError") != result.get("error"):
        provider["lastError"] = result.get("error")
        changed = True
    final_domain = result.get("final_domain")
    if result["status"] in ("active", "degraded") and final_domain:
        working = registered_domain(final_domain)
        if working and provider.get("lastWorkingDomain") != working:
            provider["lastWorkingDomain"] = working
            changed = True
    if result.get("title") and not provider.get("titleSignature"):
        provider["titleSignature"] = result["title"]
        changed = True
    provider["lastChecked"] = utc_now_iso()
    return changed


def confirm_same_site(provider, new_url, fetch_fn=None):
    """Best-effort confirmation that a moved domain is the SAME site.

    Conservative by design: only a stored titleSignature match counts. When
    nothing confirms the move the caller must open an issue instead of
    updating the registry automatically.
    """
    signature = provider.get("titleSignature")
    if not signature:
        return (False, "no stored titleSignature to compare against")
    try:
        fetch = fetch_fn or fetch
        _, _, body = fetch(new_url)
    except Exception as exc:
        return (False, f"new domain unreachable: {exc}")
    if titles_match(signature, extract_title(body)):
        return (True, "stored title signature matches")
    return (False, "title signature does not match")



