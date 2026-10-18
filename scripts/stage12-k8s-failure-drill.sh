#!/usr/bin/env bash
set -euo pipefail

: "${KUBE_CONTEXT:?set KUBE_CONTEXT to the reviewed test or staging context}"
namespace="${NAMESPACE:-im}"

before="$(kubectl --context "$KUBE_CONTEXT" -n "$namespace" get deployment im-app -o jsonpath='{.status.readyReplicas}')"
[[ "${before:-0}" -ge 3 ]] || { echo "at least three ready replicas are required" >&2; exit 1; }

victim="$(kubectl --context "$KUBE_CONTEXT" -n "$namespace" get pod -l app.kubernetes.io/name=im-app,app.kubernetes.io/track=stable -o jsonpath='{.items[0].metadata.name}')"
kubectl --context "$KUBE_CONTEXT" -n "$namespace" delete pod "$victim" --wait=false
kubectl --context "$KUBE_CONTEXT" -n "$namespace" rollout status deployment/im-app --timeout=5m

ready="$(kubectl --context "$KUBE_CONTEXT" -n "$namespace" get deployment im-app -o jsonpath='{.status.readyReplicas}')"
[[ "${ready:-0}" -ge 3 ]] || { echo "replica recovery failed" >&2; exit 1; }
echo "PASS: one application pod was removed and capacity returned to three ready replicas"
