package com.git;

import java.io.IOException;

/** Estado de sesión de GitHub compartido por toda a app (en memoria). */
public final class GitHubSession {

    private static final GitHubSession INSTANCE = new GitHubSession();

    private String token;
    private GitHubAuth.GitHubUser user;

    private GitHubSession() {
        token = TokenStore.load();
    }

    public static GitHubSession getInstance() {
        return INSTANCE;
    }

    public boolean isLoggedIn() {
        return token != null;
    }

    public String getToken() {
        return token;
    }

    public GitHubAuth.GitHubUser getUser() {
        return user;
    }

    public void setUser(GitHubAuth.GitHubUser user) {
        this.user = user;
    }

    public void login(String token, GitHubAuth.GitHubUser user) throws IOException {
        TokenStore.save(token);
        this.token = token;
        this.user = user;
    }

    public void logout() {
        TokenStore.clear();
        token = null;
        user = null;
    }
}
