# async-profiler-mcp-server — Quick Start

## Prerequisites

- Java 21
- Maven 3.9+ (or use the included `./mvnw`)
- Podman (preferred) or Docker for container builds
- `kubectl` / `oc` for Kubernetes / OpenShift deployment
- **Jafra Analyzer running** — the backend service that stores JFR chunks and serves the `/report` and `/summary` endpoints

---

## Step 0: Start the Jafra stack

The MCP server proxies analysis requests to the Jafra Analyzer. Ensure it is reachable before starting this server.

```bash
# Verify the analyzer is up
curl http://localhost:8080/api/v1/recordings
# Expected: {"workloads":[...]}
```

If you're using the Kind-based Jafra setup:

```bash
# Deploy the full Jafra stack (analyzer + agent + controller)
# Port-forward the analyzer for local dev
kubectl -n jafra-system port-forward svc/jafra-analyzer 8080:8080
```

---

## Local Development

### Build

```bash
./mvnw clean package -DskipTests
```

### Run

```bash
# Connects to Jafra Analyzer on http://localhost:8080
JAFRA_ANALYZER_URL=http://localhost:8080 \
  java -jar target/async-profiler-mcp-server-1.0.0-SNAPSHOT-runner.jar
```

### Run smoke test

After port-forwarding the MCP server to `18081`:

```bash
kubectl -n jafra-system port-forward svc/async-profiler-mcp-server 18081:8080
python3 smoke_test.py
```

The smoke test performs a full MCP handshake, lists tools, and calls both `get_recording_report` and `get_jfr_summary` against a live `auth-cache` pod.

### Connect MCP Inspector

```bash
npm install -g @modelcontextprotocol/inspector
npx @modelcontextprotocol/inspector http://localhost:8080/mcp/
```

In the browser:
1. Set URL to `http://localhost:8080/mcp/`
2. Click **Connect**
3. Click **List Tools** — you should see all 9 tools:
   - `list_profiled_pods`
   - `get_pod_jvm_status`
   - `get_jvm_statistics`
   - `start_recording` *(stub)*
   - `stop_recording` *(stub)*
   - `get_recording` *(stub)*
   - `get_recording_report`
   - `get_jfr_summary`
   - `get_flame_graph` *(stub)*

---

## Kind Deployment

### Step 1: Build the image

```bash
# linux/amd64 — required for Kind / OCP nodes
podman build --platform linux/amd64 \
  -t quay.io/khansaad/async-profiler-mcp-server:0.1.0 .
```

### Step 2: Load into Kind

```bash
kind load docker-image quay.io/khansaad/async-profiler-mcp-server:0.1.0 --name jafra
```

> **Note:** When updating an existing tag, always use a new tag (e.g. `0.1.0` → `0.1.2`) before loading into Kind. If you reuse a tag, the node may serve the cached layer and the pod will run the old image even after rollout.

### Step 3: Deploy

```bash
kubectl apply -f manifests/async-profiler-mcp-server-kind.yaml
kubectl rollout status deployment/async-profiler-mcp-server -n jafra-system
```

The manifest sets `JAFRA_ANALYZER_URL=http://jafra-analyzer.jafra-system.svc.cluster.local:8080` automatically.

### Step 4: Port-forward and test

```bash
kubectl -n jafra-system port-forward svc/async-profiler-mcp-server 18081:8080
python3 smoke_test.py http://127.0.0.1:18081
```

---

## OpenShift Deployment

### Step 1: Build and push the image

```bash
podman build --platform linux/amd64 \
  -t quay.io/khansaad/async-profiler-mcp-server:0.1.0 .
podman push quay.io/khansaad/async-profiler-mcp-server:0.1.0
```

### Step 2: Update and apply the manifest

Edit [`manifests/async-profiler-mcp-server-openshift.yaml`](manifests/async-profiler-mcp-server-openshift.yaml):

```yaml
env:
  - name: JAFRA_ANALYZER_URL
    value: "http://jafra-analyzer.jafra-system.svc.cluster.local:8080"
  - name: PROFILER_DEFAULT_NAMESPACE
    value: "default"
```

Then deploy:

```bash
oc apply -f manifests/async-profiler-mcp-server-openshift.yaml
```

### Step 3: Port-forward and test

```bash
kubectl -n jafra-system port-forward svc/async-profiler-mcp-server 18081:8080
python3 smoke_test.py http://127.0.0.1:18081
```

---

## Environment Variables

| Variable                     | Default                 | Description                                        |
|------------------------------|-------------------------|----------------------------------------------------|
| `JAFRA_ANALYZER_URL`         | `http://localhost:8080` | Base URL of the Jafra Analyzer backend             |
| `PROFILER_DEFAULT_NAMESPACE` | `default`               | Fallback Kubernetes namespace when callers omit it |
| `QUARKUS_HTTP_PORT`          | `8080`                  | HTTP port the MCP server listens on                |

---

## Run Tests

```bash
./mvnw test
```
