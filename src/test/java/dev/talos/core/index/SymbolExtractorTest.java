package dev.talos.core.index;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SymbolExtractorTest {

    @Test
    void extractsJavaTypesAndMethodsWithLineEvidence() {
        String source = """
                package demo;

                public final class RetrocatsService {
                    private int ignoredField;

                    RetrocatsService(String name) {
                    }

                    String buildEncore() {
                        return "Encore";
                    }

                    public String buildSetlist(String city) {
                        return city;
                    }
                }

                interface TourRepository {
                    void saveConcert();
                }
                """;

        List<SymbolHit> hits = SymbolExtractor.extract("src/main/java/demo/RetrocatsService.java", source);

        assertTrue(hits.stream().anyMatch(hit ->
                hit.symbol().equals("RetrocatsService")
                        && hit.kind() == SymbolKind.CLASS
                        && hit.lineStart() == 3
                        && hit.path().equals("src/main/java/demo/RetrocatsService.java")));
        assertTrue(hits.stream().anyMatch(hit ->
                hit.symbol().equals("buildSetlist")
                        && hit.kind() == SymbolKind.METHOD
                        && hit.lineStart() == 13));
        assertTrue(hits.stream().anyMatch(hit ->
                hit.symbol().equals("buildEncore")
                        && hit.kind() == SymbolKind.METHOD
                        && hit.lineStart() == 9));
        assertTrue(hits.stream().anyMatch(hit ->
                hit.symbol().equals("saveConcert")
                        && hit.kind() == SymbolKind.METHOD));
        assertFalse(hits.stream().anyMatch(hit ->
                hit.symbol().equals("RetrocatsService")
                        && hit.kind() == SymbolKind.METHOD),
                "constructors must not be accidentally classified as ordinary methods");
        assertTrue(hits.stream().anyMatch(hit ->
                hit.symbol().equals("TourRepository")
                        && hit.kind() == SymbolKind.INTERFACE
                        && hit.lineStart() == 18));
    }

    @Test
    void extractsJavaScriptAndPythonSymbols() {
        List<SymbolHit> jsHits = SymbolExtractor.extract("src/site/app.js", """
                export class StageDirector {
                }
                export function animateHero() {
                }
                const ignored = 1;
                """);
        assertTrue(jsHits.stream().anyMatch(hit -> hit.symbol().equals("StageDirector")
                && hit.kind() == SymbolKind.CLASS));
        assertTrue(jsHits.stream().anyMatch(hit -> hit.symbol().equals("animateHero")
                && hit.kind() == SymbolKind.FUNCTION));

        List<SymbolHit> pyHits = SymbolExtractor.extract("tools/catalog.py", """
                class AlbumCatalog:
                    pass

                def load_tracks():
                    return []
                """);
        assertTrue(pyHits.stream().anyMatch(hit -> hit.symbol().equals("AlbumCatalog")
                && hit.kind() == SymbolKind.CLASS));
        assertTrue(pyHits.stream().anyMatch(hit -> hit.symbol().equals("load_tracks")
                && hit.kind() == SymbolKind.FUNCTION));
    }

    @Test
    void extractsTypeScriptAndJvmAdjacentSymbols() {
        List<SymbolHit> tsHits = SymbolExtractor.extract("src/site/stage.ts", """
                export interface StageProps {
                    title: string;
                }
                export const driveStage = () => {};
                """);
        assertTrue(tsHits.stream().anyMatch(hit -> hit.symbol().equals("StageProps")
                && hit.kind() == SymbolKind.INTERFACE));
        assertTrue(tsHits.stream().anyMatch(hit -> hit.symbol().equals("driveStage")
                && hit.kind() == SymbolKind.FUNCTION));

        List<SymbolHit> kotlinHits = SymbolExtractor.extract("src/main/kotlin/demo/StageRouter.kt", """
                package demo

                class StageRouter {
                    fun routeStage() = Unit
                }
                """);
        assertTrue(kotlinHits.stream().anyMatch(hit -> hit.symbol().equals("StageRouter")
                && hit.kind() == SymbolKind.CLASS));
    }

    @Test
    void ignoresNonCodeFilesAndCommentOnlySymbols() {
        List<SymbolHit> markdown = SymbolExtractor.extract("README.md", "class FakeService {}\n");
        assertTrue(markdown.isEmpty());

        List<SymbolHit> java = SymbolExtractor.extract("src/Fake.java", """
                // public class CommentOnlyService {}
                /*
                 * public class BlockCommentService {}
                 */
                public class RealService {}
                """);
        assertFalse(java.stream().anyMatch(hit -> hit.symbol().equals("CommentOnlyService")));
        assertFalse(java.stream().anyMatch(hit -> hit.symbol().equals("BlockCommentService")));
        assertTrue(java.stream().anyMatch(hit -> hit.symbol().equals("RealService")));
    }

    @Test
    void commentTokensInsideStringLiteralsDoNotSuppressSymbols() {
        List<SymbolHit> js = SymbolExtractor.extract("src/site/app.js", """
                const url = "http://example.test"; export function animateHero() {}
                const block = "/* not a block comment"; export function afterBlockLiteral() {}
                const line = "// not a line comment"; export const driveStage = () => {};
                """);

        assertTrue(js.stream().anyMatch(hit -> hit.symbol().equals("animateHero")),
                "line comment marker inside URL string must not truncate later JS symbols");
        assertTrue(js.stream().anyMatch(hit -> hit.symbol().equals("afterBlockLiteral")),
                "block comment marker inside string must not enter block-comment state");
        assertTrue(js.stream().anyMatch(hit -> hit.symbol().equals("driveStage")),
                "line comment marker inside string must not truncate arrow-function symbols");
    }

    @Test
    void codeLikeStringLiteralContentDoesNotCreatePhantomSymbols() {
        List<SymbolHit> js = SymbolExtractor.extract("src/site/app.js", """
                const template = "export function fake() {}";
                const html = '<script>class PhantomStage {}</script>';
                export function realStage() {}
                """);
        assertFalse(js.stream().anyMatch(hit -> hit.symbol().equals("fake")),
                "function declarations inside string literals are not real symbols");
        assertFalse(js.stream().anyMatch(hit -> hit.symbol().equals("PhantomStage")),
                "class declarations inside string literals are not real symbols");
        assertTrue(js.stream().anyMatch(hit -> hit.symbol().equals("realStage")));

        List<SymbolHit> java = SymbolExtractor.extract("src/main/java/demo/RealService.java", """
                package demo;

                class RealService {
                    String generated = "public class FakeService {}";
                    String method = "String fakeMethod() {}";
                    String buildSetlist() {
                        return generated;
                    }
                }
                """);
        assertFalse(java.stream().anyMatch(hit -> hit.symbol().equals("FakeService")));
        assertFalse(java.stream().anyMatch(hit -> hit.symbol().equals("fakeMethod")));
        assertTrue(java.stream().anyMatch(hit -> hit.symbol().equals("RealService")));
        assertTrue(java.stream().anyMatch(hit -> hit.symbol().equals("buildSetlist")));
    }
}
