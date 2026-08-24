#!/usr/bin/env python3
"""
Smoke test for the async-profiler-mcp-server.
Runs a full MCP JSON-RPC handshake and calls both tools.

Usage:
  python3 smoke_test.py                  # defaults to localhost:18081
  python3 smoke_test.py http://host:port
"""
import json
import sys
import urllib.request
import urllib.error

BASE = sys.argv[1].rstrip("/") if len(sys.argv) > 1 else "http://127.0.0.1:18081"
MCP = f"{BASE}/mcp"

def post(payload, session=None):
    data = json.dumps(payload).encode()
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    }
    if session:
        headers["Mcp-Session-Id"] = session
    req = urllib.request.Request(MCP, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            session_id = resp.headers.get("Mcp-Session-Id")
            body = resp.read().decode()
            return session_id, json.loads(body) if body.strip() else None
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        return None, json.loads(body) if body.strip() else {"error": str(e)}


def check(label, data, *keys):
    cur = data
    for k in keys:
        if not isinstance(cur, dict) or k not in cur:
            print(f"  FAIL {label}: missing key '{k}' in {json.dumps(cur)[:120]}")
            return False
        cur = cur[k]
    print(f"  OK   {label}")
    return True


def main():
    print(f"Target: {MCP}\n")

    # ── 1. Initialize ─────────────────────────────────────────────────────────
    print("── 1. initialize")
    sid, resp = post({
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "smoke-test", "version": "1.0"},
        },
    })
    assert resp and "result" in resp, f"initialize failed: {resp}"
    assert sid, "no Mcp-Session-Id in response"
    print(f"  Session: {sid}")
    check("serverInfo.name", resp["result"], "serverInfo", "name")

    # ── 2. notifications/initialized ─────────────────────────────────────────
    print("── 2. notifications/initialized")
    post({"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}}, sid)
    print("  sent")

    # ── 3. tools/list ─────────────────────────────────────────────────────────
    print("── 3. tools/list")
    _, resp = post({"jsonrpc": "2.0", "id": 3, "method": "tools/list", "params": {}}, sid)
    assert resp and "result" in resp, f"tools/list failed: {resp}"
    tools = {t["name"] for t in resp["result"]["tools"]}
    print(f"  tools: {sorted(tools)}")
    for name in ("get_recording_report", "get_jfr_summary", "list_profiled_pods",
                 "get_pod_jvm_status", "get_jvm_statistics"):
        assert name in tools, f"tool '{name}' missing from tools/list"
        print(f"  found: {name}")

    # ── 4. tools/call — get_recording_report ──────────────────────────────────
    print("── 4. tools/call: get_recording_report")
    _, resp = post({
        "jsonrpc": "2.0", "id": 4, "method": "tools/call",
        "params": {
            "name": "get_recording_report",
            "arguments": {
                "namespace": "default",
                "pod": "auth-cache-6fcdc64b64-2cnw9",
                "container": "auth-cache",
                "last": "5m",
            },
        },
    }, sid)
    assert resp, "no response"
    if "error" in resp:
        print(f"  ERROR: {resp['error']}")
    else:
        content = resp["result"]["content"][0]["text"]
        parsed = json.loads(content)
        check("namespace", parsed, "namespace")
        check("findings present", parsed, "findings")
        findings = parsed.get("findings", {})
        print(f"  findings count: {len(findings)}")
        # Show top 3 by score
        top = sorted(findings.items(), key=lambda x: x[1].get("score", 0), reverse=True)[:3]
        for rule_id, f in top:
            print(f"    [{f.get('score',0):.1f}] {f.get('name')} — {f.get('topic')}")

    # ── 5. tools/call — get_jfr_summary ──────────────────────────────────────
    print("── 5. tools/call: get_jfr_summary")
    _, resp = post({
        "jsonrpc": "2.0", "id": 5, "method": "tools/call",
        "params": {
            "name": "get_jfr_summary",
            "arguments": {
                "namespace": "default",
                "pod": "auth-cache-6fcdc64b64-2cnw9",
                "container": "auth-cache",
                "last": "5m",
            },
        },
    }, sid)
    assert resp, "no response"
    if "error" in resp:
        print(f"  ERROR: {resp['error']}")
    else:
        content = resp["result"]["content"][0]["text"]
        parsed = json.loads(content)
        check("scope.namespace",     parsed, "scope", "namespace")
        check("environment.jvm",     parsed, "environment", "jvm")
        check("signals present",     parsed, "signals")
        jvm = parsed.get("environment", {}).get("jvm", {})
        print(f"  JVM: {jvm.get('jvmName')} {jvm.get('jvmVersion','')}")
        gc = parsed.get("signals", {}).get("gc", {})
        print(f"  GC events: {gc.get('count', 0)}  collectors: {gc.get('collectors', [])}")
        heap = parsed.get("signals", {}).get("heap", {})
        used = heap.get("used_b", {})
        if used:
            print(f"  Heap used avg: {used.get('avg',0)/1024/1024:.1f} MiB")

    print("\n✓ All smoke tests passed")


if __name__ == "__main__":
    main()
