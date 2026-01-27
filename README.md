Here is a professional and comprehensive **README.md** in English, designed to showcase your repository as a high-quality "Infrastructure-as-Code" toolkit for local development.

---

# 🐳 DevStack: Ready-to-Use Local Infrastructure

This repository is a curated collection of **Docker Compose** configurations and **Dockerfiles** designed to spin up a complete development ecosystem on your local machine (WSL, Linux, Mac, or Windows).

Whether you are working on Java Spring Boot microservices, Node.js applications, or learning new languages like Go and Rust, these stacks provide the necessary backend services with zero manual installation.

> **Core Philosophy:** Modular, portable, and plug-and-play. One folder, one command, one infrastructure.

---

## 🛠️ Service Catalog

The following services are pre-configured to work independently. Each folder contains the specific configuration files and persistent data mappings required for that service.

| Category               | Service Folder   | Default Port | Description                                                              |
| -----------------------|------------------|--------------|--------------------------------------------------------------------------|
| **Config & Discovery** | `consul/`        | `8500`       | HashiCorp Consul for Service Discovery & Key-Value (KV) Store.           |
| **Messaging**          | `kafka/`         | `9092`       | Apache Kafka for event-driven architectures and message streaming.       |
| **Monitoring**         | `elastic-stack/` | `5601`       | Full ELK Stack (Elasticsearch, Logstash, Kibana) + Beats for logging.    |
| **Automation**         | `jenkins/`       | `8080`       | Jenkins CI/CD server for local pipeline simulations.                     |
| **Security**           | `vault/`         | `8200`       | HashiCorp Vault for managing secrets and sensitive data.                 |
| **Code Quality**       | `sonar/`         | `9000`       | SonarQube for static code analysis and security auditing.                |
| **Web Servers**        | `tomcat/`        | `808x`       | Apache Tomcat containers (Versions 8.5, 9, and 10 available).            |

---

## 🚀 Getting Started

Each service is self-contained. To start a service, navigate to its directory and use Docker Compose.

### 1. Start a Service

```bash
# Example: Starting the Kafka stack
cd kafka
docker compose up -d

```

### 2. Stop a Service

```bash
docker compose down

```

### 3. View Logs

```bash
docker compose logs -f

```

---

## 📋 System Requirements

* **Docker & Docker Compose:** Recommended use of Docker Desktop with **WSL 2** integration for Windows users.
* **Memory:** At least **16GB RAM** is recommended if you plan to run multiple stacks (ELK and SonarQube are resource-intensive).
* **Storage:** High-speed SSD is preferred for the database and search engine volumes.

---

### Clean Up Resources

To remove unused containers, networks, and dangling images to save disk space:

```bash
docker system prune -a --volumes

```

### Network Isolation

Each folder creates its own Docker network. To allow services in different folders to communicate, consider using a **shared external network**:

```bash
docker network create dev-network

```

---