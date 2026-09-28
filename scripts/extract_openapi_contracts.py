#!/usr/bin/env python3
"""Extract the slice of the backend OpenAPI schema the Android app actually depends on.

Reads the committed backend OpenAPI export (trading-platform2, `audit/front/openapi.json`
by default — 3.1, ~490 paths) and writes a trimmed, deterministic copy to
`app/src/test/resources/contracts/openapi.json`, keeping only:

  1. the `paths` entries that match a Retrofit endpoint declared in
     `app/src/main/java/com/tradingplatform/app/data/api/*.kt` (`@GET`/`@POST`/`@PUT`/
     `@DELETE`/`@PATCH` with a literal path argument — `PairingLanApi.kt` is skipped, it
     talks to the Radxa over LAN with a dynamic `@Url`, not to the VPS), and
  2. the `components.schemas` entries transitively referenced (via `$ref`/`allOf`/`anyOf`/
     `oneOf`/`items`/`properties`/`additionalProperties`) by those paths' request bodies and
     responses.

`app/src/test/java/com/tradingplatform/app/contracts/DtoContractTest.kt` reads the output
and asserts the app's DTOs and Retrofit paths have not drifted from it. Re-run this script
after any backend contract change (new/renamed field, new endpoint the app calls, changed
required-ness) and commit the regenerated `openapi.json`:

    python3 scripts/extract_openapi_contracts.py [path/to/backend/openapi.json]

No third-party dependencies — stdlib only (`json`, `re`, `pathlib`).
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_BACKEND_OPENAPI = Path("/home/thomas/Codes/trading-platform2/audit/front/openapi.json")
API_DIR = REPO_ROOT / "app/src/main/java/com/tradingplatform/app/data/api"
OUT_PATH = REPO_ROOT / "app/src/test/resources/contracts/openapi.json"

# Interfaces under data/api/ that are intentionally excluded from this extraction:
#   PairingLanApi.kt — talks to the Radxa over the LAN (`@Url` is fully dynamic, built from
#   the scanned device IP at runtime); there is no literal path and no backend OpenAPI
#   contract to compare against (see CLAUDE.md §8 "Repository LAN").
SKIP_API_FILES = {"PairingLanApi.kt"}

RETROFIT_METHOD_RE = re.compile(r'@(GET|POST|PUT|DELETE|PATCH)\(\s*"([^"]*)"\s*\)')

# Known Android-compatibility aliases: routes the app calls that the backend serves via
# `include_in_schema=False` (trading-platform2 app/auth/router.py:475-477 — "Alias for
# Android compatibility: POST /2fa/verify -> same handler as /verify-2fa"), so they never
# appear as a `paths` key in the generated OpenAPI even though they are real, deliberate,
# same-handler routes. Used ONLY to source request/response schema names for the DTO
# contract test below — the alias path itself is NOT injected into the trimmed `paths`
# object (it genuinely is not a key in the source-of-truth file). DtoContractTest's
# Retrofit-path-existence check special-cases these as accepted exceptions.
#
# `/v1/portfolios/{portfolio_id}/transactions` (also `include_in_schema=False`, router.py
# ~1081-1084, "Alias of /trades for Android compatibility") is NOT listed here: its
# `TransactionListResponse` schema is never referenced by any *included* path (the only
# route using it as a response_model is excluded), so FastAPI drops the schema from
# components.schemas entirely — there is nothing to alias to. DtoContractTest skips
# TransactionDto/TransactionListResponseDto for this reason (see the KDoc above its
# DTO_SCHEMA_TABLE).
SCHEMA_ONLY_ALIASES: dict[tuple[str, str], tuple[str, str]] = {
    ("POST", "/v1/auth/2fa/verify"): ("POST", "/v1/auth/verify-2fa"),
}

# Schemas to always pull in even if no currently-matched Retrofit endpoint references them.
# NavResponseDto (data/model/NavResponseDto.kt) mirrors the backend's `NavResponse` schema
# (GET /v1/portfolios/{portfolio_id}/nav) but nothing in data/api/PortfolioApi.kt calls that
# endpoint yet (dead DTO, kept so DtoContractTest still guards it against bit-rot once it is
# wired up) — it would otherwise be silently dropped by the transitive-from-matched-paths walk.
EXTRA_SCHEMAS: set[str] = {"NavResponse"}


def normalize(path: str) -> str:
    return path if path.startswith("/") else "/" + path


def path_template_key(path: str) -> str:
    """Collapse every {param} segment to {} so param-NAME differences (e.g. Retrofit's
    {deviceId} vs the backend's {device_id}) don't break matching — Retrofit only requires
    the @Path name to match the string literal in its OWN annotation, not the server's."""
    return re.sub(r"\{[^/{}]+\}", "{}", path)


def find_retrofit_endpoints() -> list[tuple[str, str, str]]:
    endpoints: list[tuple[str, str, str]] = []
    for kt_file in sorted(API_DIR.glob("*.kt")):
        if kt_file.name in SKIP_API_FILES:
            continue
        text = kt_file.read_text(encoding="utf-8")
        for m in RETROFIT_METHOD_RE.finditer(text):
            method, path = m.group(1), normalize(m.group(2))
            endpoints.append((method, path, kt_file.name))
    return endpoints


def resolve_ref_name(ref: str) -> str:
    return ref.split("/")[-1]


def collect_schema_refs(node, found: set[str]) -> None:
    """Walk a schema (sub-)node, collecting every referenced schema NAME into `found`."""
    if isinstance(node, dict):
        if "$ref" in node:
            found.add(resolve_ref_name(node["$ref"]))
        for key in ("allOf", "anyOf", "oneOf"):
            for sub in node.get(key, []):
                collect_schema_refs(sub, found)
        if "items" in node:
            collect_schema_refs(node["items"], found)
        if "properties" in node:
            for sub in node["properties"].values():
                collect_schema_refs(sub, found)
        if isinstance(node.get("additionalProperties"), dict):
            collect_schema_refs(node["additionalProperties"], found)
    elif isinstance(node, list):
        for item in node:
            collect_schema_refs(item, found)


def main() -> int:
    backend_openapi_path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_BACKEND_OPENAPI
    if not backend_openapi_path.is_file():
        print(f"ERROR: backend OpenAPI not found at {backend_openapi_path}", file=sys.stderr)
        return 1

    backend = json.loads(backend_openapi_path.read_text(encoding="utf-8"))
    backend_paths: dict = backend.get("paths", {})
    all_schemas: dict = backend.get("components", {}).get("schemas", {})

    endpoints = find_retrofit_endpoints()

    backend_by_template: dict[str, list[str]] = {}
    for p in backend_paths:
        backend_by_template.setdefault(path_template_key(p), []).append(p)

    matched_paths: dict[str, dict] = {}
    unmatched: list[tuple[str, str, str]] = []
    ambiguous: list[tuple[str, str, list[str]]] = []
    schema_worklist: set[str] = set()

    for method, path, source_file in endpoints:
        method_lower = method.lower()
        real_backend_path: str | None = None
        if path in backend_paths:
            real_backend_path = path
        else:
            candidates = backend_by_template.get(path_template_key(path), [])
            if len(candidates) == 1:
                real_backend_path = candidates[0]
            elif len(candidates) > 1:
                ambiguous.append((method, path, candidates))
                real_backend_path = candidates[0]

        alias = SCHEMA_ONLY_ALIASES.get((method, path))

        if real_backend_path is None and alias is None:
            unmatched.append((method, path, source_file))
            continue

        if real_backend_path is not None:
            src_method_lower, src_path = method_lower, real_backend_path
        else:
            assert alias is not None
            src_method_lower, src_path = alias[0].lower(), alias[1]

        op = backend_paths.get(src_path, {}).get(src_method_lower)
        if op is None:
            unmatched.append((method, path, source_file))
            continue

        # Only inject a REAL match into the trimmed `paths` object — an alias resolved via
        # SCHEMA_ONLY_ALIASES is deliberately NOT a key of the source-of-truth file.
        if real_backend_path is not None:
            matched_paths.setdefault(real_backend_path, {})[method_lower] = op

        request_body = op.get("requestBody")
        if request_body:
            content = request_body.get("content", {}).get("application/json", {})
            if "schema" in content:
                collect_schema_refs(content["schema"], schema_worklist)
        for response in op.get("responses", {}).values():
            content = response.get("content", {}).get("application/json", {})
            if "schema" in content:
                collect_schema_refs(content["schema"], schema_worklist)

    collected_schemas: dict[str, dict] = {}
    frontier = set(schema_worklist) | EXTRA_SCHEMAS
    while frontier:
        name = frontier.pop()
        if name in collected_schemas:
            continue
        schema = all_schemas.get(name)
        if schema is None:
            continue
        collected_schemas[name] = schema
        nested: set[str] = set()
        collect_schema_refs(schema, nested)
        frontier.update(n for n in nested if n not in collected_schemas)

    output = {
        "openapi": backend.get("openapi"),
        "_generated_by": "scripts/extract_openapi_contracts.py",
        "_source": str(backend_openapi_path),
        "paths": matched_paths,
        "components": {"schemas": collected_schemas},
    }

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_PATH.write_text(json.dumps(output, indent=2, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")

    print(f"Wrote {OUT_PATH}")
    print(f"  {len(matched_paths)} backend paths matched ({len(endpoints)} Retrofit endpoints scanned)")
    print(f"  {len(collected_schemas)} schemas collected transitively")
    if ambiguous:
        print("AMBIGUOUS matches (multiple backend paths share the same {param} template — took the first):")
        for method, path, candidates in ambiguous:
            print(f"  {method} {path} -> {candidates}")
    if unmatched:
        print("UNMATCHED Retrofit endpoints (no backend path, no SCHEMA_ONLY_ALIASES entry):")
        for method, path, source_file in unmatched:
            print(f"  {method} {path}  ({source_file})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
