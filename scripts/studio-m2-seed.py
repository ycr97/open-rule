#!/usr/bin/env python3
"""Create/reuse exact demo documents through APIs. Never overwrite a differing existing resource."""
import argparse
from decimal import Decimal
import json
from pathlib import Path
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
FIX = ROOT / "docs/contracts/studio-m2/fixtures"

def encode(value):
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, dict):
        return "{" + ",".join(json.dumps(k) + ":" + encode(v) for k, v in value.items()) + "}"
    if isinstance(value, list):
        return "[" + ",".join(map(encode, value)) + "]"
    return json.dumps(value, ensure_ascii=False, allow_nan=False)

def read(path):
    return json.loads(path.read_text(), parse_float=Decimal)

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--flow-id", default="m2_b2_demo")
    parser.add_argument("--endpoint", default="http://127.0.0.1:19091/v1/buyer/batch")
    args = parser.parse_args()

    def call(method, path, body=None):
        req = urllib.request.Request(args.base_url.rstrip("/") + "/api/v2" + path,
                                     data=None if body is None else encode(body).encode(),
                                     headers={"Content-Type": "application/json"}, method=method)
        try:
            response = urllib.request.urlopen(req, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        return response.status, json.loads(response.read(), parse_float=Decimal)

    def ensure(collection, identity, document):
        status, saved = call("GET", collection + "/" + identity)
        if status == 200:
            if saved["document"] != document:
                raise SystemExit("Existing resource differs; preserved without update: " + identity)
            return saved
        if status != 404:
            raise SystemExit("Cannot read " + identity)
        status, saved = call("POST", collection, document)
        if status != 201:
            raise SystemExit("Cannot create " + identity + ": " + saved.get("code", "unknown"))
        return saved

    provider = read(FIX / "valid/risk-profile.provider.json")
    provider["providerId"] = "b2_demo_risk"
    provider["endpoint"] = args.endpoint
    ensure("/admin/fact-providers", provider["providerId"], provider)
    for item in read(FIX / "catalog.json"):
        document = item["document"]
        document["factId"] = "b2_demo_" + document["factId"]
        if document["binding"] is not None:
            document["binding"]["providerId"] = provider["providerId"]
        ensure("/admin/facts", document["factId"], document)
    definition = read(ROOT / "docs/contracts/studio-m1/fixtures/valid/order-admission.definition.json")
    definition["flowId"] = args.flow_id
    path = "/admin/flows/" + args.flow_id + "/versions/1"
    status, resource = call("GET", path)
    if status == 404:
        status, _ = call("POST", "/admin/flows", {"flowId": args.flow_id, "flowName": definition["flowName"]})
        if status != 201:
            raise SystemExit("Cannot create demo Flow")
        status, resource = call("PUT", path, {"expectedRevision": "1", "document": definition})
        if status != 200:
            raise SystemExit("Cannot save demo Definition")
        status, resource = call("POST", path + "/publish", {"expectedRevision": resource["revision"], "changeNote": "B2 isolated demo"})
    if status != 200 or resource["document"] != definition or resource["status"] != "PUBLISHED":
        raise SystemExit("Existing Flow differs or is unpublished; preserved")
    print(encode({"flowId": args.flow_id, "version": "1", "revision": resource["revision"],
                  "definitionChecksum": resource["definitionChecksum"], "providerId": provider["providerId"]}))
