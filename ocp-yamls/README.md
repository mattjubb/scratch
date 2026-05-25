# OCP Deployment YAMLs

Apply in this order:

## 1. postgres-ocp.yaml
PostgreSQL database for Temporal.

```bash
oc apply -f postgres-ocp.yaml
oc rollout status deployment/postgres -n temporal
```

**Before applying:** replace `<CHANGE_ME>` passwords in the Secret.

Grant postgres SA the required SCC:
```bash
oc adm policy add-scc-to-user nonroot-v2 -z postgres-sa -n temporal
```

---

## 2. temporal-ocp.yaml
Temporal server (auto-setup) + UI.

```bash
oc apply -f temporal-ocp.yaml
oc rollout status deployment/temporal-server -n temporal
oc rollout status deployment/temporal-ui -n temporal
```

**Before applying:** replace `<CLUSTER_DOMAIN>` in the two Routes.
Find your domain:
```bash
oc whoami --show-console
# strip "https://console-openshift-console." prefix
```

---

## 3. worker-ocp.yaml
Temporal REST service worker — RBAC, ImageStream, BuildConfig, Deployment.

```bash
oc apply -f worker-ocp.yaml

# Build the worker image from source (run from temporal-rest-service/ project dir)
oc start-build temporal-rest-worker -n temporal --from-dir=. --follow

oc rollout status deployment/temporal-rest-worker -n temporal
```

---

## URLs (replace <CLUSTER_DOMAIN>)

| Service | URL |
|---------|-----|
| Temporal UI | https://temporal-ui-temporal.<CLUSTER_DOMAIN> |
| nginx REST /health | https://nginx-rest-rest-service.<CLUSTER_DOMAIN>/health |
| nginx REST /api/hello | https://nginx-rest-rest-service.<CLUSTER_DOMAIN>/api/hello |

---

## Stop the REST service
```bash
oc exec -n temporal deploy/temporal-rest-worker -- java -jar app.jar --stop
```
