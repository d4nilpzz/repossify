package dev.d4nilpzz.auth;

import org.mindrot.jbcrypt.BCrypt;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * TokenService is responsible for managing access tokens in the SQLite database.
 * It supports token creation, deletion, modification, renaming, secret regeneration,
 * and retrieval based on secret. Permissions and route associations are also managed.
 */
public class TokenService {

    private final String dbUrl;

    /**
     * Constructs a TokenService instance with the given SQLite database URL.
     * Initializes the required database tables if they do not exist.
     *
     * @param dbUrl JDBC URL of the SQLite database
     * @throws SQLException if database initialization fails
     */
    public TokenService(String dbUrl) throws SQLException {
        this.dbUrl = dbUrl;
        initDb();
    }

    /**
     * Initializes the SQLite database with tables for tokens, permissions, and routes.
     *
     * @throws SQLException if any SQL error occurs during table creation
     */
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
        }
    }

    /**
     * Creates a new access token with the given name, permissions, and optional secret.
     * If no secret is provided, a random UUID-based secret is generated.
     *
     * @param name        token name must be unique
     * @param permissions list of permission strings
     * @param secret      optional secret for authentication, auto-generated if null
     * @return the created AccessToken object
     * @throws SQLException             if a database error occurs
     * @throws IllegalArgumentException if a token with the same name already exists
     */
    public AccessToken createToken(String name, List<String> permissions, String secret) throws SQLException {
        if (tokenNameExists(name)) {
            throw new IllegalArgumentException("Token with this name already exists!");
        }

        if (secret == null || secret.isEmpty()) {
            secret = UUID.randomUUID().toString().replace("-", "");
        }

        String hashed = BCrypt.hashpw(secret, BCrypt.gensalt());

        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO access_tokens(name, secret, type, description, created_at) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, name);
            ps.setString(2, hashed);
            ps.setString(3, "PERSISTENT");
            ps.setString(4, "Generated via console");
            ps.setString(5, Instant.now().toString());
            ps.executeUpdate();

            ResultSet rs = ps.getGeneratedKeys();
            int tokenId = -1;
            if (rs.next()) tokenId = rs.getInt(1);

            PreparedStatement permStmt = conn.prepareStatement(
                    "INSERT INTO token_permissions(token_id, permission) VALUES (?, ?)"
            );
            for (String perm : permissions) {
                permStmt.setInt(1, tokenId);
                permStmt.setString(2, perm.toUpperCase());
                permStmt.executeUpdate();
            }

            return new AccessToken(tokenId, "PERSISTENT", name, hashed, "Generated via console", permissions, new ArrayList<>());
        }
    }

    /**
     * Checks if a token with the given name already exists in the database.
     *
     * @param name token name to check
     * @return true if token exists, false otherwise
     * @throws SQLException if database query fails
     */
    public boolean tokenNameExists(String name) throws SQLException {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM access_tokens WHERE name = ?")) {
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    /**
     * Deletes a token by its name, including all associated permissions and routes.
     *
     * @param name token name to delete
     * @throws SQLException             if database error occurs
     * @throws IllegalArgumentException if token does not exist
     */
    public void deleteTokenByName(String name) throws SQLException {
        try (Connection conn = DriverManager.getConnection(dbUrl)) {

            PreparedStatement psId = conn.prepareStatement("SELECT id FROM access_tokens WHERE name = ?");
            psId.setString(1, name);
            ResultSet rs = psId.executeQuery();
            if (!rs.next()) {
                throw new IllegalArgumentException("Token with name '" + name + "' does not exist!");
            }
            int id = rs.getInt("id");

            PreparedStatement psPerm = conn.prepareStatement("DELETE FROM token_permissions WHERE token_id = ?");
            psPerm.setInt(1, id);
            psPerm.executeUpdate();

            PreparedStatement psRoutes = conn.prepareStatement("DELETE FROM token_routes WHERE token_id = ?");
            psRoutes.setInt(1, id);
            psRoutes.executeUpdate();

            PreparedStatement psToken = conn.prepareStatement("DELETE FROM access_tokens WHERE id = ?");
            psToken.setInt(1, id);
            psToken.executeUpdate();
        }
    }

    /**
     * Updates the permissions of an existing token.
     * Replaces any previous permissions with the new list.
     *
     * @param name        token name
     * @param permissions list of new permissions
     * @throws SQLException             if a database error occurs
     * @throws IllegalArgumentException if token does not exist
     */
    public void updateTokenPermissions(String name, List<String> permissions) throws SQLException {
        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            PreparedStatement psId = conn.prepareStatement("SELECT id FROM access_tokens WHERE name = ?");
            psId.setString(1, name);
            ResultSet rs = psId.executeQuery();
            if (!rs.next()) throw new IllegalArgumentException("Token '" + name + "' not found");
            int id = rs.getInt("id");

            PreparedStatement deleteOld = conn.prepareStatement("DELETE FROM token_permissions WHERE token_id = ?");
            deleteOld.setInt(1, id);
            deleteOld.executeUpdate();

            PreparedStatement insert = conn.prepareStatement("INSERT INTO token_permissions(token_id, permission) VALUES(?, ?)");
            for (String perm : permissions) {
                insert.setInt(1, id);
                insert.setString(2, perm);
                insert.executeUpdate();
            }
        }
    }

    /**
     * Renames an existing token to a new name.
     * The new name must be unique in the database.
     *
     * @param oldName current token name
     * @param newName new desired token name
     * @throws SQLException             if database error occurs
     * @throws IllegalArgumentException if new name already exists or old name not found
     */
    public void renameToken(String oldName, String newName) throws SQLException {
        if (tokenNameExists(newName))
            throw new IllegalArgumentException("Token with name '" + newName + "' already exists");

        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            PreparedStatement ps = conn.prepareStatement("UPDATE access_tokens SET name=? WHERE name=?");
            ps.setString(1, newName);
            ps.setString(2, oldName);
            int updated = ps.executeUpdate();
            if (updated == 0) throw new IllegalArgumentException("Token '" + oldName + "' not found");
        }
    }

    /**
     * Regenerates the secret for an existing token and returns the new secret.
     *
     * @param name token name
     * @return newly generated secret
     * @throws SQLException             if database error occurs
     * @throws IllegalArgumentException if token does not exist
     */
    public String regenerateTokenSecret(String name) throws SQLException {
        String newSecret = UUID.randomUUID().toString().replace("-", "");
        String hash = BCrypt.hashpw(newSecret, BCrypt.gensalt());

        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            PreparedStatement ps = conn.prepareStatement("UPDATE access_tokens SET secret=? WHERE name=?");
            ps.setString(1, hash);
            ps.setString(2, name);
            int updated = ps.executeUpdate();
            if (updated == 0) throw new IllegalArgumentException("Token '" + name + "' not found");
        }
        return newSecret;
    }

    /**
     * Deletes all tokens from the database, including permissions and route associations.
     *
     * @throws SQLException if a database error occurs
     */
    public void deleteAllTokens() throws SQLException {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM token_permissions");
            stmt.executeUpdate("DELETE FROM token_routes");
            stmt.executeUpdate("DELETE FROM access_tokens");
        }
    }

    /**
     * Retrieves an access token by its secret value.
     * This method verifies the secret using BCrypt.
     *
     * @param secret token secret to search for
     * @return AccessToken object if found and secret matches, null otherwise
     * @throws SQLException if a database error occurs
     */
    public AccessToken getTokenBySecret(String secret) throws SQLException {
        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            PreparedStatement ps = conn.prepareStatement("SELECT * FROM access_tokens");
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String hash = rs.getString("secret");
                if (BCrypt.checkpw(secret, hash)) {
                    int id = rs.getInt("id");
                    String type = rs.getString("type");
                    String name = rs.getString("name");
                    String desc = rs.getString("description");

                    List<String> permissions = new ArrayList<>();
                    PreparedStatement permStmt = conn.prepareStatement(
                            "SELECT permission FROM token_permissions WHERE token_id=?");
                    permStmt.setInt(1, id);
                    ResultSet permRs = permStmt.executeQuery();
                    while (permRs.next()) permissions.add(permRs.getString("permission"));

                    List<AccessToken.Route> routes = new ArrayList<>();
                    PreparedStatement routeStmt = conn.prepareStatement(
                            "SELECT path, route_permission FROM token_routes WHERE token_id=?");
                    routeStmt.setInt(1, id);
                    ResultSet routeRs = routeStmt.executeQuery();
                    while (routeRs.next()) {
                        AccessToken.Route r = new AccessToken.Route();
                        r.path = routeRs.getString("path");
                        r.routePermission = routeRs.getString("route_permission");
                        routes.add(r);
                    }

                    return new AccessToken(id, type, name, hash, desc, permissions, routes);
                }
            }
        }
        return null;
    }

    /**
     * Adds a route to an existing token.
     *
     * @param tokenName the name of the token to add the route to
     * @param path the path to add to the token's routes
     * @param permission the permission required to access the route
     * @throws SQLException if a database error occurs
     */
    public void addRouteToToken(String tokenName, String path, String permission) throws SQLException {
        try (Connection conn = DriverManager.getConnection(dbUrl)) {

            PreparedStatement psId = conn.prepareStatement("SELECT id FROM access_tokens WHERE name=?");
            psId.setString(1, tokenName);
            ResultSet rs = psId.executeQuery();
            if (!rs.next()) throw new IllegalArgumentException("Token not found");
            int id = rs.getInt("id");

            PreparedStatement psRoute = conn.prepareStatement(
                    "INSERT INTO token_routes(token_id, path, route_permission) VALUES (?, ?, ?)"
            );
            psRoute.setInt(1, id);
            psRoute.setString(2, path);
            psRoute.setString(3, permission.toUpperCase());
            psRoute.executeUpdate();
        }
    }

}
