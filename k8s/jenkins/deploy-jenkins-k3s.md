# Deploy Jenkins di k3s — Dokumentasi

## Informasi Umum

- **Host k3s**: jkt-ho-svr-051 (IP `10.1.40.51`), 8 vCPU, ~16 GB RAM
- **Akses Jenkins**: NodePort — HTTP `31080`, agent/JNLP `31500`
- **Jenkins version**: LTS `2.568.1`, Java 21
- **Ingress**: NGINX Ingress terpasang, Traefik di-disable
- **Git server**: Gitea self-hosted di `http://10.3.1.67:8080`
- **Docker registry**: Nexus di `10.1.40.51` (user `admin`, cek port Docker connector di Nexus UI)

---

## 1. Manifest Dasar (Namespace, PVC, Deployment, Service)

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: jenkins
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: jenkins-pvc
  namespace: jenkins
spec:
  accessModes:
    - ReadWriteOnce
  storageClassName: local-path
  resources:
    requests:
      storage: 10Gi
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jenkins
  namespace: jenkins
spec:
  replicas: 1
  selector:
    matchLabels:
      app: jenkins
  template:
    metadata:
      labels:
        app: jenkins
    spec:
      containers:
        - name: jenkins
          image: 10.1.40.51:PORT_NEXUS/jenkins-docker:2.568.1-jdk21
          env:
            - name: DOCKER_HOST
              value: tcp://localhost:2375
          ports:
            - containerPort: 8080
            - containerPort: 50000
          resources:
            requests:
              cpu: "500m"
              memory: "1Gi"
            limits:
              cpu: "1"
              memory: "2Gi"
          volumeMounts:
            - name: jenkins-home
              mountPath: /var/jenkins_home

        - name: dind
          image: docker:24-dind
          securityContext:
            privileged: true
          env:
            - name: DOCKER_TLS_CERTDIR
              value: ""
          args:
            - --host=tcp://0.0.0.0:2375
            - --insecure-registry=10.1.40.51:PORT_NEXUS
          volumeMounts:
            - name: docker-storage
              mountPath: /var/lib/docker

      volumes:
        - name: jenkins-home
          persistentVolumeClaim:
            claimName: jenkins-pvc
        - name: docker-storage
          emptyDir: {}
---
apiVersion: v1
kind: Service
metadata:
  name: jenkins-svc
  namespace: jenkins
spec:
  type: NodePort
  selector:
    app: jenkins
  ports:
    - name: http
      port: 8080
      targetPort: 8080
      nodePort: 31080
    - name: agent
      port: 50000
      targetPort: 50000
      nodePort: 31500
```

Apply:
```bash
kubectl apply -f jenkins-all.yaml
kubectl get pods -n jenkins   # pastikan 2/2 Running
```

Ambil password admin awal:
```bash
kubectl exec -n jenkins -it deploy/jenkins -c jenkins -- \
  cat /var/jenkins_home/secrets/initialAdminPassword
```

Akses: `http://10.1.40.51:31080`

---

## 2. Custom Image Jenkins (Docker CLI + kubectl + Helm)

Base image Jenkins resmi tidak punya Docker/kubectl/Helm, dan container jalan sebagai user non-root sehingga `apt-get install` manual di dalam pod tidak bisa dipakai permanen. Solusi: build custom image.

### Dockerfile

```dockerfile
FROM jenkins/jenkins:2.568.1-jdk21

USER root

RUN apt-get update && apt-get install -y \
    curl apt-transport-https ca-certificates gnupg lsb-release

# Docker CLI
RUN curl -fsSL https://download.docker.com/linux/debian/gpg | gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg
RUN echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://download.docker.com/linux/debian $(lsb_release -cs) stable" > /etc/apt/sources.list.d/docker.list
RUN apt-get update && apt-get install -y docker-ce-cli

# kubectl
RUN curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl" \
    && install kubectl /usr/local/bin/kubectl \
    && rm kubectl

# Helm
RUN curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash

USER jenkins
```

### Build & Push ke Nexus

