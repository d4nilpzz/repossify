# Repossify API

Reference for Repossify 1.1.0.

Two surfaces exist:

- **`/repo/*`** — the Maven endpoint. This is what `mvn`, Gradle and `curl` talk to.
- **`/api/*`** — the JSON management API backing the dashboard.

---

## Authentication

Credentials may be supplied three ways:

| Form | Header | Used by |
|---|---|---|
| Basic | `Authorization: Basic base64(name:secret)` | Maven, Gradle |
| Bearer | `Authorization: Bearer <secret>` | scripts, sign-in |
| Session cookie | `repossify_session` | the dashboard |

Basic is preferred: knowing the token name makes the lookup exact. A `401` always carries
`WWW-Authenticate: Basic realm="Repossify"`, so Maven retries with the credentials from
`settings.xml` instead of failing outright.

### Permission model

A token either holds the **MANAGER** permission, which grants everything, or a list of
**routes**. Each route is a path prefix plus `r` (read) or `w` (write). Write implies read.

Routes match on path-segment boundaries, so a route for `/repo/releases/com/foo` does not
cover `/repo/releases/com/foobar`.

```
token "ci-bot"
  route /repo/releases/com/example/demo  w
```

That token may deploy and resolve everything under `com.example.demo` in `releases`, and
nothing else.

---

## Maven endpoint

### Resolve an artifact

```http
GET /repo/{repository}/{group}/{artifactId}/{version}/{file}
```

Returns the artifact with `ETag`, `Last-Modified`, `Content-Length` and `Accept-Ranges`.
Supports `If-None-Match`, `If-Modified-Since` (`304`) and `Range` (`206`).

Released artifacts are served with a long-lived `Cache-Control`; snapshots and
`maven-metadata.xml` are always revalidated.

If the artifact is absent locally and the repository has mirrors configured, they are
consulted and the result is cached.

### Browse

```http
GET /repo/
GET /repo/{repository}/{path}/
```

Returns an HTML index, or JSON when the request sends `Accept: application/json`.
Repositories the caller may not read are omitted.

### Deploy

```http
PUT /repo/{repository}/{group}/{artifactId}/{version}/{file}
Authorization: Basic ...
```

| Status | Meaning |
|---|---|
| `201` | stored |
| `401` | no or invalid credentials |
| `403` | the token has no write access to that path |
| `404` | the repository is not declared in `page.json` |
| `409` | the release already exists and redeployment is disabled |
| `507` | the repository is over its storage quota |

Checksums (`.md5`, `.sha1`, `.sha256`) are generated for every deployed file.

`maven-metadata.xml` is **only** generated when it does not already exist. A Maven or
Gradle client maintains that file itself — it downloads the current version, merges, and
uploads the result — so overwriting it would produce metadata that disagrees with the files
actually present.

Repossify never generates a POM during a deploy. A generated POM declares no dependencies,
so a consumer would resolve it, compile fine, and fail at runtime.

### Delete

```http
DELETE /repo/{repository}/{path}
Authorization: Basic ...
```

Removes a file or a directory tree, then prunes directories left empty. Requires write
access to the path.

---

## Dashboard API

### Page content

```http
GET /api/page/content
GET /api/version
```

Dashboard settings plus the repository trees the caller may see. Deployment policy and
mirror configuration are only included for managers.

### Session

```http
POST /api/auth/signin      Authorization: Bearer <secret>
POST /api/auth/signout
GET  /api/auth/me
```

`signin` sets an `HttpOnly`, `SameSite=Strict` session cookie valid for 8 hours, marked
`Secure` when the request arrived over HTTPS.

The stored secret hash is never serialized in any response.

### Tokens — manager only

```http
GET    /api/tokens
POST   /api/tokens
PUT    /api/tokens/{name}
DELETE /api/tokens/{name}
POST   /api/tokens/{name}/regenerate
POST   /api/tokens/{name}/routes
DELETE /api/tokens/{name}/routes?path=/repo/releases
```

Create:

```json
{
  "name": "ci-bot",
  "permissions": [],
  "routes": [ { "path": "/repo/releases/com/example", "permission": "w" } ]
}
```

The response carries the plaintext secret. It is stored hashed and cannot be retrieved
again.

Deleting the only remaining manager token is refused, since it would lock everyone out.

### Configuration — manager only

```http
PUT /api/config/update
GET /api/config/server
```

The body is the `page.json` shape. Repository entries accept:

| Field | Meaning |
|---|---|
| `name` | lowercase letters, digits, `.`, `-`, `_` |
| `visibility` | `PUBLIC`, `HIDDEN` or `PRIVATE` |
| `redeployment` | allow overwriting an existing release |
| `preserveSnapshots` | timestamped builds to keep per version; `0` keeps all |
| `storageQuota` | e.g. `"10GB"`; empty means unlimited |
| `proxied` | list of upstream mirrors |

A mirror:

```json
{
  "url": "https://repo.maven.apache.org/maven2",
  "store": true,
  "allowedGroups": ["com.example"],
  "connectTimeout": 3000,
  "readTimeout": 15000
}
```

`allowedGroups` empty means every group is looked up upstream. `store: false` proxies
without caching. `maven-metadata.xml` is proxied but never cached, since a stale copy would
freeze the upstream's version list.

### Search

```http
GET /api/maven/search?q=demo&limit=50&repository=releases
GET /api/maven/details/{repository}/{group}/{artifactId}
```

Results are filtered by what the caller may read.

### Statistics — manager only

```http
GET /api/statistics
GET /api/statistics/top?limit=20
```

### Console — manager only

```http
POST /api/console/exec     body: the command text
WS   /api/console/ws       streamed output
WS   /api/metrics          host CPU, memory and disk every 5s
```

### Badge

```http
GET /api/badge/latest/{repository}/{group}/{owner}/{artifact}?color=40c14a&label=demo&prefix=v&filter=1
```

Returns an SVG version badge. Only public repositories resolve; anything else reports
`unknown`, so a badge in a README cannot leak private version numbers.

### Upload and delete from the dashboard

```http
POST   /api/file/upload      multipart form
DELETE /api/file/delete?repo=releases&path=com/example/demo
```

Upload fields: `repo`, `path`, `generate_pom_file`, `maven[groupId]`, `maven[artifactId]`,
`maven[version]`, `file`. The coordinates are authoritative; a `path` that disagrees with
them is rejected.

---

## Errors

Every JSON endpoint returns the same shape:

```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Artifact not found: releases/com/example/demo/1.0.0/demo-1.0.0.jar"
}
```
