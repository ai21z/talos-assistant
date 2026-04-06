package dev.talos.spi.types;

public record ModelRef(String backend, String name, Integer dims, String note) {
    public static ModelRef of(String backend, String name) {
        return new ModelRef(backend, name, null, "");
    }
}