```bash
# build
docker build -t jenkins-docker:2.568.1-jdk21 .

# tag sesuai Nexus
docker tag jenkins-docker:2.568.1-jdk21 10.1.40.51:PORT_NEXUS/jenkins-docker:2.568.1-jdk21

# login ke Nexus
docker login 10.1.40.51:PORT_NEXUS -u admin -p admin123

# push
docker push 10.1.40.51:PORT_NEXUS/jenkins-docker:2.568.1-jdk21
```

> Ganti `PORT_NEXUS` dengan port Docker connector Nexus (cek di Nexus UI → Repositories → repo tipe docker hosted).
> Kalau Docker lokal menolak push ke Nexus HTTP (bukan HTTPS), tambahkan Nexus ke `insecure-registries` di Docker daemon config (`daemon.json`).

Setelah image ter-push, update manifest Deployment (`image:` di container `jenkins`) lalu:
```bash
kubectl apply -f jenkins-all.yaml
```

Verifikasi Docker jalan di dalam pod:
```bash
kubectl exec -n jenkins -it deploy/jenkins -c jenkins -- docker info
```

---

## 3. Integrasi Gitea (Read-only)

### Credential

- **Username/Password** (untuk clone) — ID: `jenkins-secret-gitea`
- **Secret Text** (API token, untuk dropdown Active Choices) — ID: `gitea-api-token`
  - Generate token di Gitea: Settings → Applications → scope `read:repository`

### Parameter Job (Active Choices Plugin)

Job Pipeline generik (`k8s-uat-deploy`), parameter:

**REPO_URL** (Active Choices Parameter, Single Select, Enable filters ✓)
```groovy
import com.cloudbees.plugins.credentials.CredentialsProvider
import org.jenkinsci.plugins.plaincredentials.StringCredentials
import jenkins.model.Jenkins
import groovy.json.JsonSlurper

def creds = CredentialsProvider.lookupCredentials(
    StringCredentials.class, Jenkins.instance, null, null
).find { it.id == 'gitea-api-token' }
def token = creds.secret.plainText

def giteaUrl = "http://10.3.1.67:8080"
def url = new URL("${giteaUrl}/api/v1/repos/search?limit=50&token=${token}")
def json = new JsonSlurper().parse(url)

def repos = []
json.data.each { repo -> repos.add(repo.clone_url) }
return repos
```

**BRANCH** (Active Choices Reactive Parameter, referenced ke `REPO_URL`)
```groovy
import com.cloudbees.plugins.credentials.CredentialsProvider
import org.jenkinsci.plugins.plaincredentials.StringCredentials
import jenkins.model.Jenkins
import groovy.json.JsonSlurper

def creds = CredentialsProvider.lookupCredentials(
    StringCredentials.class, Jenkins.instance, null, null
).find { it.id == 'gitea-api-token' }
def token = creds.secret.plainText

if (REPO_URL == null || REPO_URL.toString().trim() == "") {
    return ["-- pilih repo dulu --"]
}
def parts = REPO_URL.toString().replace(".git", "").split("/")
def repoName = parts[-1]
def owner = parts[-2]

def url = new URL("http://10.3.1.67:8080/api/v1/repos/${owner}/${repoName}/branches?token=${token}")
def json = new JsonSlurper().parse(url)

def branches = []
json.each { b -> branches.add(b.name) }
return branches
```

**APP_ID** (String Parameter biasa) — nama aplikasi/deployment target di k8s

### Script Approval yang dibutuhkan

Di **Manage Jenkins → In-process Script Approval**, approve signature berikut (muncul bertahap saat script pertama kali dijalankan):
- `staticMethod jenkins.model.Jenkins getInstance`
- `staticMethod com.cloudbees.plugins.credentials.CredentialsProvider lookupCredentials ...`
- `method org.jenkinsci.plugins.plaincredentials.StringCredentials getSecret`
- `method hudson.util.Secret getPlainText`
- `method com.cloudbees.plugins.credentials.common.IdCredentials getId`

### Konfigurasi Pipeline (job config)

- **Definition**: Pipeline script from SCM
- **SCM**: Git
- **Repository URL**: `${REPO_URL}`
- **Credentials**: `jenkins-secret-gitea`
- **Branch Specifier**: `*/${BRANCH}`
- **Script Path**: `k8s/ci/Jenkinsfile`

