
<p align="center">
  <img src="https://github.com/user-attachments/assets/d1d676f9-e63d-47fb-9cb9-89c6fd1e83d8" alt="MAS Logo" width="120" height="120">
</p>

<h1 align="center">Repossify</h1>

<p align="center">
  Repossify is a Maven repository designed for the efficient management, storage, and distribution of Java artifacts. The backend is fully implemented in Java, ensuring robustness, scalability, and full compatibility with the standard Maven ecosystem.
</p>

<div align="center">
  <a href="https://repossify.dev">Website</a>
  ·
  <a href="https://repossify.dev/docs/">Official Guide</a>
  ·
  <a href="https://github.com/d4nilpzz/maven-repossify/releases">GitHub Releases</a>
  ·
  <a href="https://maven.repossify.dev">Demo</a>
  ·
  <a href="https://discord.gg/x2PuPKznA6">Discord</a>
</div>

---

<img width="1209" height="748" alt="{AA134C8B-A447-4E9B-8906-44B440355061}" src="https://github.com/user-attachments/assets/6e6321d3-332e-4751-9151-4fa1e5dcfa05" />

## Features

- Hosts releases and snapshots with correct `maven-metadata.xml`, checksums and timestamped snapshot builds
- **Mirrors upstream repositories**, so a build can point at Repossify alone instead of listing Maven Central separately
- Per-repository visibility (public, hidden, private), redeployment policy, snapshot retention and storage quotas
- Access tokens scoped to path prefixes with read or write permission, managed from the dashboard, a REST API or the console
- Conditional requests and range support, so clients revalidate instead of re-downloading and large jars resume
- Artifact search, download statistics and a browsable web dashboard

## Setup

**Prerequisites:** JDK 21.

Download the latest jar from [releases](https://github.com/d4nilpzz/maven-repossify/releases)
and place it in the directory you want Repossify to use.

Create the directory layout, the SQLite database and the configuration files:

```bash
java -jar repossify-1.1.0.jar --init
```

Then start it:

```bash
java -jar repossify-1.1.0.jar
```

On the first start with an empty database, a manager token named `admin` is generated and
printed once. Save it — it is stored hashed and cannot be recovered.

### Command line options

| Option | Description |
|---|---|
| `--init` | Create the working directory layout and exit |
| `--port`, `-p` | Override the port from `configuration.json` |
| `--hostname`, `-h` | Override the hostname |
| `--max-request-size`, `-mrs` | Maximum accepted request size, in bytes |
| `--working-directory`, `-wd` | Where Repossify keeps its data |
| `--version` | Print the version |
| `--help`, `-?` | List the options |

Flags win over `configuration.json`.

### Configuration

`configuration.json` holds the server settings: hostname, port, request size limit,
compression, CORS, HTTPS and reverse-proxy handling.

`page.json` holds the dashboard settings and the repository definitions. Both are editable
from the dashboard's configuration modal.

To serve HTTPS, place a PKCS#12 or JKS keystore in the working directory and set:

```json
{
  "ssl": {
    "enabled": true,
    "port": 8443,
    "keyStore": "keystore.p12",
    "keyStorePassword": "…",
    "keepHttp": true,
    "redirectToHttps": false
  }
}
```

Behind a reverse proxy, enable `forwardedIp` so requests are attributed to the real client
rather than the proxy.

## Publishing

```xml
<distributionManagement>
  <repository>
    <id>repossify</id>
    <url>https://maven.example.dev/repo/releases</url>
  </repository>
  <snapshotRepository>
    <id>repossify</id>
    <url>https://maven.example.dev/repo/snapshots</url>
  </snapshotRepository>
</distributionManagement>
```

with the token in `~/.m2/settings.xml`:

```xml
<server>
  <id>repossify</id>
  <username>ci-bot</username>
  <password>the-token-secret</password>
</server>
```

## Using it as your only repository

Give a repository one or more mirrors, then point Maven at it. Artifacts that are not held
locally are fetched upstream and cached:

```xml
<mirror>
  <id>repossify</id>
  <url>https://maven.example.dev/repo/central</url>
  <mirrorOf>*</mirrorOf>
</mirror>
```

## Console

Repossify reads commands on stdin, and the dashboard exposes the same console to managers.

```
generate_token <name> [<permissions>] [--secret=<secret>] [--silent]
list_tokens
token_add_route <tokenName> <path> <r|w>
repositories
repair_metadata <repository|*>     rebuild metadata after copying artifacts in by hand
prune_snapshots [<repository>]
statistics
performance
```

## Docker

```bash
docker compose up -d
```

Data lives in the `/data` volume. Read the generated admin token from the container logs.

## Building from source

```bash
mvn package
```

The dashboard lives in a separate repository. After changing it, rebuild and install it into
the backend's resources:

```bash
./scripts/sync-client.ps1
```

---

### API reference: [API_DOCS.md](API_DOCS.md) · Full docs: [repossify.dev/docs](https://repossify.dev/docs/)
