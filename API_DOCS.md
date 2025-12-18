# Repossify API docs
This is the API documentation for the Repossify project.

---

### PAGE

Serves the main HTML page (SPA) `index.html` from the classpath.

```http request
GET /
```
---

Returns the page configuration and repositories as JSON.

```http request
GET /api/page
```

`Response`
```json
{
  "title": "Repossify",
  "author": "author",
  "group_id": "com.example",
  "description": "A powerful maven repository.",
  "avatar_url": "/public/repossify.png",
  "links": [
    {
      "url": "https://github.com/d4nilpzz/repossify",
      "icon": "github"
    }
  ],
  "repositories": []
}
```

---
### AUTH

Signs in using an existing access token and creates a session cookie.

```http request
POST /auth/signin
Authorization: Bearer <token>
```

`Response`
```json
{
  "id": "token-id",
  "owner": "user",
  "scopes": [],
  "expiresAt": "2025-01-01T00:00:00Z"
}
```

Sets an HTTP-only cookie: repossify_session

Session TTL: 30 minutes

---

Signs out and deletes the session cookie.

```http request
POST /auth/signout
```

---

Returns the authenticated user.

```http request
GET /auth/me
```

---

### CONFIG

Updates the page configuration.

```http request
PUT /config/update
```