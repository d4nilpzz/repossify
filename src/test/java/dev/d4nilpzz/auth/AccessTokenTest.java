package dev.d4nilpzz.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AccessTokenTest {

    private AccessToken token(List<String> permissions, AccessToken.Route... routes) {
        return new AccessToken(1, "PERSISTENT", "ci", "hash", "test", permissions, List.of(routes));
    }

    @Test
    void managerPassesEverything() {
        AccessToken manager = token(List.of("M"));
        assertTrue(manager.isManager);
        assertTrue(manager.hasAccess("/repo/private/anything", RoutePermission.WRITE));
        assertTrue(manager.hasAccess("/api/tokens", RoutePermission.READ));
    }

    @Test
    void writeImpliesRead() {
        AccessToken deploy = token(List.of(), new AccessToken.Route("/repo/releases", "w"));
        assertTrue(deploy.hasAccess("/repo/releases/com/example/demo/1.0/demo-1.0.jar", RoutePermission.WRITE));
        assertTrue(deploy.hasAccess("/repo/releases/com/example/demo/1.0/demo-1.0.jar", RoutePermission.READ));
    }

    @Test
    void readDoesNotImplyWrite() {
        AccessToken reader = token(List.of(), new AccessToken.Route("/repo/private", "r"));
        assertTrue(reader.hasAccess("/repo/private/com/example/demo-1.0.jar", RoutePermission.READ));
        assertFalse(reader.hasAccess("/repo/private/com/example/demo-1.0.jar", RoutePermission.WRITE));
    }

    @Test
    void deployTokenScopedToAnArtifactCanDeployIt() {
        // The regression this guards: the PUT handler used to check the literal prefix
        // "/repo" first, which no scoped route can ever match, so only managers could deploy.
        AccessToken scoped = token(List.of(), new AccessToken.Route("/repo/releases/com/example/demo", "w"));
        assertTrue(scoped.hasAccess("/repo/releases/com/example/demo/1.0/demo-1.0.jar", RoutePermission.WRITE));
    }

    @Test
    void routesMatchOnSegmentBoundaries() {
        AccessToken scoped = token(List.of(), new AccessToken.Route("/repo/releases/com/foo", "w"));

        assertTrue(scoped.hasAccess("/repo/releases/com/foo", RoutePermission.WRITE));
        assertTrue(scoped.hasAccess("/repo/releases/com/foo/bar/1.0/bar-1.0.jar", RoutePermission.WRITE));
        // A sibling whose name merely starts with the granted path must not be covered.
        assertFalse(scoped.hasAccess("/repo/releases/com/foobar/1.0/foobar-1.0.jar", RoutePermission.WRITE));
    }

    @Test
    void trailingSlashInRouteIsIgnored() {
        AccessToken scoped = token(List.of(), new AccessToken.Route("/repo/releases/", "w"));
        assertTrue(scoped.hasAccess("/repo/releases/com/example/demo-1.0.jar", RoutePermission.WRITE));
        assertFalse(scoped.hasAccess("/repo/private/com/example/demo-1.0.jar", RoutePermission.WRITE));
    }

    @Test
    void rootRouteGrantsEverythingBelowIt() {
        AccessToken wide = token(List.of(), new AccessToken.Route("/", "r"));
        assertTrue(wide.hasAccess("/repo/releases/anything", RoutePermission.READ));
        assertFalse(wide.hasAccess("/repo/releases/anything", RoutePermission.WRITE));
    }

    @Test
    void tokenWithoutRoutesHasNoAccess() {
        AccessToken empty = token(List.of());
        assertFalse(empty.hasAccess("/repo/releases/demo.jar", RoutePermission.READ));
        assertFalse(empty.hasAccess(null, RoutePermission.READ));
    }

    @Test
    void unparseableRoutePermissionGrantsNothing() {
        AccessToken broken = token(List.of(), new AccessToken.Route("/repo/releases", "xyz"));
        assertFalse(broken.hasAccess("/repo/releases/demo.jar", RoutePermission.READ));
    }
}
