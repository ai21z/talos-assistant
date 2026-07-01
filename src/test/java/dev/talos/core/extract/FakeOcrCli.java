package dev.talos.core.extract;

public final class FakeOcrCli {
    private FakeOcrCli() {}

    public static void main(String[] args) {
        System.out.println("OCR fixture visible text");
        System.out.println("API_TOKEN=t267-token-should-not-appear");
    }
}