> Setiap repo yang ingin memakai job generik ini **wajib** punya file di path `k8s/ci/Jenkinsfile`.

---

## 4. Jenkinsfile Template (`k8s/ci/Jenkinsfile`)

```groovy
pipeline {
    agent any

    parameters {
        string(name: 'APP_ID', defaultValue: '', description: 'Nama aplikasi / deployment target')
    }

    environment {
        NEXUS_REGISTRY = "10.1.40.51:PORT_NEXUS"
        IMAGE_NAME     = "${NEXUS_REGISTRY}/${params.APP_ID}"
        IMAGE_TAG      = "${env.BUILD_NUMBER}"
        K8S_NAMESPACE  = "default"
    }

    stages {
        stage('Checkout Info') {
            steps {
                echo "Building APP_ID: ${params.APP_ID}"
                sh 'git log -1 --oneline || true'
            }
        }

        stage('Build') {
            steps {
                sh 'mvn clean package -DskipTests'
            }
        }

        stage('Build Docker Image') {
            steps {
                sh "docker build -t ${IMAGE_NAME}:${IMAGE_TAG} ."
            }
        }

        stage('Push to Nexus') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'nexus-docker-credentials',
                    usernameVariable: 'REG_USER',
                    passwordVariable: 'REG_PASS'
                )]) {
                    sh """
                        echo \$REG_PASS | docker login ${NEXUS_REGISTRY} -u \$REG_USER --password-stdin
                        docker push ${IMAGE_NAME}:${IMAGE_TAG}
                    """
                }
            }
        }

        stage('Deploy to Kubernetes') {
            steps {
                sh """
                    kubectl set image deployment/${params.APP_ID} \
                        ${params.APP_ID}=${IMAGE_NAME}:${IMAGE_TAG} \
                        -n ${K8S_NAMESPACE}
                """
            }
        }

        stage('Verify Rollout') {
            steps {
                sh "kubectl rollout status deployment/${params.APP_ID} -n ${K8S_NAMESPACE} --timeout=120s"
            }
        }
    }

    post {
        always {
            sh "docker logout ${NEXUS_REGISTRY} || true"
        }
        success {
            echo "Deploy ${params.APP_ID} build #${env.BUILD_NUMBER} berhasil ke Nexus."
        }
        failure {
            echo "Deploy ${params.APP_ID} build #${env.BUILD_NUMBER} GAGAL."
        }
    }
}
```

Credential Nexus untuk push image (beda dari credential Gitea):
- Kind: Username/Password
- ID: `nexus-docker-credentials`
- Username: `admin`, Password: `admin123` *(sarankan ganti password default ini)*

---

## 5. Trigger Build dari Aplikasi Eksternal

Generate API Token: Jenkins → user profile → **Configure → API Token → Add new Token**

### Ambil crumb (anti-CSRF) lalu trigger build

```bash
CRUMB=$(curl -s "http://10.1.40.51:31080/crumbIssuer/api/json" \
  --user "USERNAME:API_TOKEN" | jq -r '.crumb')

curl -X POST "http://10.1.40.51:31080/job/k8s-uat-deploy/buildWithParameters" \
  --user "USERNAME:API_TOKEN" \
  -H "Jenkins-Crumb: $CRUMB" \
  --data-urlencode "REPO_URL=http://10.3.1.67:8080/BCALifeApps/BIMA.git" \
  --data-urlencode "BRANCH=main" \
  --data-urlencode "APP_ID=bima"
```

---

## Catatan & TODO

- [ ] Isi port Docker connector Nexus yang sebenarnya (ganti semua `PORT_NEXUS` di dokumen ini)
- [ ] Ganti password default Nexus (`admin123`)
- [ ] Pastikan nama deployment k8s sama dengan `APP_ID`, atau buat mapping kalau beda
- [ ] `docker:24-dind` dengan `privileged: true` punya risiko keamanan lebih tinggi — pertimbangkan Kaniko untuk build image tanpa privileged container di kemudian hari
- [ ] Setup handling file `.env` (belum diputuskan: File Parameter vs Secret File Credential)