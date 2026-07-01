package dev.talos.runtime.verification;

import dev.talos.runtime.capability.StaticWebCapabilityProfile;
import dev.talos.runtime.task.TaskContract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class StaticWebPartialVerifier {

    private StaticWebPartialVerifier() {}

    static void verifyStyledWebWorkspace(
            Path root,
            List<String> primaryFiles,
            List<String> facts,
            List<String> problems
    ) {
        if (root == null || primaryFiles == null || primaryFiles.isEmpty()) return;
        String htmlFile = StaticWebSelectorAnalyzer.pickPrimary(primaryFiles, ".html", ".htm");
        if (htmlFile == null) {
            problems.add("Styled web task is missing a primary HTML file.");
            return;
        }

        String html;
        try {
            html = Files.readString(root.resolve(htmlFile));
        } catch (Exception e) {
            problems.add(htmlFile + ": could not be read for styled web verification.");
            return;
        }

        problems.addAll(StaticWebStructureVerifier.htmlStructureProblems(htmlFile, html));

        String cssFile = StaticWebSelectorAnalyzer.pickPrimary(primaryFiles, ".css");
        List<String> linkedCssOccurrences = StaticWebSelectorAnalyzer.linkedCssOccurrences(html);
        Set<String> linkedCssFiles = new LinkedHashSet<>(linkedCssOccurrences);
        Set<String> existingFileNames = StaticWebSelectorAnalyzer.existingFileNames(root);
        boolean hasInlineStyle = StaticWebStructureVerifier.hasNonBlankInlineStyle(html);
        if (linkedCssFiles.isEmpty()) {
            if (cssFile != null) {
                problems.add("HTML does not link CSS file: `" + cssFile + "`");
            } else if (!hasInlineStyle) {
                problems.add("Styled web task is missing CSS styling: no stylesheet link, CSS file, or inline <style> was found.");
            }
        }
        for (String linked : linkedCssFiles) {
            if (!existingFileNames.contains(linked)) {
                problems.add("HTML references missing CSS file: `" + linked + "`");
            }
        }
        if (hasInlineStyle) {
            facts.add(htmlFile + ": inline CSS styling is present.");
        } else if (!linkedCssFiles.isEmpty()) {
            facts.add(htmlFile + ": linked CSS stylesheet is present.");
        }
    }

    static void verifyFunctionalWebWorkspace(
            Path root,
            TaskContract contract,
            List<String> primaryFiles,
            List<String> facts,
            List<String> problems
    ) {
        if (root == null || primaryFiles == null || primaryFiles.isEmpty()) return;
        String htmlFile = StaticWebSelectorAnalyzer.pickPrimary(primaryFiles, ".html", ".htm");
        if (htmlFile == null) {
            problems.add("Functional web task is missing a primary HTML file.");
            return;
        }

        String html;
        try {
            html = Files.readString(root.resolve(htmlFile));
        } catch (Exception e) {
            problems.add(htmlFile + ": could not be read for functional web verification.");
            return;
        }

        String jsFile = StaticWebSelectorAnalyzer.pickPrimary(primaryFiles, ".js");
        List<String> linkedJsOccurrences = StaticWebSelectorAnalyzer.linkedJavaScriptOccurrences(html);
        Set<String> linkedJsFiles = new LinkedHashSet<>(linkedJsOccurrences);
        Set<String> existingFileNames = StaticWebSelectorAnalyzer.existingFileNames(root);
        boolean hasInlineScript = StaticWebStructureVerifier.hasNonBlankInlineScript(html);
        if (jsFile == null && linkedJsFiles.isEmpty() && !hasInlineScript) {
            problems.add("Functional web task is missing JavaScript behavior: no JavaScript file or inline script was found.");
            problems.add("HTML does not link a JavaScript file for functional behavior.");
        }
        for (String linked : linkedJsFiles) {
            if (!existingFileNames.contains(linked)) {
                problems.add("HTML references missing JavaScript file: `" + linked + "`");
            }
        }

        List<String> htmlIdOccurrences = StaticWebSelectorAnalyzer.htmlIdOccurrences(html);
        for (String id : StaticWebSelectorAnalyzer.duplicateValues(htmlIdOccurrences)) {
            problems.add("HTML defines duplicate IDs: `#" + id + "`");
        }
        if (StaticWebCapabilityProfile.looksCalculatorOrFormTask(contract)) {
            List<String> formProblems = StaticWebStructureVerifier.calculatorFormProblems(
                    contract.originalUserRequest(), html);
            problems.addAll(formProblems);
            if (formProblems.isEmpty()) {
                facts.add("Calculator/form static structure checks passed.");
            }
        }
    }
}
