package dev.talos.core.ingest;

import dev.talos.spi.types.SourceFormat;
import dev.talos.spi.types.SourceType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for {@link CodeBlockSplitter} - the structural block
 * splitter for source code files (brace-based, indent-based, blank-line).
 */
class CodeBlockSplitterTest {

    // ═══════════════════════════════════════════════════════════════════════
    //  Null / empty / null-format edge cases
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void split_nullContent_returnsEmpty() {
        assertEquals(List.of(), CodeBlockSplitter.split(null, SourceFormat.JAVA));
    }

    @Test
    void split_emptyContent_returnsEmpty() {
        assertEquals(List.of(), CodeBlockSplitter.split("", SourceFormat.JAVA));
    }

    @Test
    void split_nullFormat_fallsBackToBlankLineGroups() {
        String content = "block one\n\n\nblock two";
        List<String> blocks = CodeBlockSplitter.split(content, null);
        assertEquals(2, blocks.size());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Net brace depth
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class NetBraceDepthTests {

        @Test
        void simpleBraces() {
            assertEquals(1, CodeBlockSplitter.netBraceDepth("{"));
            assertEquals(-1, CodeBlockSplitter.netBraceDepth("}"));
            assertEquals(0, CodeBlockSplitter.netBraceDepth("{}"));
        }

        @Test
        void bracesInStringLiteral_ignored() {
            assertEquals(0, CodeBlockSplitter.netBraceDepth("String s = \"{ }\";"));
        }

        @Test
        void bracesInCharLiteral_ignored() {
            assertEquals(0, CodeBlockSplitter.netBraceDepth("char c = '{';"));
        }

        @Test
        void bracesInLineComment_ignored() {
            assertEquals(0, CodeBlockSplitter.netBraceDepth("// { not counted }"));
        }

        @Test
        void bracesInBlockComment_ignored() {
            assertEquals(0, CodeBlockSplitter.netBraceDepth("/* { } */"));
        }

        @Test
        void escapedQuoteInString_doesNotEndString() {
            assertEquals(0, CodeBlockSplitter.netBraceDepth("String s = \"escaped \\\" { brace\";"));
        }

        @Test
        void mixedBracesAndCode() {
            assertEquals(1, CodeBlockSplitter.netBraceDepth("public void foo() {"));
            assertEquals(-1, CodeBlockSplitter.netBraceDepth("    }"));
        }

        @Test
        void emptyLine_zeroDepth() {
            assertEquals(0, CodeBlockSplitter.netBraceDepth(""));
        }

        @Test
        void nestedBraces() {
            assertEquals(2, CodeBlockSplitter.netBraceDepth("if (x) { if (y) {"));
            assertEquals(-2, CodeBlockSplitter.netBraceDepth("    }}"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Brace-based strategy
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class BraceBasedTests {

        @Test
        void javaFile_preambleSeparatedFromClass() {
            String java = """
                    package com.example;
                    
                    import java.util.List;
                    
                    public class Foo {
                        public void bar() {
                            System.out.println("hello");
                        }
                    }
                    """;
            List<String> blocks = CodeBlockSplitter.splitBraceBased(java);
            assertTrue(blocks.size() >= 2,
                    "Should have preamble + class block, got " + blocks.size() + ": " + blocks);

            assertTrue(blocks.get(0).contains("package "), "First block should be the preamble");
            assertTrue(blocks.get(0).contains("import "), "Preamble should contain imports");

            String classBlock = blocks.stream()
                    .filter(b -> b.contains("class Foo"))
                    .findFirst().orElse(null);
            assertNotNull(classBlock, "Should have a block containing class Foo");
            assertTrue(classBlock.contains("bar()"), "Class block should include the method");
        }

        @Test
        void javaFile_multipleTopLevelTypes() {
            String java = """
                    class Foo {
                        void m() {}
                    }
                    
                    class Bar {
                        void n() {}
                    }
                    """;
            List<String> blocks = CodeBlockSplitter.splitBraceBased(java);
            assertTrue(blocks.size() >= 2,
                    "Two top-level classes should produce at least 2 blocks, got " + blocks.size());
        }

        @Test
        void singleClassNoMethods_producesAtLeastOneBlock() {
            String java = "public class Empty {}";
            List<String> blocks = CodeBlockSplitter.splitBraceBased(java);
            assertFalse(blocks.isEmpty());
            assertTrue(blocks.stream().anyMatch(b -> b.contains("class Empty")));
        }

        @Test
        void javadocBeforeClass_staysWithClass() {
            String java = """
                    package com.ex;
                    
                    /** This is a Javadoc comment. */
                    public class Documented {
                        int x;
                    }
                    """;
            List<String> blocks = CodeBlockSplitter.splitBraceBased(java);
            String classBlock = blocks.stream()
                    .filter(b -> b.contains("class Documented"))
                    .findFirst().orElse(null);
            assertNotNull(classBlock);
        }

        @Test
        void annotationBeforeClass_startsNewBlock() {
            String java = """
                    package com.ex;
                    
                    @Deprecated
                    public class Old {
                        void m() {}
                    }
                    
                    @SuppressWarnings("all")
                    public class New {
                        void n() {}
                    }
                    """;
            List<String> blocks = CodeBlockSplitter.splitBraceBased(java);
            assertTrue(blocks.size() >= 2,
                    "Annotated classes should produce separate blocks, got " + blocks.size());
        }

        @Test
        void stringLiteralWithBraces_doesNotBreakDepthTracking() {
            String java = """
                    class Foo {
                        String json = "{ \\"key\\": \\"value\\" }";
                        void bar() {
                            System.out.println(json);
                        }
                    }
                    """;
            List<String> blocks = CodeBlockSplitter.splitBraceBased(java);
            assertFalse(blocks.isEmpty());
            String classBlock = blocks.stream()
                    .filter(b -> b.contains("class Foo"))
                    .findFirst().orElse(null);
            assertNotNull(classBlock, "Foo should be in one block");
            assertTrue(classBlock.contains("bar()"), "Method should be in same block as class");
        }

        @Test
        void bracesInComments_doesNotBreakDepthTracking() {
            String java = """
                    class Foo {
                        // This line has a { brace in a comment
                        /* And this one too: } */
                        void bar() {}
                    }
                    """;
            List<String> blocks = CodeBlockSplitter.splitBraceBased(java);
            String classBlock = blocks.stream()
                    .filter(b -> b.contains("class Foo"))
                    .findFirst().orElse(null);
            assertNotNull(classBlock);
            assertTrue(classBlock.contains("bar()"));
        }

        @Test
        void emptyFileBody_safetyFallback() {
            String java = "";
            List<String> blocks = CodeBlockSplitter.splitBraceBased(java);
            assertFalse(blocks.isEmpty());
        }

        @Test
        void interfaceAndEnum_detected() {
            String java = """
                    interface Foo {
                        void m();
                    }
                    
                    enum Color {
                        RED, GREEN, BLUE
                    }
                    """;
            List<String> blocks = CodeBlockSplitter.splitBraceBased(java);
            assertTrue(blocks.size() >= 2,
                    "Interface and enum should be separate blocks, got " + blocks.size());
        }

        @Test
        void recordDeclaration_detected() {
            String java = """
                    package ex;
                    
                    record Point(int x, int y) {}
                    
                    record Line(Point a, Point b) {
                        double length() {
                            return Math.sqrt(1);
                        }
                    }
                    """;
            List<String> blocks = CodeBlockSplitter.splitBraceBased(java);
            assertTrue(blocks.size() >= 2,
                    "Records should produce separate blocks, got " + blocks.size());
        }

        @Test
        void kotlinFile_funAndClass() {
            String kotlin = """
                    package com.ex
                    
                    import kotlin.math.sqrt
                    
                    fun topLevel(): Int = 42
                    
                    class Foo {
                        fun bar() {
                            println("hello")
                        }
                    }
                    """;
            List<String> blocks = CodeBlockSplitter.split(kotlin, SourceFormat.KOTLIN);
            assertTrue(blocks.size() >= 2,
                    "Kotlin preamble + declarations should split, got " + blocks.size());
        }

        @Test
        void goFile_funcDeclarations() {
            String go = """
                    package main
                    
                    import "fmt"
                    
                    func hello() {
                        fmt.Println("hello")
                    }
                    
                    func world() {
                        fmt.Println("world")
                    }
                    """;
            List<String> blocks = CodeBlockSplitter.split(go, SourceFormat.GO);
            assertTrue(blocks.size() >= 2,
                    "Go functions should produce separate blocks, got " + blocks.size());
        }

        @Test
        void rustFile_implBlock() {
            String rust = """
                    use std::fmt;
                    
                    struct Point {
                        x: f64,
                        y: f64,
                    }
                    
                    impl Point {
                        fn new(x: f64, y: f64) -> Self {
                            Self { x, y }
                        }
                    }
                    """;
            List<String> blocks = CodeBlockSplitter.split(rust, SourceFormat.RUST);
            assertTrue(blocks.size() >= 2,
                    "Rust struct + impl should produce separate blocks, got " + blocks.size());
        }

        @Test
        void cppFile_includeGuards() {
            String cpp = """
                    #ifndef FOO_H
                    #define FOO_H
                    
                    #include <string>
                    
                    class Foo {
                    public:
                        void bar();
                    };
                    
                    #endif
                    """;
            List<String> blocks = CodeBlockSplitter.split(cpp, SourceFormat.C_HEADER);
            assertFalse(blocks.isEmpty());
        }

        @Test
        void gradleKts_usesBraceStrategy() {
            String gradle = """
                    plugins {
                        id("java")
                    }
                    
                    dependencies {
                        implementation("com.google:guava:31.0")
                    }
                    """;
            List<String> blocks = CodeBlockSplitter.split(gradle, SourceFormat.GRADLE_KTS);
            assertTrue(blocks.size() >= 2,
                    "Gradle blocks should separate, got " + blocks.size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Indent-based strategy (Python)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class IndentBasedTests {

        @Test
        void pythonFile_importsAndFunctions() {
            String py = """
                    import os
                    import sys
                    
                    def hello():
                        print("hello")
                    
                    def world():
                        print("world")
                    """;
            List<String> blocks = CodeBlockSplitter.splitIndentBased(py);
            assertTrue(blocks.size() >= 2,
                    "Should split preamble and functions, got " + blocks.size() + ": " + blocks);
        }

        @Test
        void pythonFile_classAndMethods() {
            String py = """
                    class Foo:
                        def __init__(self):
                            self.x = 1
                    
                        def bar(self):
                            return self.x
                    
                    class Bar:
                        pass
                    """;
            List<String> blocks = CodeBlockSplitter.splitIndentBased(py);
            assertTrue(blocks.size() >= 2,
                    "Two classes should produce at least 2 blocks, got " + blocks.size());
        }

        @Test
        void pythonFile_decorators() {
            String py = """
                    from functools import wraps
                    
                    @wraps
                    def decorated():
                        pass
                    
                    @staticmethod
                    def another():
                        pass
                    """;
            List<String> blocks = CodeBlockSplitter.splitIndentBased(py);
            assertTrue(blocks.size() >= 2,
                    "Decorators should start new blocks, got " + blocks.size());
        }

        @Test
        void pythonFile_asyncDef() {
            String py = """
                    import asyncio
                    
                    async def fetch():
                        pass
                    
                    async def process():
                        pass
                    """;
            List<String> blocks = CodeBlockSplitter.splitIndentBased(py);
            assertTrue(blocks.size() >= 2,
                    "Async defs should split, got " + blocks.size());
        }

        @Test
        void pythonFile_throughSplitDispatch() {
            String py = """
                    import os
                    
                    def main():
                        os.listdir(".")
                    """;
            List<String> blocks = CodeBlockSplitter.split(py, SourceFormat.PYTHON);
            assertFalse(blocks.isEmpty());
            assertTrue(blocks.size() >= 2, "Should get preamble + function");
        }

        @Test
        void pythonFile_onlyPreamble_returnsSingleBlock() {
            String py = "import os\nimport sys\n# just imports\n";
            List<String> blocks = CodeBlockSplitter.splitIndentBased(py);
            assertEquals(1, blocks.size(), "Only preamble should produce 1 block");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Blank-line groups (Shell, fallback)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class BlankLineGroupTests {

        @Test
        void shellScript_splitOnDoubleBlankLines() {
            String sh = """
                    #!/bin/bash
                    set -e
                    
                    
                    
                    function install() {
                        echo "installing"
                    }
                    
                    
                    
                    function cleanup() {
                        echo "cleaning"
                    }
                    """;
            List<String> blocks = CodeBlockSplitter.split(sh, SourceFormat.SHELL);
            assertTrue(blocks.size() >= 2,
                    "Double blank lines should split, got " + blocks.size());
        }

        @Test
        void blankLineGroups_singleBlankLinesKeptTogether() {
            String content = "line1\n\nline2\n\nline3";
            List<String> blocks = CodeBlockSplitter.splitBlankLineGroups(content);
            assertEquals(1, blocks.size(),
                    "Single blank lines should NOT trigger split, got " + blocks.size());
        }

        @Test
        void blankLineGroups_emptyContent_returnsOriginal() {
            List<String> blocks = CodeBlockSplitter.splitBlankLineGroups("   \n  \n ");
            assertEquals(1, blocks.size(), "Whitespace-only returns original content");
        }

        @Test
        void unknownFormat_usesBlankLineGroups() {
            String content = "line1\n\n\nline2";
            List<String> blocks = CodeBlockSplitter.split(content, SourceFormat.UNKNOWN);
            assertTrue(blocks.size() >= 2);
        }

        @Test
        void configFormat_usesBlankLineGroups() {
            String yaml = "server:\n  port: 8080\n\n\n\nlogging:\n  level: debug";
            List<String> blocks = CodeBlockSplitter.split(yaml, SourceFormat.YAML);
            assertTrue(blocks.size() >= 2,
                    "YAML with double blank lines should split");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Content preservation (no chars lost)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ContentPreservationTests {

        @Test
        void braceBased_allNonBlankLinesPreserved() {
            String java = """
                    package ex;
                    
                    class Foo {
                        void m() { int x = 1; }
                    }
                    
                    class Bar {
                        void n() {}
                    }
                    """;
            List<String> blocks = CodeBlockSplitter.splitBraceBased(java);
            String reconstructed = String.join("\n", blocks);
            for (String line : java.split("\n")) {
                if (!line.isBlank()) {
                    assertTrue(reconstructed.contains(line.trim()),
                            "Line should be preserved: " + line.trim());
                }
            }
        }

        @Test
        void indentBased_allNonBlankLinesPreserved() {
            String py = """
                    import os
                    
                    def foo():
                        pass
                    
                    def bar():
                        return 1
                    """;
            List<String> blocks = CodeBlockSplitter.splitIndentBased(py);
            String reconstructed = String.join("\n", blocks);
            for (String line : py.split("\n")) {
                if (!line.isBlank()) {
                    assertTrue(reconstructed.contains(line.trim()),
                            "Line should be preserved: " + line.trim());
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Integration: Chunker.chunk() with code files
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ChunkerIntegrationTests {

        @Test
        void javaFile_usesCodeAwareSplitting() {
            String java = """
                    package com.example;
                    
                    import java.util.List;
                    
                    public class Service {
                        private final List<String> items;
                    
                        public Service(List<String> items) {
                            this.items = items;
                        }
                    
                        public void process() {
                            for (String item : items) {
                                System.out.println(item);
                            }
                        }
                    
                        public int count() {
                            return items.size();
                        }
                    }
                    """;
            List<ParsedChunk> chunks = Chunker.chunk("src/Service.java", java, 200, 0);
            assertFalse(chunks.isEmpty());

            for (ParsedChunk c : chunks) {
                assertEquals("java", c.metadata().language());
                assertEquals(SourceType.CODE_FILE, c.metadata().sourceIdentity().type());
                assertEquals(SourceFormat.JAVA, c.metadata().sourceIdentity().format());
            }

            for (ParsedChunk c : chunks) {
                assertTrue(c.metadata().lineStart() >= 1,
                        "lineStart should be >= 1, got " + c.metadata().lineStart());
                assertTrue(c.metadata().lineEnd() >= c.metadata().lineStart(),
                        "lineEnd should >= lineStart");
            }
        }

        @Test
        void pythonFile_usesIndentBasedSplitting() {
            String py = """
                    import os
                    import sys
                    
                    def main():
                        print("Hello, World!")
                        for i in range(10):
                            print(i)
                    
                    def helper(x):
                        return x * 2
                    
                    class Config:
                        def __init__(self):
                            self.debug = False
                    """;
            List<ParsedChunk> chunks = Chunker.chunk("app.py", py, 150, 0);
            assertFalse(chunks.isEmpty());
            for (ParsedChunk c : chunks) {
                assertEquals("py", c.metadata().language());
                assertEquals(SourceType.CODE_FILE, c.metadata().sourceIdentity().type());
                assertEquals(SourceFormat.PYTHON, c.metadata().sourceIdentity().format());
            }
        }

        @Test
        void markdownFile_stillUsesMarkdownSplitting() {
            String md = """
                    # Introduction
                    Some intro text here.
                    
                    ## Details
                    More detailed content follows.
                    
                    ```java
                    public class Example {}
                    ```
                    """;
            List<ParsedChunk> chunks = Chunker.chunk("README.md", md, 60, 0);
            assertFalse(chunks.isEmpty());
            assertEquals(SourceType.DOCUMENT, chunks.get(0).metadata().sourceIdentity().type());
            assertEquals(SourceFormat.MARKDOWN, chunks.get(0).metadata().sourceIdentity().format());
        }

        @Test
        void configFile_usesBlankLineFallback() {
            String yaml = "server:\n  port: 8080\n\n\n\nlogging:\n  level: debug\n";
            List<ParsedChunk> chunks = Chunker.chunk("config.yaml", yaml, 100, 0);
            assertFalse(chunks.isEmpty());
            assertEquals(SourceType.CONFIG, chunks.get(0).metadata().sourceIdentity().type());
        }

        @Test
        void largeJavaFile_chunksAlignOnStructuralBoundaries() {
            StringBuilder sb = new StringBuilder();
            sb.append("package ex;\n\n");
            sb.append("public class Big {\n");
            for (int i = 0; i < 20; i++) {
                sb.append("    public void method").append(i).append("() {\n");
                sb.append("        // Body of method ").append(i).append("\n");
                sb.append("        int x = ").append(i).append(";\n");
                sb.append("        System.out.println(x);\n");
                sb.append("    }\n\n");
            }
            sb.append("}\n");

            List<ParsedChunk> chunks = Chunker.chunk("Big.java", sb.toString(), 300, 50);
            assertTrue(chunks.size() >= 3,
                    "Large file should produce multiple chunks, got " + chunks.size());

            String allText = chunks.stream().map(ParsedChunk::text).reduce("", String::concat);
            assertTrue(allText.contains("method0"), "method0 should appear");
            assertTrue(allText.contains("method19"), "method19 should appear");
        }

        @Test
        void javaFile_overlapPreserved() {
            String java = """
                    package ex;
                    
                    class Foo {
                        void a() { int x = 1; }
                        void b() { int y = 2; }
                        void c() { int z = 3; }
                    }
                    """;
            List<ParsedChunk> noOverlap = Chunker.chunk("Foo.java", java, 80, 0);
            List<ParsedChunk> withOverlap = Chunker.chunk("Foo.java", java, 80, 20);

            assertFalse(noOverlap.isEmpty());
            assertFalse(withOverlap.isEmpty());
        }

        @Test
        void shellFile_usesBlankLineStrategy() {
            String sh = """
                    #!/bin/bash
                    set -euo pipefail
                    
                    
                    
                    install() {
                        echo "Installing..."
                    }
                    
                    
                    
                    cleanup() {
                        echo "Cleaning up..."
                    }
                    """;
            List<ParsedChunk> chunks = Chunker.chunk("deploy.sh", sh, 200, 0);
            assertFalse(chunks.isEmpty());
            assertEquals("sh", chunks.get(0).metadata().language());
        }

        @Test
        void typescriptFile_usesBraceStrategy() {
            String ts = """
                    import { Component } from '@angular/core';
                    
                    export class AppComponent {
                        title = 'my-app';
                    
                        ngOnInit() {
                            console.log('init');
                        }
                    }
                    
                    export function helper(): number {
                        return 42;
                    }
                    """;
            List<ParsedChunk> chunks = Chunker.chunk("app.component.ts", ts, 200, 0);
            assertFalse(chunks.isEmpty());
            assertEquals(SourceFormat.TYPESCRIPT,
                    chunks.get(0).metadata().sourceIdentity().format());
        }
    }
}

