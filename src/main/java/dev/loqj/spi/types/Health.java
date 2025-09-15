package dev.loqj.spi.types;

public record Health(boolean ok, String server, boolean hasModel, String message) {
    public static Health ok(String server, boolean hasModel) {
        return new Health(true, server, hasModel, "");
    }
    public static Health down(String msg) {
        return new Health(false, "", false, msg == null ? "" : msg);
    }
}
