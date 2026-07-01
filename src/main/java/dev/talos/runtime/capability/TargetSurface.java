package dev.talos.runtime.capability;

public enum TargetSurface {
    NONE("none", false),
    DOCUMENT_TEXT("document text extraction", false),
    SOURCE_DERIVED_TEXT("source-derived text artifact", false),
    SELF_CONTAINED_HTML("self-contained HTML", true),
    FUNCTIONAL_WEB("functional web surface", true),
    HTML_CSS_JS("HTML/CSS/JS", false);

    private final String description;
    private final boolean allowsFunctionalPartial;

    TargetSurface(String description, boolean allowsFunctionalPartial) {
        this.description = description;
        this.allowsFunctionalPartial = allowsFunctionalPartial;
    }

    public String description() {
        return description;
    }

    public boolean allowsFunctionalPartial() {
        return allowsFunctionalPartial;
    }
}
