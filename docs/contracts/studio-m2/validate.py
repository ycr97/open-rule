#!/usr/bin/env python3
"""Offline Schema/OpenAPI/fixture checks. No Java business runtime or HTTP calls."""
import copy
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from decimal import Decimal

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[2]
sys.path.insert(0, str(REPO / "scripts"))
from studio_m1_checksum import read, checksum, decimal  # existing frozen decimal rules


def load(path):
    return read(Path(path).read_text())


def encode(value):
    if isinstance(value, Decimal):
        return decimal(value)
    if isinstance(value, dict):
        return "{" + ",".join(json.dumps(k, ensure_ascii=False) + ":" + encode(v) for k, v in value.items()) + "}"
    if isinstance(value, list):
        return "[" + ",".join(map(encode, value)) + "]"
    return json.dumps(value, ensure_ascii=False, allow_nan=False)


def pointer(value, path):
    if not path:
        return value
    assert path.startswith("/"), path
    for token in path[1:].split("/"):
        token = token.replace("~1", "/").replace("~0", "~")
        value = value[int(token)] if isinstance(value, list) else value[token]
    return value


def resolve(ref, source):
    file, _, fragment = ref.partition("#")
    assert not re.match(r"^[a-z]+:", file), "Network references are not allowed: " + ref
    path = (source.parent / file).resolve() if file else source.resolve()
    assert path.is_relative_to(ROOT.parent), "Reference outside contracts: " + ref
    return pointer(load(path), fragment), path


def bundle(value, source, chain=()):
    if isinstance(value, dict):
        if "$ref" in value:
            target, path = resolve(value["$ref"], source)
            key = (str(source), value["$ref"])
            assert key not in chain, "Unexpected recursive wire schema: " + str(key)
            result = bundle(target, path, chain + (key,))
            siblings = {k: v for k, v in value.items() if k != "$ref"}
            return {"allOf": [result, bundle(siblings, source, chain)]} if siblings else result
        return {k: bundle(v, source, chain) for k, v in value.items()}
    if isinstance(value, list):
        return [bundle(v, source, chain) for v in value]
    return value


def overlay(base, patch):
    """Fixture object overlay: retain null; replace arrays/scalars. Not JSON Merge Patch."""
    result = copy.deepcopy(base)
    for key, value in patch.items():
        result[key] = overlay(result[key], value) if isinstance(value, dict) and isinstance(result.get(key), dict) else copy.deepcopy(value)
    return result


