package dev.d4nilpzz.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Manages access tokens in the SQLite database: creation, deletion, permissions,
 * per-route grants and authentication.
 * <p>
 * <b>Lookup strategy.</b> Secrets are stored as BCrypt hashes, which cannot be queried.
 * Rather than BCrypt-checking every row on every request, each token also stores
 * {@code secret_lookup}, the SHA-256 of its secret, which is indexed. Authentication is
 * therefore one indexed SELECT plus a single BCrypt verification. Secrets carry 128 bits
 * of entropy, so the SHA-256 index is not a meaningful attack surface, while BCrypt
 * remains the thing that actually protects the stored value.
 * <p>
 * Successful authentications are additionally cached for a short window, because a single
 * {@code mvn} invocation issues hundreds of requests and BCrypt is deliberately slow.
 */
public class TokenService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String dbUrl;

    /** secret -> token, short lived so revocations take effect quickly. */
    private final Cache<String, AccessToken> authCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(1_000)
            .build();

    public TokenService(String dbUrl) throws SQLException {
        this.dbUrl = dbUrl;
        initDb();
    }

    /**
     * Generates a cryptographically random secret. {@link java.util.UUID#randomUUID()} was
     * previously used here; it is random but only exposes 122 bits and reads as a UUID,
     * which invites treating it as an identifier rather than a credential.
     */
    public static String generateSecret() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String lookupHash(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void initDb() throws SQLException {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE TABLE IF NOT EXISTS access_tokens (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "name TEXT NOT NULL," +
                    "secret TEXT NOT NULL," +
                    "type TEXT NOT NULL," +
                    "description TEXT," +
                    "created_at TEXT NOT NULL)");

            stmt.execute("CREATE TABLE IF NOT EXISTS token_permissions (" +
                    "token_id INTEGER NOT NULL," +
                    "permission TEXT NOT NULL," +
                    "FOREIGN KEY(token_id) REFERENCES access_tokens(id))");

            stmt.execute("CREATE TABLE IF NOT EXISTS token_routes (" +
                    "token_id INTEGER NOT NULL," +
                    "path TEXT NOT NULL," +
                    "route_permission TEXT NOT NULL," +
                    "FOREIGN KEY(token_id) REFERENCES access_tokens(id))");

            // Added after 1.0.0: indexed lookup column so authentication is not a full scan.
            if (!columnExists(conn, "access_tokens", "secret_lookup")) {
                stmt.execute("ALTER TABLE access_tokens ADD COLUMN secret_lookup TEXT");
                LOGGER.info("Migrated access_tokens: added secret_lookup column");
            }

            // Not UNIQUE: an existing database may already hold duplicates, and failing the
            // index creation would take startup down. Uniqueness is enforced on insert.
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_tokens_name ON access_tokens(name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_tokens_lookup ON access_tokens(secret_lookup)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_perms_token ON token_permissions(token_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_routes_token ON token_routes(token_id)");
        }
    }

    private boolean columnExists(Connection conn, String table, String column) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, table, column)) {
            return rs.next();
        }
    }

    /* ===================== creation / mutation ===================== */

    public AccessToken createToken(String name, List<String> permissions, String secret) throws SQLException {
        return createToken(name, permissions, secret, "Generated via console");
    }

    public AccessToken createToken(String name, List<String> permissions, String secret, String description)
            throws SQLException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Token name cannot be empty");
        }
        if (tokenNameExists(name)) {
            throw new IllegalArgumentException("Token with this name already exists!");
        }

        String plainSecret = (secret == null || secret.isEmpty()) ? generateSecret() : secret;
        String hashed = BCrypt.hashpw(plainSecret, BCrypt.gensalt());
        String createdAt = Instant.now().toString();

        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            int tokenId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO access_tokens(name, secret, secret_lookup, type, description, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setString(2, hashed);
                ps.setString(3, lookupHash(plainSecret));
                ps.setString(4, "PERSISTENT");
                ps.setString(5, description);
                ps.setString(6, createdAt);
                ps.executeUpdate();

                try (ResultSet rs = ps.getGeneratedKeys()) {
                    tokenId = rs.next() ? rs.getInt(1) : -1;
                }
            }

            List<String> normalized = normalizePermissions(permissions);
            try (PreparedStatement permStmt = conn.prepareStatement(
                    "INSERT INTO token_permissions(token_id, permission) VALUES (?, ?)")) {
                for (String perm : normalized) {
                    permStmt.setInt(1, tokenId);
                    permStmt.setString(2, perm);
                    permStmt.executeUpdate();
                }
            }

            invalidateCache();
            return new AccessToken(tokenId, "PERSISTENT", name, hashed, description,
                    normalized, new ArrayList<>(), createdAt);
        }
    }

    private List<String> normalizePermissions(List<String> permissions) {
        List<String> result = new ArrayList<>();
        if (permissions == null) return result;
        for (String perm : permissions) {
            if (perm == null || perm.isBlank()) continue;
            result.add(perm.trim().toUpperCase());
        }
        return result;
    }

    public boolean tokenNameExists(String name) throws SQLException {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM access_tokens WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    public void deleteTokenByName(String name) throws SQLException {
        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            int id = requireTokenId(conn, name);

            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM token_permissions WHERE token_id = ?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM token_routes WHERE token_id = ?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM access_tokens WHERE id = ?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }
        }
        invalidateCache();
    }

    public void updateTokenPermissions(String name, List<String> permissions) throws SQLException {
        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            int id = requireTokenId(conn, name);

            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM token_permissions WHERE token_id = ?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO token_permissions(token_id, permission) VALUES(?, ?)")) {
                for (String perm : normalizePermissions(permissions)) {
                    ps.setInt(1, id);
                    ps.setString(2, perm);
                    ps.executeUpdate();
                }
            }
        }
        invalidateCache();
    }

    public void renameToken(String oldName, String newName) throws SQLException {
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("Token name cannot be empty");
        }
        if (tokenNameExists(newName)) {
            throw new IllegalArgumentException("Token with name '" + newName + "' already exists");
        }

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement("UPDATE access_tokens SET name=? WHERE name=?")) {
            ps.setString(1, newName);
            ps.setString(2, oldName);
            if (ps.executeUpdate() == 0) {
                throw new IllegalArgumentException("Token '" + oldName + "' not found");
            }
        }
        invalidateCache();
    }

    public String regenerateTokenSecret(String name) throws SQLException {
        String newSecret = generateSecret();
        String hash = BCrypt.hashpw(newSecret, BCrypt.gensalt());

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE access_tokens SET secret=?, secret_lookup=? WHERE name=?")) {
            ps.setString(1, hash);
            ps.setString(2, lookupHash(newSecret));
            ps.setString(3, name);
            if (ps.executeUpdate() == 0) {
                throw new IllegalArgumentException("Token '" + name + "' not found");
            }
        }
        invalidateCache();
        return newSecret;
    }

    public void deleteAllTokens() throws SQLException {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM token_permissions");
            stmt.executeUpdate("DELETE FROM token_routes");
            stmt.executeUpdate("DELETE FROM access_tokens");
        }
        invalidateCache();
    }

    public void addRouteToToken(String tokenName, String path, String permission) throws SQLException {
        RoutePermission parsed = RoutePermission.parse(permission);
        if (parsed == null) throw new IllegalArgumentException("Route permission must be 'r' or 'w'");

        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            int id = requireTokenId(conn, tokenName);

            // Replace an existing grant for the same path instead of stacking duplicates.
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM token_routes WHERE token_id=? AND path=?")) {
                ps.setInt(1, id);
                ps.setString(2, path);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO token_routes(token_id, path, route_permission) VALUES (?, ?, ?)")) {
                ps.setInt(1, id);
                ps.setString(2, path);
                ps.setString(3, parsed.shortcut());
                ps.executeUpdate();
            }
        }
        invalidateCache();
    }

    public void removeRouteFromToken(String tokenName, String path) throws SQLException {
        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            int id = requireTokenId(conn, tokenName);
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM token_routes WHERE token_id=? AND path=?")) {
                ps.setInt(1, id);
                ps.setString(2, path);
                ps.executeUpdate();
            }
        }
        invalidateCache();
    }

    private int requireTokenId(Connection conn, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM access_tokens WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Token '" + name + "' does not exist!");
                return rs.getInt("id");
            }
        }
    }

    /* ===================== lookup / authentication ===================== */

    /**
     * Resolves a token from its plaintext secret alone (Bearer header or session cookie).
     */
    public AccessToken getTokenBySecret(String secret) throws SQLException {
        if (secret == null || secret.isEmpty()) return null;

        AccessToken cached = authCache.getIfPresent(secret);
        if (cached != null) return cached;

        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            AccessToken token = findByLookup(conn, secret);
            if (token == null) token = findByScan(conn, secret);
            if (token != null) authCache.put(secret, token);
            return token;
        }
    }

    /**
     * Resolves a token from a name/secret pair (Basic auth). Preferred over
     * {@link #getTokenBySecret} because it never needs the migration fallback.
     */
    public AccessToken authenticate(String name, String secret) throws SQLException {
        if (name == null || name.isBlank()) return getTokenBySecret(secret);
        if (secret == null || secret.isEmpty()) return null;

        String cacheKey = name + " " + secret;
        AccessToken cached = authCache.getIfPresent(cacheKey);
        if (cached != null) return cached;

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM access_tokens WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                if (!BCrypt.checkpw(secret, rs.getString("secret"))) return null;

                AccessToken token = hydrate(conn, rs);
                backfillLookup(conn, token.id, rs.getString("secret_lookup"), secret);
                authCache.put(cacheKey, token);
                return token;
            }
        }
    }

    private AccessToken findByLookup(Connection conn, String secret) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM access_tokens WHERE secret_lookup = ?")) {
            ps.setString(1, lookupHash(secret));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (BCrypt.checkpw(secret, rs.getString("secret"))) {
                        return hydrate(conn, rs);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Fallback for tokens created before the {@code secret_lookup} column existed. Only
     * scans rows that have not been migrated, and backfills each one as it is resolved,
     * so this path empties itself out with use.
     */
    private AccessToken findByScan(Connection conn, String secret) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM access_tokens WHERE secret_lookup IS NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (BCrypt.checkpw(secret, rs.getString("secret"))) {
                    AccessToken token = hydrate(conn, rs);
                    backfillLookup(conn, token.id, null, secret);
                    return token;
                }
            }
        }
        return null;
    }

    private void backfillLookup(Connection conn, int tokenId, String existing, String secret) throws SQLException {
        if (existing != null && !existing.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE access_tokens SET secret_lookup = ? WHERE id = ?")) {
            ps.setString(1, lookupHash(secret));
            ps.setInt(2, tokenId);
            ps.executeUpdate();
        }
    }

    private AccessToken hydrate(Connection conn, ResultSet rs) throws SQLException {
        int id = rs.getInt("id");
        String type = rs.getString("type");
        String name = rs.getString("name");
        String hash = rs.getString("secret");
        String desc = rs.getString("description");
        String createdAt = rs.getString("created_at");

        List<String> permissions = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT permission FROM token_permissions WHERE token_id=?")) {
            ps.setInt(1, id);
            try (ResultSet permRs = ps.executeQuery()) {
                while (permRs.next()) permissions.add(permRs.getString("permission"));
            }
        }

        List<AccessToken.Route> routes = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT path, route_permission FROM token_routes WHERE token_id=?")) {
            ps.setInt(1, id);
            try (ResultSet routeRs = ps.executeQuery()) {
                while (routeRs.next()) {
                    routes.add(new AccessToken.Route(
                            routeRs.getString("path"), routeRs.getString("route_permission")));
                }
            }
        }

        return new AccessToken(id, type, name, hash, desc, permissions, routes,
                Objects.requireNonNullElse(createdAt, Instant.now().toString()));
    }

    /** All tokens, without secrets. Backs the token management API and the console. */
    public List<AccessToken> listTokens() throws SQLException {
        List<AccessToken> tokens = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM access_tokens ORDER BY name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) tokens.add(hydrate(conn, rs));
        }
        return tokens;
    }

    public AccessToken getTokenByName(String name) throws SQLException {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM access_tokens WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? hydrate(conn, rs) : null;
            }
        }
    }

    /**
     * Creates a default admin token when the database has none, returning its plaintext
     * secret. Returns null when tokens already exist.
     */
    public String bootstrapAdminIfEmpty() throws SQLException {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM access_tokens")) {
            if (rs.next() && rs.getInt(1) > 0) return null;
        }

        String secret = generateSecret();
        createToken("admin", List.of("M"), secret, "Bootstrapped on first start");
        return secret;
    }

    /** Drops cached authentications so permission changes take effect immediately. */
    public void invalidateCache() {
        authCache.invalidateAll();
    }
}
