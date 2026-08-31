package io.github.softcane.workbook.proxy;

public enum ProxyProvider {
    CODEX("codex", "/backend-api/codex/responses"),
    CLAUDE("claude", "/v1/messages");

    private final String wireName;
    private final String path;

    ProxyProvider(String wireName, String path) {
        this.wireName = wireName;
        this.path = path;
    }

    public String wireName() { return wireName; }

    public static ProxyProvider forPath(String path) {
        for (var provider : values()) if (provider.path.equals(path)) return provider;
        throw new IllegalArgumentException("Route is not intercepted: " + path);
    }
}