def main():
    manifest = load(ROOT / "manifest.json")
    api_path = ROOT / "openapi.json"
    api = load(api_path)
    schema_path = ROOT / "schema/m2.schema.json"
    definitions = load(schema_path)["$defs"]
    tests = []

    def add(label, data, schema_ref, valid=True, source=ROOT / "manifest.json"):
        tests.append({"label": label, "instance": data, "schema": bundle({"$ref": schema_ref}, source), "valid": valid})

    assert api["openapi"] == "3.1.0"
    assert api["info"]["title"] and api["info"]["version"]
    operation_ids = set()
    for path, item in api["paths"].items():
        assert path.startswith("/api/v2/")
        for method, op in item.items():
            if method == "parameters":
                continue
            assert method in {"get", "post", "put"}, (path, method)
            assert op["operationId"] not in operation_ids
            operation_ids.add(op["operationId"])
            params = item.get("parameters", []) + op.get("parameters", [])
            assert len({(p["in"], p["name"]) for p in params}) == len(params)
            assert {p["name"] for p in params if p["in"] == "path" and p["required"]} == set(re.findall(r"\{([^}]+)\}", path))
            assert any(str(code).startswith("2") for code in op["responses"])
            if method in {"post", "put"}:
                assert op["requestBody"]["required"]
            bundle(op, api_path)  # resolve every local schema/response/parameter reference
    assert not set(api["paths"]) & set(load(ROOT.parent / "studio-m1/openapi.json")["paths"])
    print(f"PASS OpenAPI structure/references: {len(api['paths'])} additive paths, {len(operation_ids)} unique operations")

    wire_types = {
        "createFact": ("FactDocument", "FactResource"),
        "updateFact": ("FactUpdate", "FactResource"),
        "createProvider": ("ProviderDocument", "ProviderResource"),
        "updateProvider": ("ProviderUpdate", "ProviderResource"),
        "testProviderMock": ("MockTestRequest", "MockTestResult"),
        "simulateWithFacts": ("FactSimulationRequest", "FactExecutionResult"),
        "executeWithFacts": ("FactExecutionRequest", "FactExecutionResult"),
    }
    for item in api["paths"].values():
        for method, op in item.items():
            if method == "parameters" or op["operationId"] not in wire_types:
                continue
            request_type, response_type = wire_types[op["operationId"]]
            assert op["requestBody"]["content"]["application/json"]["schema"]["$ref"] == "schema/m2.schema.json#/$defs/" + request_type
            for code, response in op["responses"].items():
                typ = response_type if code.startswith("2") else "ErrorBody"
                assert response["content"]["application/json"]["schema"]["$ref"] == "schema/m2.schema.json#/$defs/" + typ

    for entry in manifest["schemaCases"]:
        add(entry["file"], load(ROOT / entry["file"]), entry["schema"], entry["valid"])
    fixture_files = {p.relative_to(ROOT).as_posix() for d in ["valid", "invalid"] for p in (ROOT / "fixtures" / d).glob("*.json")}
    assert fixture_files == {e["file"] for e in manifest["schemaCases"]}, "Unlisted or absent Schema fixture"

    cases_path = ROOT / manifest["executionCases"]
    cases = load(cases_path)["cases"]
    assert len({c["id"] for c in cases}) == len(cases)
    m1_path = (ROOT / manifest["m1Definition"]).resolve()
    expected_checksum = checksum(m1_path.read_text())
    for case in cases:
        assert (cases_path.parent / case["definitionFile"]).resolve() == m1_path, case["id"] + " definition path"
        catalog = load(cases_path.parent / case["catalogFile"])
        provider = load(cases_path.parent / case["providerDocumentFile"])
        effective = overlay(provider, case["providerPatch"])
        add(case["id"] + " effective provider", effective, "schema/m2.schema.json#/$defs/ProviderDocument")
        for fact in catalog:
            add(case["id"] + " catalog", fact, "schema/m2.schema.json#/$defs/FactResource")
        typ = {"SIMULATE": "FactSimulationRequest", "LIVE": "FactExecutionRequest"}.get(case["endpoint"])
        if typ:
            add(case["id"] + " request", case["request"], "schema/m2.schema.json#/$defs/" + typ)
        else:
            assert case["endpoint"] == "M1_SIMULATE"
            add(case["id"] + " M1 request", case["request"], "../studio-m1/openapi.json#/components/schemas/ExecutionRequestWithRevision")
        expected = case["expected"]
        assert expected["httpCalls"] in (0, 1), case["id"]
        if case["request"].get("factMode") == "MOCK" or case["endpoint"].startswith("M1_"):
            assert expected["httpCalls"] == 0
        if not expected["coreInvoked"]:
            assert expected.get("execution") is None and expected.get("decisionCode") is None
        if expected.get("httpStatus") == 504:
            assert expected["errorCode"] == "OR-EXECUTION-TIMEOUT"
    print(f"PASS behavioral assets: {len(cases)} request/configuration combinations; expected outcomes not executed")

    static = load(ROOT / manifest["requiredFacts"])
    flow = load(m1_path)
    assert static["definitionChecksum"] == expected_checksum
    paths = [f["path"] for f in static["requiredFacts"]]
    assert paths == sorted(set(paths))
    expected_refs = {}

    def walk(value, path=""):
        if isinstance(value, dict):
            if "ref" in value and value["ref"].get("source") == "FACT":
                expected_refs[path + "/ref/pointer"] = value["ref"]["pointer"]
            for key, val in value.items():
                walk(val, path + "/" + key.replace("~", "~0").replace("/", "~1"))
        elif isinstance(value, list):
            for i, val in enumerate(value):
                walk(val, path + "/" + str(i))

    walk(flow)
    declared_refs = {}
    for fact in static["requiredFacts"]:
        for use in fact["uses"]:
            assert pointer(flow, use["pointer"]) == fact["path"]
            parts = use["pointer"].split("/")
            stage = flow["stages"][int(parts[2])]
            assert use["stageId"] == stage["stageId"]
            if "nodes" in parts:
                node = stage["nodes"][int(parts[4])]
                assert use["nodeId"] == node["nodeId"]
                row_pointer = use["pointer"].split("/condition/")[0]
                row = pointer(flow, row_pointer)
                assert use["itemId"] == row.get("id")
            declared_refs[use["pointer"]] = fact["path"]
    assert declared_refs == expected_refs, "RequiredFact references omitted or added"

    normal = load(ROOT / "fixtures/valid/normal.response.json")
    assert normal["execution"]["definitionChecksum"] == expected_checksum
    assert {x["path"] for x in normal["factResolution"]["fields"]} == set(paths)
    assert all(c["traceId"] == normal["execution"]["traceId"] for c in normal["factResolution"]["calls"])
    assert normal["execution"]["decision"]["score"] == "15"
    assert [(x["stageId"], x["nodeId"]) for x in normal["execution"]["nodeResults"]] == [(st["stageId"], n["nodeId"]) for st in flow["stages"] for n in st["nodes"]]
    print(f"PASS requiredFacts/checksum: {len(paths)} paths, {len(declared_refs)} original locations, frozen M1 definition")

    for case in load(ROOT / manifest["semanticCases"]):
        counterpart = load(ROOT / ("fixtures/valid/" + case["id"].lower() + ".semantic.json"))
        assert case["document"] == counterpart
        assert (ROOT / "fixtures" / case["catalogFile"]).is_file()
        assert (ROOT / "fixtures" / case["providerResourceFile"]).is_file()
        assert case["operation"]["method"] in {"POST", "PUT"}
        assert case["expectedHttpStatus"] in (400, 409)
    print("PASS semantic fixture indexing (runtime rejections remain unverified)")

    precise = load(ROOT / "fixtures/valid/precision.provider.json")["mock"]["response"]["data"]
    assert precise["ageDays"] == Decimal("9007199254740995")
    assert precise["refundRate"] == Decimal("0.123456789012345678901234567890123456789")
    assert read(encode(precise)) == precise

    # Check every newly-authored Markdown link without touching historical documents.
    documents = list(ROOT.glob("*.md")) + [
        REPO / "docs/design/2026-10-04-openrule-studio-m2-facts.md",
        REPO / "docs/plans/2026-10-04-openrule-studio-m2-delivery.md",
        REPO / "docs/adr/0006-studio-m2-fact-resolution.md",
    ]
    links = 0
    for document in documents:
        for target in re.findall(r"\]\(([^)]+)\)", document.read_text()):
            if target.startswith(("https:", "http:", "#")):
                continue
            file = target.split("#", 1)[0]
            assert (document.parent / file).resolve().exists(), str(document) + " -> " + target
            links += 1
    print(f"PASS precision literals and {links} local documentation links")

    # All Java compilation output and bundled files stay outside the repository.
    java_home = os.environ.get("JAVA_HOME")
    javac = str(Path(java_home) / "bin/javac") if java_home else "javac"
    java = str(Path(java_home) / "bin/java") if java_home else "java"
    with tempfile.TemporaryDirectory(prefix="openrule-m2-d0-") as directory:
        temp = Path(directory)
        cp = temp / "classpath.txt"
        subprocess.run(["mvn", "-o", "-q", "-f", str(REPO / "scripts/studio-m1-validation-pom.xml"), "dependency:build-classpath", "-Dmdep.outputFile=" + str(cp)], check=True)
        suite = {"schemas": [bundle(d, schema_path) for d in definitions.values()], "tests": tests}
        (temp / "suite.json").write_text(encode(suite))
        subprocess.run([javac, "-cp", cp.read_text().strip(), "-d", directory, str(ROOT / "checks/SchemaCheck.java")], check=True)
        subprocess.run([java, "-cp", directory + os.pathsep + cp.read_text().strip(), "SchemaCheck", str(temp / "suite.json")], check=True)
    print("PASS M2-D0 static contract checks; no Provider/Core/JDBC/browser implementation executed")


if __name__ == "__main__":
    main()
