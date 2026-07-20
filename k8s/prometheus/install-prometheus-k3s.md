# Instalasi Prometheus di k3s (jkt-ho-svr-051)

Dokumentasi instalasi `kube-prometheus-stack` (Prometheus + Grafana + Alertmanager + node-exporter) di cluster k3s pada host `jkt-ho-svr-051` (8 vCPU, ~16 GB RAM), dengan NGINX Ingress (Traefik disabled).

---

## 1. Prasyarat

Cek kubectl bisa akses cluster:

```bash
kubectl get nodes
```

Cek NGINX Ingress controller sudah jalan (karena Traefik di-disable):

```bash
kubectl get pods -n ingress-nginx
# atau kalau namespace-nya berbeda:
kubectl get pods -A | grep nginx
```

---

## 2. Install Helm (skip kalau sudah ada)

```bash
which helm
```

Kalau kosong:

```bash
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
helm version
```

---

## 3. Tambahkan repo Helm

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
```

---

## 4. Buat namespace monitoring

```bash
kubectl create namespace monitoring
```

---

## 5. File `values.yaml`

Simpan sebagai `values.yaml`. **Wajib sesuaikan** bagian bertanda `# GANTI`.

```yaml
# values.yaml - kube-prometheus-stack untuk k3s (jkt-ho-svr-051)
# Host: 8 vCPU / ~16GB RAM, sudah ada workload BIMA di node yang sama

fullnameOverride: monitoring

# ================= PROMETHEUS =================
prometheus:
  prometheusSpec:
    retention: 7d
    retentionSize: "5GB"
    resources:
      requests:
        cpu: 250m
        memory: 512Mi
      limits:
        cpu: 500m
        memory: 1Gi
    storageSpec:
      volumeClaimTemplate:
        spec:
          storageClassName: local-path   # default k3s storage class
          accessModes: ["ReadWriteOnce"]
          resources:
            requests:
              storage: 10Gi
    # Kalau mau scrape service di namespace lain (mis. namespace BIMA),
    # kosongkan selector supaya semua ServiceMonitor ke-pickup
    serviceMonitorSelectorNilUsesHelmValues: false
    podMonitorSelectorNilUsesHelmValues: false

  ingress:
    enabled: true
    ingressClassName: nginx
    hosts:
      - prometheus.bcalife.internal   # GANTI sesuai domain internal kamu
    paths:
      - /
    pathType: Prefix

# ================= ALERTMANAGER =================
# Matikan dulu kalau belum butuh alerting channel (Slack/Telegram/dsb)
alertmanager:
  enabled: false

# ================= GRAFANA =================
grafana:
  enabled: true
  adminPassword: "GantiPasswordIni123!"   # GANTI, atau pakai existingSecret
  resources:
    requests:
      cpu: 100m
      memory: 128Mi
    limits:
      cpu: 200m
      memory: 256Mi
  persistence:
    enabled: true
    storageClassName: local-path
    size: 2Gi
  ingress:
    enabled: true
    ingressClassName: nginx
    hosts:
      - grafana.bcalife.internal       # GANTI sesuai domain internal kamu
    path: /

# ================= NODE EXPORTER =================
nodeExporter:
  enabled: true

prometheus-node-exporter:
  resources:
    requests:
      cpu: 50m
      memory: 32Mi
    limits:
      cpu: 100m
      memory: 64Mi

# ================= KUBE-STATE-METRICS =================
kube-state-metrics:
  resources:
    requests:
      cpu: 50m
      memory: 64Mi
    limits:
      cpu: 100m
      memory: 128Mi

# ================= OPERATOR =================
prometheusOperator:
  resources:
    requests:
      cpu: 100m
      memory: 128Mi
    limits:
      cpu: 200m
      memory: 256Mi
  admissionWebhooks:
    patch:
      enabled: true

# Non-aktifkan komponen yang biasanya tidak relevan untuk single-node k3s kecil
kubeControllerManager:
  enabled: false
kubeScheduler:
  enabled: false
kubeProxy:
  enabled: false
kubeEtcd:
  enabled: false
```

---

## 6. Dry-run (opsional, disarankan)

Cek konfigurasi sebelum apply beneran:

```bash
helm install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  -n monitoring \
  -f values.yaml \
  --dry-run --debug
```

---

## 7. Install

```bash
helm install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  -n monitoring \
  -f values.yaml
```

---

## 8. Tunggu semua pod Running

```bash
kubectl get pods -n monitoring -w
```

Tekan `Ctrl+C` setelah semua status `Running`/`Completed`. Biasanya butuh 1–3 menit karena CRD Prometheus Operator perlu ke-install dulu.

---

## 9. Cek resource yang terbentuk

```bash
kubectl get all -n monitoring
kubectl get ingress -n monitoring
kubectl get pvc -n monitoring
```

Pastikan PVC berstatus `Bound` (storageClass `local-path`).

---

## 10. Tambah entry DNS/hosts

Kalau domain internal belum resolve, tambahkan di komputer kamu (bukan di server):

**Linux/Mac** — `/etc/hosts`
**Windows** — `C:\Windows\System32\drivers\etc\hosts`

```
<IP_jkt-ho-svr-051>  prometheus.bcalife.internal
<IP_jkt-ho-svr-051>  grafana.bcalife.internal
```

---

## 11. Akses

| Layanan | URL | Login |
|---|---|---|
| Prometheus | `http://prometheus.bcalife.internal` | - |
| Grafana | `http://grafana.bcalife.internal` | `admin` / password sesuai `values.yaml` |

---

## 12. Verifikasi target scrape

Buka Prometheus UI → **Status → Targets**. Pastikan target `node-exporter`, `kube-state-metrics`, dsb berstatus `UP`.

---

## Troubleshooting

Kalau ada pod `CrashLoopBackOff` atau `Pending`:

```bash
kubectl describe pod <nama-pod> -n monitoring
kubectl logs <nama-pod> -n monitoring
```

---

## Catatan Penting

- **Storage single-node**: `local-path` provisioner tidak replikasi data. Kalau host down, data Prometheus/Grafana ikut hilang. Pertimbangkan backup berkala kalau retensi data penting.
- **Resource**: values di atas sudah dibatasi (requests/limits) supaya tidak berebut resource dengan workload BIMA di node yang sama.
- **Ingress**: karena Traefik disabled, semua ingress di atas eksplisit pakai `ingressClassName: nginx`.

---

## Next Step (opsional)

Setup `ServiceMonitor` untuk scrape metrics dari Spring Boot Actuator/Micrometer pada aplikasi BIMA/BASS/GITA, agar metrics aplikasi Java ikut masuk ke Prometheus.
