package dev.talos.runtime.intent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One canonical matcher for mutation action families used by intent planners. */
final class CanonicalMutationClauseMatcher {

    private static final Pattern WORKSPACE_SOURCE_PREFIX = Pattern.compile(
            "(?iu)^[\\s:,-]*(?:(?:exactly|only)\\s+)*"
                    + "(?:(?:the|a|an|current|existing|local)\\s+)*"
                    + "(?:(?:path|file|directory|folder)\\s+)?$");
    private static final Pattern WORKSPACE_TARGET_PREFIX = Pattern.compile(
            "(?iu)^[\\s:,-]*(?:(?:named|called|as)\\s+)?"
                    + "(?:(?:the|a|an|current|existing|local)\\s+)*"
                    + "(?:(?:path|file|directory|folder)\\s+)?$");
    private static final Pattern COPY_MOVE_CONNECTOR = Pattern.compile(
            "(?iu)^\\s+(?:to|into)\\s+$");
    private static final Pattern RENAME_CONNECTOR = Pattern.compile(
            "(?iu)^\\s+(?:to|as)\\s+$");
    private static final Pattern EMPTY_OPERATION_TRAILER = Pattern.compile(
            "(?iu)^\\s*(?:please|kindly)?\\s*[.!?]?\\s*$");
    private static final Pattern OVERWRITE_OPERATION_TRAILER = Pattern.compile(
            "(?iu)^\\s*(?:,?\\s*(?:and\\s+)?overwrite(?:\\s+it)?)?"
                    + "(?:\\s+(?:please|kindly))?\\s*[.!?]?\\s*$");
    private static final Pattern RECURSIVE_OPERATION_TRAILER = Pattern.compile(
            "(?iu)^\\s*(?:,?\\s*(?:and\\s+)?recursively)?"
                    + "(?:\\s+(?:please|kindly))?\\s*[.!?]?\\s*$");
    private static final Pattern COPY_OPERATION_TRAILER = Pattern.compile(
            "(?iu)^\\s*(?:(?:,?\\s*recursively)"
                    + "(?:\\s+(?:and\\s+)?overwrite(?:\\s+it)?)?"
                    + "|(?:,?\\s*(?:and\\s+)?overwrite(?:\\s+it)?)"
                    + "(?:\\s+(?:and\\s+)?recursively)?)?"
                    + "(?:\\s+(?:please|kindly))?\\s*[.!?]?\\s*$");
    private static final Pattern DIRECT_ASSISTANT_ACTION_REQUEST =
            Pattern.compile("(?iu)^\\s*(?:can|could|would)\\s+you\\s*$");
    private static final Pattern DIRECT_TOOL_ACTION_REQUEST = Pattern.compile(
            "(?iu)^\\s*use\\s+[a-z][a-z0-9_]{0,63}"
                    + "(?:\\.[a-z][a-z0-9_]{0,63})?\\s+to\\s*$");
    private static final Pattern WORKSPACE_OVERWRITE_MODIFIER_GAP =
            Pattern.compile("(?iu).*(?:\\band\\s+|,\\s*)$");
    private static final Pattern WORKSPACE_OVERWRITE_MODIFIER_SUFFIX =
            Pattern.compile("(?iu)^\\s+it\\s*[.!?]?\\s*$");

    enum Family {
        CONTENT_MUTATION,
        WORKSPACE_OPERATION
    }

    enum RestrictiveAuthority {
        NONE,
        OPTIONAL,
        NON_AUTHORITATIVE
    }

    enum ScopeKind {
        NONE,
        OPTIONAL,
        REPORTED_ADVISORY,
        PROHIBITED
    }

    private static final Pattern ACTION = Pattern.compile(
            "(?i)\\b(?:"
                    + "(?:create|make)\\s+(?:me\\s+)?"
                    + "(?:(?:a|an|the)\\s+)?(?:new\\s+)?"
                    + "(?:directories|directory|dirs|dir|folders|folder)|"
                    + "mkdir|rename|move|copy|delete|remove|rm|"
                    + "write|create|save|apply|add|put|implement|"
                    + "regenerate|generate|scaffold|build|make|"
                     + "improve|edit|update|rewrite|replace|append|refactor|"
                     + "overwrite|modify|change|fix|adjust|tweak|re-?style|"
                    + "re-?design|polish|repair|touch"
                     + ")\\b");
    private static final Pattern DIRECTORY_CONTEXT = Pattern.compile(
            "(?iu)\\b(?:directories|directory|dirs|dir|folders|folder)\\b");
    private static final Pattern DIRECT_TARGET_PREFIX = Pattern.compile(
            "(?i)^[\\s:,-]*(?:(?:the|a|an|both|current|existing|local)\\s+)*"
                    + "(?:(?:file|files)\\s+)?$");
    private static final Set<String> NOMINAL_ACTION_WORDS = Set.of(
            "the", "a", "an", "this", "that", "current", "existing", "local");
    private static final int MAX_NOMINAL_ACTION_WORD_LENGTH = 8;
    static final int MAX_FALLBACK_CLASSIFICATION_CHARACTERS = 4096;
    static final int MAX_FALLBACK_INPUT_MULTIPLIER = 2;
    private static final Pattern EXPLICIT_ACTION_PREFIX = Pattern.compile(
            "(?iu)^[\\s,:;\\-\\u2013\\u2014]*"
                    + "(?:(?:so|ok|okay|and|but|then|also|please|kindly|now|maybe|"
                    + "optionally|perhaps|possibly|potentially|optional|if|when|"
                    + "unless|as|only|needed|necessary|required|appropriate|"
                    + "applicable|possible|feasible|first|next|really|genuinely|"
                    + "definitely|actually|carefully|explicitly|finally|"
                    + "subsequently|simply|just|still)\\b"
                    + "[\\s,:;\\-\\u2013\\u2014]*)*$");
    private static final Pattern EXPLICIT_CLAUSE_BOUNDARY_PREFIX =
            Pattern.compile(
                    "(?i)[,:;\\r\\n\\u2013\\u2014][\\s,:;*\\-]*"
                            + "(?:(?:[a-z][a-z-]*ly|now|first|next)"
                            + "[\\s,:;*\\-]+){0,4}$");
    private static final Pattern TRAILING_STRUCTURAL_CONNECTOR_PREFIX =
            Pattern.compile(
                    "(?iu)\\b(?:and|but|then|also)\\b"
                            + "(?:\\s+(?:[\\p{L}][\\p{L}-]*ly|only|now|just|"
                            + "first|next|maybe|perhaps|optionally|possibly|"
                            + "potentially)){0,4}"
                            + "[\\s,:;\\-\\u2013\\u2014]*$");
    private static final Pattern EXPLICIT_MODIFIER_ONLY_PREFIX =
            Pattern.compile(
                    "(?i)^\\s*(?:(?:[a-z][a-z-]*ly|now|first|next)"
                            + "[\\s,:;-]+){1,4}$");
    private static final Pattern AUTHORITY_SCOPE_RESET =
            Pattern.compile("(?iu)\\b(?:but|then)\\b");
    private static final Pattern NON_RESETTING_SCOPE_LEAD = Pattern.compile(
            "(?iu)\\b(?:and|anything|everything|nothing)\\s*$");
    private static final Pattern OPEN_CONDITIONAL_SCOPE = Pattern.compile(
            "(?iu)\\b(?:if|when|unless)\\b"
                    + "[^;.!?\\r\\n\\u2028\\u2029]*$");
    private static final Pattern OPTIONAL_OR_CONDITIONAL = Pattern.compile(
            "(?iu)\\b(?:if|when|as|unless)\\s+"
                    + "(?:needed|necessary|required|appropriate|applicable|possible|feasible)\\b"
                    + "|\\b(?:if|when|unless)\\b"
                    + "|\\b(?:suppose|assuming)\\b"
                    + "|\\bin\\s+case(?=\\s|[,;:])"
                    + "|\\bonly\\s+(?:if|when)\\b"
                    + "|\\boptionally\\b|\\bperhaps\\b|\\bmaybe\\b"
                    + "|\\bpossibly\\b|\\bpotentially\\b"
                    + "|\\byou\\s+may\\b"
                    + "|\\bfeel\\s+free\\s+to\\b"
                    + "|\\b(?:i|we)\\s+(?:permit|allow)\\s+"
                    + "(?:you|talos)\\s+to\\b"
                    + "|\\b(?:you|talos)\\s+(?:are\\s+allowed|"
                    + "have\\s+permission)\\s+to\\b"
                    + "|\\b(?:i|we)\\s+(?:might|may|could|would)\\s+"
                    + "(?:ask|request|instruct|tell|direct|command|require)\\b");
    private static final Pattern PROPOSAL_QUESTION = Pattern.compile(
            "(?iu)\\bwhat\\s+if\\s+we\\b"
                    + "|\\b(?:should|could|would|can|may)\\s+(?:i|we|you)\\b"
                    + "|\\b(?:would|could|might)\\s+it\\s+"
                    + "(?:help|make\\s+sense|be\\s+(?:useful|better|appropriate))"
                    + "\\s+to\\b");
    private static final Pattern REPORTED_OR_ADVISORY = Pattern.compile(
            "(?iu)\\b(?:ticket|guide|policy|runbook|reviewer|verifier|report|"
                    + "requirements?|spec(?:ification)?|documentation|document|"
                    + "message|comment|note|issue|task|prompt|example)\\s+"
                    + "(?:(?:then|also|clearly|explicitly|specifically|directly|"
                    + "strongly)\\s+)?"
                    + "(?:says?|said|states?|stated|requires?|required|"
                    + "proposes?|proposed|suggests?|suggested|advises?|advised|"
                    + "directs?|directed|commands?|commanded|asks?|asked|"
                    + "instructs?|instructed|tells?|told|requests?|requested|"
                    + "recommends?|recommended|mentions?|mentioned|wrote|reads?|"
                    + "thinks?|thought|believes?|believed|notes?|noted|"
                    + "indicates?|indicated|observes?|observed|hears?|heard|"
                    + "knows?|knew|reports?|reported|claims?|claimed|"
                    + "asserts?|asserted)\\b"
                    + "|\\bconsider\\b"
                    + "|\\byou\\s+should\\b"
                    + "|\\bwhat\\s+would\\b|\\bhow\\s+would\\b"
                    + "|\\byou\\s+(?:can|could|might)\\b"
                    + "|\\bi\\s+(?:(?:would|might|may|could)\\s+)?"
                    + "(?:strongly\\s+)?(?:suggest|recommend|advise)\\b"
                    + "|\\bi\\s+propose(?:\\s+(?:that\\s+)?we)?\\b"
                    + "|\\b(?:the\\s+|a\\s+)?proposal\\s+from\\s+"
                    + "(?:the\\s+)?(?:ticket|guide|policy|runbook|reviewer|"
                    + "verifier|report|requirements?|spec(?:ification)?|"
                    + "documentation|document|message|comment|note|issue|task|"
                    + "prompt|example)\\s+(?:is|was)\\s+to\\s*$"
                    + "|\\bas\\s+(?:the\\s+)?(?:ticket|guide|policy|runbook|reviewer|"
                    + "verifier|report|spec|documentation)\\s+(?:says?|suggests?|"
                    + "proposes?|recommends?)\\b"
                    + "|\\b(?:only\\s+)?as\\s+(?:a\\s+)?"
                    + "(?:proposal|suggestion|recommendation)\\b"
                    + "|\\bas\\s+an?\\s+option\\b"
                    + "|\\b(?:verifier|reviewer|report|test)\\s+"
                    + "(?:output|result|finding|message)\\s+"
                    + "(?:is|was|says?|said|reads?)[\\s:,-]*$"
                    + "|\\b(?:(?:the\\s+)?(?:verifier|reviewer|report|test|tool|"
                    + "diagnostic)\\s+)?(?:output|result|finding|message)"
                    + "[\\s:,-]*$"
                    + "|\\b(?:(?:the\\s+)?(?:verifier|reviewer|report|test|tool|"
                    + "diagnostic)\\s+)?(?:output|result|finding|message)"
                    + "\\s+contains?\\s+(?:(?:the|an?)\\s+)?"
                    + "(?:instruction|directive|command|request)\\s+"
                    + "(?:to(?:\\s+not)?|not\\s+to)\\s*$"
                    + "|\\b(?:the\\s+)?(?:(?:diagnostic|tool)\\s+)?"
                    + "(?:output|result|finding|message)\\s+"
                    + "(?:is|was|says?|said|reads?)[\\s:,-]*$"
                    + "|\\baccording\\s+to\\s+(?:the\\s+)?(?:ticket|guide|policy|"
                    + "runbook|reviewer|verifier|report|requirements?|"
                    + "spec(?:ification)?|documentation|document|message|comment|"
                    + "note|issue|task|prompt|example)\\b"
                    + "|\\bper\\s+(?:the\\s+)?(?:ticket|guide|policy|runbook|"
                    + "reviewer|verifier|report|requirements?|spec(?:ification)?|"
                    + "documentation|document|message|comment|note|issue|task|"
                    + "prompt|example)\\b"
                    + "|\\bfor\\s+(?:review|discussion|reference|context|comparison|"
                    + "consideration)(?:\\s+only)?\\b");
    private static final Pattern EXAMPLE_ONLY_SCOPE = Pattern.compile(
            "(?iu)(?:^|[\\s,;:])(?:e\\s*\\.\\s*g\\s*\\.|etc\\s*\\.)"
                    + "(?:[\\s,;:]|$)"
                    + "|\\b(?:as|for)\\s+(?:an?\\s+)?example\\b");
    private static final Pattern BARE_EXAMPLE_LABEL = Pattern.compile(
            "(?iu)(?:^|[\\s\\u2028\\u2029,;])"
                    + "(?:an?[\\s\\u2028\\u2029]+)?examples?"
                    + "[\\s\\u2028\\u2029]*:[\\s\\u2028\\u2029]*$");
    private static final Pattern POSTFIX_OPTIONAL_SCOPE = Pattern.compile(
             "(?iu)\\b(?:only\\s+)?(?:if|when|unless)\\b"
                     + "|\\bif\\s+you\\s+(?:want|wish|prefer|choose)\\b"
                     + "|\\b(?:if|when)\\s+(?:desired|useful|helpful)\\b"
                     + "|\\bas\\s+(?:needed|necessary)\\b"
                     + "|\\b(?:optionally|perhaps|maybe|possibly|potentially)\\b");
    private static final Pattern POSTFIX_REPORTED_SCOPE = Pattern.compile(
            "(?iu)\\bis\\s+what\\s+(?:the\\s+)?(?:ticket|guide|policy|"
                    + "runbook|reviewer|verifier|report|requirements?|"
                    + "spec(?:ification)?|documentation|document|message|"
                    + "comment|note|issue|task|prompt|example)\\s+"
                    + "(?:says?|said|states?|stated|requires?|required|"
                    + "proposes?|proposed|suggests?|suggested|mentions?|"
                    + "mentioned)\\b"
                    + "|\\baccording\\s+to\\s+(?:the\\s+)?(?:ticket|guide|"
                    + "policy|runbook|reviewer|verifier|report|requirements?|"
                    + "spec(?:ification)?|documentation|document|message|"
                    + "comment|note|issue|task|prompt|example)\\b"
                    + "|\\bper\\s+(?:the\\s+)?(?:ticket|guide|policy|"
                    + "runbook|reviewer|verifier|report|requirements?|"
                    + "spec(?:ification)?|documentation|document|message|"
                    + "comment|note|issue|task|prompt|example)\\b"
                    + "|\\b(?:only\\s+)?as\\s+(?:an?\\s+)?(?:proposal|"
                    + "suggestion|recommendation|option|example|reference)\\b");
    private static final Pattern METALINGUISTIC_PREFIX = Pattern.compile(
            "(?iu)(?:\\b(?:the\\s+)?(?:command|instruction|text|phrase|literal|"
                    + "example)\\s*|\\bfor\\s+example[\\s,:-]*)$");
    private static final Pattern PROPOSAL_LABEL_PREFIX = Pattern.compile(
            "(?iu)(?:\\b(?:(?:my|the)\\s+)?"
                    + "(?:proposals?|suggestions?|recommendations?|options?)"
                    + "|\\b(?:one|another|the)\\s+option)"
                    + "(?:\\s+(?:is|are|would\\s+be)\\s+to)?[\\s,:-]*$"
                    + "|\\b(?:proposed|suggested|recommended)\\s+"
                    + "(?:change|edit|update)[\\s,:-]*$"
                    + "|\\b(?:proposed|suggested|recommended)\\s*$");
    private static final Pattern OPTIONAL_LABEL_PREFIX = Pattern.compile(
            "(?iu)\\b(?:optional|possible|conditional)[\\s,:-]*$");
    private static final Pattern DECLARATIVE_ACTOR_PREFIX = Pattern.compile(
            "(?iu)\\b(?:(?:i|we)\\s+(?:will|can|could|may|might|"
                    + "plan(?:ned)?\\s+to|intend(?:ed)?\\s+to)"
                    + "|(?:the\\s+)?operator\\s+"
                    + "(?:will|can|could|may|might))\\s*$");
    private static final Pattern THIRD_PARTY_CLAUSE_BOUNDARY =
            Pattern.compile("(?iu)(?:[!?;:\\r\\n]+\\s*"
                    + "|\\b(?:so|then|but)\\b\\s+)");
    private static final Pattern THIRD_PARTY_INTENT_END = Pattern.compile(
            "(?iu)\\b(?:plans?|planned|intends?|intended|wants?|wanted|"
                    + "needs?|needed)\\s+to\\s*$"
                    + "|\\b(?:is|was)\\s+going\\s+to\\s*$");
    private static final Pattern DIRECT_COLLABORATIVE_NECESSITY_END =
            Pattern.compile(
                    "(?iu)\\b(?:needs?|needed|(?:has|have|had))\\s+to\\s*$");
    private static final Pattern DIRECT_CONTROL_COMPLEMENT_END =
            Pattern.compile(
                    "(?iu)\\b(?:needs?|needed|wants?|wanted|expects?|expected|"
                            + "prefers?|preferred|would\\s+like)\\s+"
                            + "(?:you|talos)\\s+to\\s*$");
    private static final Pattern DIRECT_FIRST_PERSON_PERFORMATIVE =
            Pattern.compile(
                    "(?iu)(?:^|[,:;\\r\\n\\u2013\\u2014]+\\s*"
                            + "|\\b(?:so|but|then|also)\\b\\s+)"
                            + "([^!?;:\\r\\n\\u2013\\u2014]+?)\\s+"
                            + "(?:(?:am|are)\\s+)?"
                            + "(?:(?:hereby|do)\\s+)?"
                            + "(?:require|requires|request|requests|requesting|"
                            + "ask|asks|asking|instruct|instructs|instructing|"
                            + "tell|tells|telling|direct|directs|directing|"
                            + "command|commands|commanding)\\s+"
                            + "(?:that\\s+)?(?:you|talos)"
                            + "(?:\\s+(?:must|should|shall|need|needs|to))?"
                            + "\\s*$");
    private static final Pattern BARE_DIRECT_NECESSITY_END =
            Pattern.compile(
                    "(?iu)(?:^|[,:;\\r\\n]+\\s*|\\b(?:so|and|but|then|also)\\b\\s+)"
                            + "(?:(?:please|kindly|now|still|also|next|really|"
                            + "genuinely|definitely|actually|carefully|"
                            + "explicitly)\\s+){0,4}"
                            + "(?:need(?:s|ed)?\\s+to|must|should|shall)"
                            + "\\s*$");
    private static final Pattern DIRECT_ACTOR_IMPERATIVE_END =
            Pattern.compile(
                    "(?iu)(?:^|[,:;\\r\\n\\u2013\\u2014]+\\s*"
                            + "|\\b(?:so|and|but|then|also)\\b\\s+)"
                            + "(?:you|talos)\\s+"
                            + "(?:(?:please|kindly|now|still|also|next|really|"
                            + "genuinely|definitely|actually|carefully|"
                            + "explicitly|do)\\s+){0,4}$");
    private static final String POSITIVE_NEGATED_FAILURE_FRAME =
            "(?:(?:don['\\u2019]t|do\\s+not)\\s+"
                    + "(?:forget|fail)\\s+to|avoid\\s+failing\\s+to)";
    private static final Pattern POSITIVE_IMPERATIVE_END =
            Pattern.compile(
                    "(?iu)(?:^|[,:;\\r\\n\\u2013\\u2014]+\\s*"
                            + "|\\b(?:so|and|but|then|also)\\b\\s+)"
                            + "(?:(?:please|kindly|now|still|also|next|really|"
                            + "genuinely|definitely|actually|carefully|"
                            + "explicitly)\\s+){0,4}"
                            + "(?:make\\s+sure\\s+to|be\\s+sure\\s+to|"
                            + "remember\\s+to|"
                            + POSITIVE_NEGATED_FAILURE_FRAME
                            + ")"
                            + "\\s*$");
    private static final Pattern POSITIVE_NEGATED_FAILURE_DIRECTIVE =
            Pattern.compile(
                    "(?iu)(?:^|[,:;\\r\\n\\u2013\\u2014]+\\s*"
                            + "|\\b(?:so|and|but|then|also)\\b\\s+)"
                            + "(?:(?:please|kindly|now|still|also|next|really|"
                            + "genuinely|definitely|actually|carefully|"
                            + "explicitly)\\s+){0,4}"
                            + POSITIVE_NEGATED_FAILURE_FRAME
                            + "(?:\\s+(?:write|create|save|apply|add|put|"
                            + "implement|regenerate|generate|scaffold|build|make|"
                            + "improve|edit|update|rewrite|replace|append|"
                            + "refactor|overwrite|modify|change|fix|adjust|tweak|"
                            + "restyle|redesign|polish|repair|touch|mkdir|rename|"
                            + "move|copy|delete|remove|rm))?"
                            + "(?:\\s+(?:(?:exactly|only|the|a|an|both|current|"
                            + "existing|local|file|files)\\s+)*)?$");
    private static final Pattern THIRD_PARTY_COMPLEMENT_TO_END =
            Pattern.compile(
                    "(?iu)\\bto"
                            + "(?:\\s+(?:then|also|please|kindly|directly|"
                            + "now|first|next)){0,4}\\s*$");
    private static final Pattern THIRD_PARTY_COMPLEMENT_VERB =
            Pattern.compile(
                    "(?iu)\\b(?:(?:wants?|wanted|needs?|needed|expects?|"
                            + "expected|prefers?|preferred)"
                            + "|would\\s+like)\\b");
    private static final Pattern THIRD_PARTY_ATTRIBUTION_PREFIX =
            Pattern.compile(
                    "(?iu)(?:^|[!?;:\\r\\n]+\\s*"
                            + "|\\b(?:so|then|and|but)\\b\\s+)"
                            + "(?:according\\s+to|per)\\s+"
                            + "(?!(?:me|us|you|talos)\\b)"
                            + "[^!?;:\\r\\n]+[\\s,:-]*$");
    private static final Pattern THIRD_PARTY_POSSESSIVE_PREFIX =
            Pattern.compile(
                    "(?iu)(?:^|[!?;:\\r\\n]+\\s*"
                            + "|\\b(?:so|then|and|but)\\b\\s+)"
                            + "[^!?;:\\r\\n]+['\\u2019]s\\s+"
                            + "(?:instruction|directive|proposal|suggestion|"
                            + "recommendation|plan|request)\\s+"
                            + "(?:is|was)\\s+to\\s*$");
    private static final Pattern THIRD_PARTY_SUBJECT_TOKEN =
            Pattern.compile("(?iu)[\\p{L}\\p{N}]+"
                    + "(?:['_\\u2019-][\\p{L}\\p{N}]+)*");
    private static final Pattern THIRD_PARTY_ROLE_SUBJECT_SEGMENT =
            Pattern.compile(
                    "(?iu)^\\s*(?:the\\s+)?"
                            + "(?:(?:build|local|assistant)\\s+)?"
                            + "(?:bot|runtime|process|operator|agent|developer|"
                            + "user|team)(?:\\s+[\\p{N}]+)?\\s*$");
    private static final Set<String> DIRECT_DISCOURSE_WORDS = Set.of(
            "and", "but", "then", "so", "now", "still", "also", "next",
            "really", "just", "please",
            "kindly", "directly", "immediately", "carefully", "explicitly",
            "finally", "subsequently", "currently", "already", "simply",
            "genuinely", "definitely", "actually", "right", "first", "do");
    private static final Set<String> DIRECT_COLLABORATIVE_ACTORS =
            Set.of("i", "we", "you", "talos");
    private static final Pattern DIRECT_FIRST_PERSON_DIRECTIVE =
            Pattern.compile(
                    "(?iu)^(?:requires?|requests?|asks?|instructs?|tells?|"
                            + "directs?|commands?)$");
    private static final Pattern DIRECT_COLLABORATIVE_RECIPIENT =
            Pattern.compile(
                    "(?iu)^[\\s,:-]*(?:that\\s+)?(?:you|talos)\\b.*$");
    private static final Pattern COMMA_CLOSABLE_OPTIONAL_SCOPE =
            Pattern.compile(
                    "(?iu)\\b(?:maybe|perhaps|optionally|possibly|potentially)"
                            + "\\s*,\\s*$");
    private static final Pattern NEGATED_NECESSITY_PREFIX = Pattern.compile(
            "(?iu)\\b(?:(?:there\\s+is\\s+)?no\\s+need\\s+to"
                    + "|it\\s+is\\s+unnecessary\\s+to)\\s*$");
    private static final Pattern OPEN_PROHIBITION = Pattern.compile(
            "(?iu)(?:\\bdo\\s+not\\b|\\bdon['\\u2019]t\\b|\\bdont\\b|"
                    + "\\bnever\\b|\\bmust\\s+not\\b|\\bmay\\s+not\\b|"
                    + "\\bshould\\s+not\\b|\\bshall\\s+not\\b|\\bcannot\\b|"
                    + "\\bcan['\\u2019]t\\b|"
                    + "\\b(?:mustn|shouldn|shalln)['\\u2019]t\\b|\\bavoid\\b|"
                    + "\\bnot\\s+(?:also\\s+)?|"
                    + "\\b(?:you\\s+are\\s+)?not\\s+allowed\\s+to\\b|"
                    + "\\b(?:you|talos)\\s+(?:aren|isn)['\\u2019]t\\s+"
                    + "(?:allowed|authorized|permitted)\\s+to\\b|"
                    + "\\b(?:i|we)\\s+forbid(?:\\s+(?:you|talos))?\\s+to\\b|"
                    + "\\b(?:you|talos)\\s+(?:are|is)\\s+forbidden\\s+to\\b|"
                    + "\\b(?:i|we)\\s+(?:deny|revoke)\\s+"
                    + "(?:(?:your|talos['\\u2019]s)\\s+)?permission\\s+to\\b|"
                    + "\\bnot\\s+to\\b|"
                    + "\\b(?:rather\\s+than|instead\\s+of)\\b)"
                    + "[\\s,:;\\-\\u2013\\u2014]*$");
    private static final Pattern GENERAL_PROHIBITION = Pattern.compile(
            "(?iu)(?:\\bdo\\s+not\\b|\\bdon['\\u2019]t\\b|\\bdont\\b|"
                    + "\\bnever\\b|\\bmust\\s+not\\b|\\bmay\\s+not\\b|"
                    + "\\bshould\\s+not\\b|\\bshall\\s+not\\b|\\bcannot\\b|"
                    + "\\bcan['\\u2019]t\\b|"
                    + "\\b(?:mustn|shouldn|shalln)['\\u2019]t\\b|\\bavoid\\b|"
                    + "\\b(?:you\\s+are\\s+)?not\\s+allowed\\s+to\\b|"
                    + "\\b(?:you|talos)\\s+(?:aren|isn)['\\u2019]t\\s+"
                    + "(?:allowed|authorized|permitted)\\s+to\\b|"
                    + "\\b(?:i|we)\\s+forbid(?:\\s+(?:you|talos))?\\s+to\\b|"
                    + "\\b(?:you|talos)\\s+(?:are|is)\\s+forbidden\\s+to\\b|"
                    + "\\b(?:i|we)\\s+(?:deny|revoke)\\s+"
                    + "(?:(?:your|talos['\\u2019]s)\\s+)?permission\\s+to\\b)");
    private static final Pattern READ_ONLY_PROHIBITION_COMPLEMENT =
            Pattern.compile(
                    "(?iu)^[\\s,:;\\-\\u2013\\u2014]*(?:read|inspect|view|"
                            + "open|search|list|scan|review)\\b");
    private static final Pattern FRESH_SCOPE_AFTER_CONNECTOR_PREFIX =
            Pattern.compile(
                    "(?iu)^[\\s,:;\\-\\u2013\\u2014]*"
                            + "(?:(?:please|kindly|also|now|first|next|only|"
                            + "directly|immediately|carefully|explicitly|"
                            + "finally|subsequently|really|genuinely|definitely|"
                            + "actually|simply|just|still|i|we|you|talos|do|must|"
                            + "should|shall|can|could|would|will|may|need|"
                            + "needs|needed|want|wants|wanted|have|has|to|that|"
                            + "require|requires|required|request|requests|requested|"
                            + "ask|asks|asked|instruct|instructs|instructed|tell|"
                            + "tells|told|direct|directs|directed|command|commands|"
                            + "commanded)\\b"
                            + "[\\s,:;\\-\\u2013\\u2014]*)*$");
    private static final Pattern CLOSED_QUOTED_PAYLOAD_RESET = Pattern.compile(
            "(?iu)(?:,\\s*(?:and(?:\\s+also)?|but|then)"
                    + "|[;:]|\\r\\n|[\\r\\n]|[\\u2013\\u2014])\\s*");
    static final String REPORTED_PREDICATE_WORD =
            "(?:says?|said|states?|stated|requires?|required|"
                    + "proposes?|proposed|suggests?|suggested|advises?|advised|"
                    + "directs?|directed|commands?|commanded|asks?|asked|"
                    + "instructs?|instructed|tells?|told|requests?|requested|"
                    + "recommends?|recommended|mentions?|mentioned|wrote|reads?|"
                    + "explains?|explained|shows?|showed|describes?|described|"
                    + "documents?|documented|demonstrates?|demonstrated|"
                    + "teaches?|taught|thinks?|thought|believes?|believed|"
                    + "notes?|noted|indicates?|indicated|observes?|observed|"
                    + "hears?|heard|knows?|knew|reports?|reported|"
                    + "claims?|claimed|asserts?|asserted)";
    private static final Pattern REPORTED_PREDICATE = Pattern.compile(
            "(?iu)\\b" + REPORTED_PREDICATE_WORD + "\\b");
    private static final Pattern FINITE_REPORTED_LITERAL_COMPLEMENT =
            Pattern.compile(
                    "(?iu)\\b(?:the\\s+)?"
                            + "[\\p{L}\\p{N}][\\p{L}\\p{N}_'\\u2019-]*\\s+"
                            + REPORTED_PREDICATE_WORD
                            + "\\s+no\\s*,\\s*$");
    private static final Pattern REPORTED_OUTPUT_LABEL = Pattern.compile(
            "(?iu)\\b(?:(?:the\\s+)?(?:verifier|reviewer|report|test|tool|"
                    + "diagnostic)\\s+)?(?:output|result|finding|message)"
                    + "(?:\\s+(?:is|was|says?|said|reads?))?[\\s:,-]*$"
                    + "|\\b(?:(?:the\\s+)?(?:verifier|reviewer|report|test|tool|"
                    + "diagnostic)\\s+)?(?:output|result|finding|message)"
                    + "\\s+contains?\\s+(?:(?:the|an?)\\s+)?"
                    + "(?:instruction|directive|command|request)\\s+"
                    + "(?:to(?:\\s+not)?|not\\s+to)\\s*$");
    private static final Pattern ADVISORY_LABEL = Pattern.compile(
            "(?iu)\\b(?:proposals?|suggestions?|recommendations?|options?)"
                    + "(?:\\s+(?:is|are|would\\s+be)\\s+to)?[\\s:,-]*$"
                    + "|\\b(?:proposed|suggested|recommended)\\s+"
                    + "(?:changes?|edits?|updates?)[\\s:,-]*$");
    private static final Pattern INSTRUCTIONAL_INFINITIVE = Pattern.compile(
            "(?iu)\\b(?:how|ways?|instructions?|guidance|examples?)"
                    + "(?:\\s+on)?\\s+(?:how\\s+)?to\\s*$"
                    + "|\\b(?:is|are|was|were)\\s+"
                    + "(?:designed|intended|configured|expected|supposed|used)"
                    + "\\s+to\\s*$");
    private static final Pattern MODAL_ACTOR = Pattern.compile(
            "(?iu)\\b([\\p{L}][\\p{L}\\p{N}_-]*"
                    + "(?:\\s+[\\p{L}][\\p{L}\\p{N}_-]*){0,5})\\s+"
                    + "(?:will|can|could|may|might|would|should|shall|must)"
                    + "(?:\\s+[\\p{L}][\\p{L}-]*ly){0,3}\\s*$");
    private static final Pattern TRAILING_WORD =
            Pattern.compile("(?iu)([\\p{L}][\\p{L}\\p{N}_-]*)\\s*$");
    private static final Set<String> NON_SUBJECT_REPORTING_LEADS = Set.of(
            "and",
            "are",
            "as",
            "be",
            "been",
            "being",
            "but",
            "can",
            "could",
            "did",
            "do",
            "does",
            "had",
            "has",
            "have",
            "if",
            "is",
            "kindly",
            "may",
            "might",
            "must",
            "not",
            "or",
            "please",
            "shall",
            "should",
            "so",
            "then",
            "to",
            "unless",
            "was",
            "when",
            "were",
            "will",
            "would");

    private CanonicalMutationClauseMatcher() {}

    /**
     * Binds operands only inside one exact parser-owned workspace clause.
     * This is the sole workspace grammar shared by T1196 and T1204; callers
     * must never invoke it over an unowned whole request.
     */
    static Optional<WorkspaceOperationObligation> bindWorkspaceOperation(
            RequestLexicalView view,
            Action action,
            Family effectiveFamily,
            int clauseStart,
            int clauseEnd,
            List<String> candidatePaths,
            WorkspaceOperationObligation.Owner owner
    ) {
        if (view == null
                || action == null
                || effectiveFamily != Family.WORKSPACE_OPERATION
                || owner == null
                || clauseStart < 0
                || clauseEnd <= clauseStart
                || clauseEnd > view.request().length()
                || action.start() < clauseStart
                || action.end() > clauseEnd
                || action.nonImperativeUse()
                || action.scopeKind() != ScopeKind.NONE
                || view.containsProviderRemovedControl(clauseStart, clauseEnd)
                || view.containsLoneCarriageReturn(clauseStart, clauseEnd)
                || view.containsMalformedDelimiter(clauseStart, clauseEnd)
                || view.request().substring(clauseStart, clauseEnd)
                        .indexOf('\0') >= 0) {
            return Optional.empty();
        }
        List<Action> localActions = actions(view, clauseStart, clauseEnd);
        if (localActions.size() != 1
                || localActions.getFirst().start() != action.start()
                || localActions.getFirst().end() != action.end()) {
            return Optional.empty();
        }
        List<String> candidates = candidatePaths == null
                ? List.of()
                : candidatePaths.stream().distinct().toList();
        List<CoordinatedMutationTailParser.TargetOccurrence> operands =
                CoordinatedMutationTailParser.targetOccurrences(
                        view,
                        candidates,
                        action.end(),
                        clauseEnd).stream()
                        .filter(occurrence ->
                                occurrence.envelopeStart() >= action.end()
                                        && occurrence.envelopeEnd() <= clauseEnd)
                        .toList();
        String verb = action.verb().toLowerCase(Locale.ROOT).strip();
        WorkspaceOperationObligation.Kind kind = workspaceKind(
                verb,
                view.maskedLower().substring(action.end(), clauseEnd));
        if (kind == null) return Optional.empty();

        int requiredOperands = switch (kind) {
            case COPY_PATH, MOVE_PATH, RENAME_PATH -> 2;
            case MKDIR, DELETE_PATH -> 1;
        };
        if (operands.size() != requiredOperands) return Optional.empty();

        var first = operands.getFirst();
        String firstPrefix = view.masked().substring(
                action.end(),
                first.envelopeStart());
        Pattern firstPrefixPattern = kind == WorkspaceOperationObligation.Kind.MKDIR
                ? WORKSPACE_TARGET_PREFIX
                : WORKSPACE_SOURCE_PREFIX;
        if (!firstPrefixPattern.matcher(firstPrefix).matches()) {
            return Optional.empty();
        }

        List<WorkspaceOperationObligation.Operand> typedOperands;
        int lastEnd = first.envelopeEnd();
        if (requiredOperands == 1) {
            WorkspaceOperationObligation.Role role =
                    WorkspaceOperationObligation.Role.TARGET;
            typedOperands = List.of(operand(view, first, role, first.path()));
        } else {
            var second = operands.getLast();
            String connector = view.masked().substring(
                    first.envelopeEnd(),
                    second.envelopeStart());
            Pattern expectedConnector =
                    kind == WorkspaceOperationObligation.Kind.RENAME_PATH
                            ? RENAME_CONNECTOR
                            : COPY_MOVE_CONNECTOR;
            if (!expectedConnector.matcher(connector).matches()) {
                return Optional.empty();
            }
            String destination = second.path();
            if (kind == WorkspaceOperationObligation.Kind.RENAME_PATH) {
                destination = renameDestination(first.path(), second.path());
                if (destination.isEmpty()) return Optional.empty();
            }
            typedOperands = List.of(
                    operand(
                            view,
                            first,
                            WorkspaceOperationObligation.Role.SOURCE,
                            first.path()),
                    operand(
                            view,
                            second,
                            WorkspaceOperationObligation.Role.DESTINATION,
                            destination));
            lastEnd = second.envelopeEnd();
        }

        String trailer = view.maskedLower().substring(lastEnd, clauseEnd);
        boolean overwrite = trailer.contains("overwrite");
        boolean recursive = trailer.contains("recursively");
        Pattern trailerPattern = switch (kind) {
            case COPY_PATH -> COPY_OPERATION_TRAILER;
            case MOVE_PATH, RENAME_PATH -> OVERWRITE_OPERATION_TRAILER;
            case DELETE_PATH -> RECURSIVE_OPERATION_TRAILER;
            case MKDIR -> EMPTY_OPERATION_TRAILER;
        };
        if (!trailerPattern.matcher(trailer).matches()) {
            return Optional.empty();
        }
        WorkspaceOperationObligation.OverwritePolicy overwritePolicy =
                switch (kind) {
                    case COPY_PATH, MOVE_PATH, RENAME_PATH -> overwrite
                            ? WorkspaceOperationObligation.OverwritePolicy
                                    .OVERWRITE_EXPLICIT
                            : WorkspaceOperationObligation.OverwritePolicy
                                    .FAIL_IF_EXISTS;
                    case MKDIR, DELETE_PATH ->
                            WorkspaceOperationObligation.OverwritePolicy
                                    .NOT_APPLICABLE;
                };
        WorkspaceOperationObligation.RecursivePolicy recursivePolicy =
                switch (kind) {
                    case COPY_PATH, DELETE_PATH -> recursive
                            ? WorkspaceOperationObligation.RecursivePolicy
                                    .RECURSIVE_EXPLICIT
                            : WorkspaceOperationObligation.RecursivePolicy
                                    .NON_RECURSIVE;
                    case MOVE_PATH, RENAME_PATH, MKDIR ->
                            WorkspaceOperationObligation.RecursivePolicy
                                    .NOT_APPLICABLE;
                };
        WorkspaceOperationObligation.ParentPolicy parentPolicy =
                switch (kind) {
                    case COPY_PATH, MOVE_PATH, RENAME_PATH ->
                            WorkspaceOperationObligation.ParentPolicy
                                    .REQUIRE_EXISTING_PARENT;
                    case MKDIR -> WorkspaceOperationObligation.ParentPolicy
                            .REQUIRE_EXISTING_PARENT;
                    case DELETE_PATH -> WorkspaceOperationObligation.ParentPolicy
                            .NOT_APPLICABLE;
                };
        try {
            return Optional.of(WorkspaceOperationObligation.create(
                    owner,
                    kind,
                    typedOperands,
                    overwritePolicy,
                    recursivePolicy,
                    parentPolicy,
                    view.request(),
                    action.start(),
                    action.end(),
                    clauseStart,
                    clauseEnd));
        } catch (IllegalArgumentException rejected) {
            return Optional.empty();
        }
    }

    private static WorkspaceOperationObligation.Operand operand(
            RequestLexicalView view,
            CoordinatedMutationTailParser.TargetOccurrence occurrence,
            WorkspaceOperationObligation.Role role,
            String effectivePath
    ) {
        return new WorkspaceOperationObligation.Operand(
                role,
                view.request().substring(
                        occurrence.envelopeStart(),
                        occurrence.envelopeEnd()),
                effectivePath,
                occurrence.envelopeStart(),
                occurrence.envelopeEnd());
    }

    private static WorkspaceOperationObligation.Kind workspaceKind(
            String verb,
            String trailingContext
    ) {
        if ("copy".equals(verb)) {
            return WorkspaceOperationObligation.Kind.COPY_PATH;
        }
        if ("move".equals(verb)) {
            return WorkspaceOperationObligation.Kind.MOVE_PATH;
        }
        if ("rename".equals(verb)) {
            return WorkspaceOperationObligation.Kind.RENAME_PATH;
        }
        if (Set.of("delete", "remove", "rm").contains(verb)) {
            return WorkspaceOperationObligation.Kind.DELETE_PATH;
        }
        boolean directoryAction = "mkdir".equals(verb)
                || (verb.startsWith("create ") || verb.startsWith("make "))
                        && DIRECTORY_CONTEXT.matcher(verb).find()
                || ("create".equals(verb) || "make".equals(verb))
                        && DIRECTORY_CONTEXT.matcher(
                                trailingContext == null
                                        ? ""
                                        : trailingContext)
                                .find();
        return directoryAction
                ? WorkspaceOperationObligation.Kind.MKDIR
                : null;
    }

    private static String renameDestination(String source, String destination) {
        String normalizedSource = TargetRef.normalizePath(source);
        String normalizedDestination = TargetRef.normalizePath(destination);
        int sourceSlash = normalizedSource.lastIndexOf('/');
        String sourceParent = sourceSlash < 0
                ? ""
                : normalizedSource.substring(0, sourceSlash + 1);
        if (!normalizedDestination.contains("/")) {
            return sourceParent + normalizedDestination;
        }
        int destinationSlash = normalizedDestination.lastIndexOf('/');
        String destinationParent = normalizedDestination.substring(
                0,
                destinationSlash + 1);
        return destinationParent.equals(sourceParent)
                ? normalizedDestination
                : "";
    }

    static List<Action> actions(RequestLexicalView view, int start, int end) {
        return indexedActions(view, start, end, List.of()).actions();
    }

    static IndexedActions indexedActions(
            RequestLexicalView view,
            int start,
            int end,
            List<RequestLexicalView.Span> protectedTargetEnvelopes
    ) {
        if (view == null || start >= end) {
            return new IndexedActions(
                    List.of(),
                    new AuthorityWork(
                            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                            0, 0, 0, 0, 0, 0, 0, 0));
        }
        int safeStart = Math.max(0, start);
        int safeEnd = Math.min(view.request().length(), Math.max(safeStart, end));
        MutableAuthorityWork work = new MutableAuthorityWork();
        work.actionScans = 1;
        work.actionInputCharacters = safeEnd - safeStart;
        work.connectorScans = 1;
        Matcher matcher = ACTION.matcher(view.maskedLower());
        matcher.region(safeStart, safeEnd);
        List<ActionToken> tokens = new ArrayList<>();
        while (matcher.find()) {
            if (!view.isUnquotedRange(matcher.start(), matcher.end())) continue;
            if (insidePathToken(view.request(), matcher.start(), matcher.end())) {
                continue;
            }
            NominalPrefixCheck nominal = nominalActionPrefix(
                    view.maskedLower(), safeStart, matcher.start());
            work.nominalPrefixChecks++;
            work.nominalPrefixCharacters += nominal.visitedCharacters();
            if (nominal.matches()) {
                continue;
            }
            String verb = view.request().substring(matcher.start(), matcher.end());
            tokens.add(new ActionToken(
                    workspaceOperation(verb)
                            ? Family.WORKSPACE_OPERATION
                            : Family.CONTENT_MUTATION,
                    verb.toLowerCase(Locale.ROOT),
                    matcher.start(),
                    matcher.end()));
        }
        tokens = withoutWorkspaceOverwriteModifier(view, tokens, safeEnd);
        return new AuthorityConnectorIndex(
                view,
                safeStart,
                safeEnd,
                tokens,
                protectedTargetEnvelopes).result(work);
    }

    private static List<ActionToken> withoutWorkspaceOverwriteModifier(
            RequestLexicalView view,
            List<ActionToken> tokens,
            int safeEnd
    ) {
        if (view == null || tokens == null || tokens.size() < 2) {
            return tokens == null ? List.of() : List.copyOf(tokens);
        }
        List<ActionToken> retained = new ArrayList<>(tokens.size());
        for (ActionToken token : tokens) {
            ActionToken previous = retained.isEmpty()
                    ? null
                    : retained.getLast();
            boolean exactModifier = previous != null
                    && previous.family() == Family.WORKSPACE_OPERATION
                    && "overwrite".equals(token.verb())
                    && WORKSPACE_OVERWRITE_MODIFIER_GAP.matcher(
                            view.maskedLower().substring(
                                    previous.end(),
                                    token.start())).matches()
                    && WORKSPACE_OVERWRITE_MODIFIER_SUFFIX.matcher(
                            view.maskedLower().substring(
                                    token.end(),
                                    Math.min(
                                            safeEnd,
                                            view.sentenceEndAfter(
                                                    token.end())))).matches();
            if (!exactModifier) retained.add(token);
        }
        return List.copyOf(retained);
    }

    /**
     * Exact bounded equivalent of {@code NOMINAL_ACTION_PREFIX}.
     *
     * <p>The old implementation searched a newly allocated prefix growing from
     * {@code safeStart} for every action token. Only the immediately preceding
     * ASCII-regex whitespace run, one of eight finite words, and its word
     * boundary can affect the result, so no earlier request byte is inspected.
     */
    private static NominalPrefixCheck nominalActionPrefix(
            String maskedLower,
            int safeStart,
            int actionStart
    ) {
        int cursor = actionStart;
        long visited = 0;
        while (cursor > safeStart
                && isAsciiRegexWhitespace(maskedLower.charAt(cursor - 1))) {
            cursor--;
            visited++;
        }
        if (cursor == actionStart) {
            return new NominalPrefixCheck(false, visited);
        }

        int wordEnd = cursor;
        int boundedStart = Math.max(
                safeStart,
                wordEnd - MAX_NOMINAL_ACTION_WORD_LENGTH);
        while (cursor > boundedStart
                && isAsciiLetter(maskedLower.charAt(cursor - 1))) {
            cursor--;
            visited++;
        }
        if (cursor > safeStart) {
            char boundary = maskedLower.charAt(cursor - 1);
            visited++;
            if (isAsciiWordCharacter(boundary)) {
                return new NominalPrefixCheck(false, visited);
            }
        }
        String word = maskedLower.substring(cursor, wordEnd);
        return new NominalPrefixCheck(
                NOMINAL_ACTION_WORDS.contains(word),
                visited);
    }

    private static boolean isAsciiRegexWhitespace(char value) {
        return value == ' '
                || value == '\t'
                || value == '\n'
                || value == '\u000B'
                || value == '\f'
                || value == '\r';
    }

    private static boolean isAsciiLetter(char value) {
        return value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z';
    }

    private static boolean isAsciiWordCharacter(char value) {
        return isAsciiLetter(value)
                || value >= '0' && value <= '9'
                || value == '_';
    }

    private record NominalPrefixCheck(
            boolean matches,
            long visitedCharacters
    ) {}

    private record ActionToken(
            Family family,
            String verb,
            int start,
            int end
    ) {}

    record AuthorityWork(
            int actionScans,
            long actionInputCharacters,
            long nominalPrefixChecks,
            long nominalPrefixCharacters,
            int connectorScans,
            long connectorInputCharacters,
            long authorityTransitions,
            long authorityClauseCharacters,
            long fallbackClassifications,
            long fallbackCharacters,
            long fallbackFailClosedClassifications,
            int targetEnvelopeIndexBuilds,
            int targetEnvelopeIndexEntries,
            long targetEnvelopeBuildCharacters,
            long targetEnvelopeContainmentQueries,
            long targetEnvelopeRangeQueries,
            long targetEnvelopeVisits,
            long targetEnvelopeCoverageChecks
    ) {}

    record IndexedActions(List<Action> actions, AuthorityWork work) {
        IndexedActions {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    /**
     * Immutable, source-ordered index for action authority in one bounded tail.
     *
     * <p>The exact legacy grammar remains the authority for the first or any
     * ambiguous clause. Once an action has a typed scope, a separator made only
     * from already-selected target envelopes and declared connectors cannot
     * introduce new authority prose. Those continuations are advanced from the
     * carried state without rebuilding every growing request prefix.
     */
    private static final class AuthorityConnectorIndex {
        private static final Pattern NEUTRAL_CONNECTOR_ENVELOPE =
                Pattern.compile(
                        "(?isu)^[\\s,;:\\-\\u2013\\u2014]*"
                                + "(?:(?:and|but|then|also)\\b"
                                + "[\\s,;:\\-\\u2013\\u2014]*)*$");
        private static final Pattern TYPED_RESET =
                Pattern.compile("(?iu)\\b(?:but|then)\\b");

        private final RequestLexicalView view;
        private final int safeStart;
        private final int safeEnd;
        private final List<ActionToken> tokens;
        private final TargetEnvelopeIndex targetIndex;

        private AuthorityConnectorIndex(
                RequestLexicalView view,
                int safeStart,
                int safeEnd,
                List<ActionToken> tokens,
                List<RequestLexicalView.Span> protectedTargetEnvelopes
        ) {
            this.view = view;
            this.safeStart = safeStart;
            this.safeEnd = safeEnd;
            this.targetIndex = new TargetEnvelopeIndex(
                    protectedTargetEnvelopes,
                    safeStart,
                    safeEnd);
            this.tokens = tokens == null ? List.of() : List.copyOf(tokens);
        }

        private IndexedActions result(MutableAuthorityWork work) {
            work.targetEnvelopeIndexBuilds++;
            work.targetEnvelopeIndexEntries += targetIndex.size();
            work.targetEnvelopeBuildCharacters += targetIndex.coverageLength();
            work.targetEnvelopeVisits += targetIndex.buildVisits();
            if (tokens.isEmpty()) {
                return new IndexedActions(List.of(), work.snapshot());
            }

            List<Action> actions = new ArrayList<>(tokens.size());
            ActionAuthority carried = null;
            ActionToken previous = null;
            long fallbackCharacterBudget = (long) (safeEnd - safeStart)
                    * MAX_FALLBACK_INPUT_MULTIPLIER;
            for (ActionToken token : tokens) {
                boolean targetAmbiguous = targetIndex.strictlyContains(
                        token, work);
                ActionAuthority authority;
                NeutralTransition transition = previous == null
                                || targetAmbiguous
                        ? NeutralTransition.ambiguous()
                        : neutralTransition(previous.end(), token.start(), work);
                if (carried != null
                        && transition.neutral()
                        && !transition.sentenceBoundary()
                        && !carried.nonImperativeUse()) {
                    authority = advance(carried, transition);
                    work.authorityTransitions++;
                } else {
                    int classificationStart = boundedFallbackStart(
                            carried,
                            previous,
                            transition);
                    long classificationCharacters = Math.max(
                            0,
                            token.start() - classificationStart);
                    if (classificationCharacters
                                    > MAX_FALLBACK_CLASSIFICATION_CHARACTERS
                            || work.fallbackCharacters
                                    + classificationCharacters
                                    > fallbackCharacterBudget) {
                        authority = new ActionAuthority(
                                true,
                                ScopeKind.PROHIBITED);
                        work.fallbackFailClosedClassifications++;
                    } else {
                        authority = classifyActionAuthority(
                                view,
                                classificationStart,
                                token.start());
                        work.fallbackClassifications++;
                        work.fallbackCharacters += classificationCharacters;
                    }
                }
                actions.add(new Action(
                        token.family(),
                        token.verb(),
                        token.start(),
                        token.end(),
                        authority.nonImperativeUse(),
                        authority.scopeKind()));
                if (targetAmbiguous) {
                    carried = null;
                    previous = null;
                } else {
                    carried = authority;
                    previous = token;
                }
            }
            return new IndexedActions(actions, work.snapshot());
        }

        /**
         * A directly classified preceding action with no open restrictive
         * scope is a complete authority boundary. For that state, authority
         * affecting the next action can only be introduced by the intervening
         * source bytes, so an ambiguous connector fallback may classify that
         * disjoint segment instead of re-scanning the whole growing tail.
         *
         * <p>Restrictive, unknown, and target-ambiguous states deliberately
         * retain the canonical full-prefix fallback. This keeps optional,
         * reported, advisory, and prohibited scope propagation fail-closed.
         */
        private int boundedFallbackStart(
                ActionAuthority carried,
                ActionToken previous,
                NeutralTransition transition
        ) {
            if (transition != null
                    && transition.previousTargetBound()
                    && transition.freshClauseStart() >= 0) {
                return Math.max(safeStart, transition.freshClauseStart());
            }
            if (carried == null
                    || previous == null
                    || transition == null
                    || !transition.previousTargetBound()
                    || carried.nonImperativeUse()
                    || carried.scopeKind() != ScopeKind.NONE) {
                return safeStart;
            }
            return Math.max(safeStart, previous.end());
        }

        private ActionAuthority advance(
                ActionAuthority carried,
                NeutralTransition transition
        ) {
            if (!transition.hasReset()) return carried;
            ScopeKind scope = carried.scopeKind();
            boolean reset = switch (scope) {
                case OPTIONAL -> "then".equals(transition.connector())
                        && transition.commaBeforeConnector();
                case REPORTED_ADVISORY -> "but".equals(transition.connector())
                        && transition.commaBeforeConnector();
                case PROHIBITED -> false;
                case NONE -> true;
            };
            return reset
                    ? new ActionAuthority(false, ScopeKind.NONE)
                    : carried;
        }

        private NeutralTransition neutralTransition(
                int from,
                int to,
                MutableAuthorityWork work
        ) {
            int safeFrom = Math.max(safeStart, from);
            int safeTo = Math.min(safeEnd, Math.max(safeFrom, to));
            EnclosedTargetRange enclosedTargets =
                    targetIndex.enclosedBetween(safeFrom, safeTo, work);
            boolean previousTargetBound = enclosedTargets.count() == 1
                    && DIRECT_TARGET_PREFIX.matcher(
                            view.masked().substring(
                                    safeFrom,
                                    enclosedTargets.first().start()))
                            .matches();
            if (!previousTargetBound) {
                work.connectorInputCharacters += safeTo - safeFrom;
                work.authorityClauseCharacters += safeTo - safeFrom;
                return NeutralTransition.ambiguous();
            }
            StringBuilder visible = new StringBuilder(safeTo - safeFrom);
            boolean sentenceBoundary = false;
            int freshClauseStart = -1;
            for (int offset = safeFrom; offset < safeTo; offset++) {
                work.connectorInputCharacters++;
                if (view.isSentenceBoundaryAt(offset)) {
                    sentenceBoundary = true;
                    freshClauseStart = offset + 1;
                }
                visible.append(targetIndex.covers(offset, work)
                        || view.isQuoted(offset)
                        ? ' '
                        : view.maskedLower().charAt(offset));
            }
            String envelope = visible.toString();
            work.authorityClauseCharacters += envelope.length();
            if (!NEUTRAL_CONNECTOR_ENVELOPE.matcher(envelope).matches()) {
                return new NeutralTransition(
                        false,
                        sentenceBoundary,
                        "",
                        false,
                        false,
                        freshClauseStart,
                        true);
            }
            Matcher reset = TYPED_RESET.matcher(envelope);
            String connector = "";
            int connectorStart = -1;
            int connectorEnd = -1;
            while (reset.find()) {
                connector = reset.group().toLowerCase(Locale.ROOT);
                connectorStart = reset.start();
                connectorEnd = reset.end();
            }
            boolean fresh = connectorEnd >= 0
                    && freshAuthorityPrefix(envelope.substring(connectorEnd));
            boolean commaBefore = connectorStart >= 0
                    && envelope.substring(0, connectorStart)
                            .stripTrailing()
                            .endsWith(",");
            return new NeutralTransition(
                    true,
                    sentenceBoundary,
                    connector,
                    fresh,
                    commaBefore,
                    freshClauseStart,
                    true);
        }

        private static List<RequestLexicalView.Span> normalizedEnvelopes(
                List<RequestLexicalView.Span> raw,
                int from,
                int to
        ) {
            if (raw == null || raw.isEmpty()) return List.of();
            return raw.stream()
                    .filter(span -> span != null
                            && span.start() >= from
                            && span.end() <= to
                            && span.end() > span.start())
                    .distinct()
                    .sorted(java.util.Comparator
                            .comparingInt(RequestLexicalView.Span::start)
                            .thenComparingInt(RequestLexicalView.Span::end))
                    .toList();
        }

        private static final class TargetEnvelopeIndex {
            private final int safeStart;
            private final List<RequestLexicalView.Span> targets;
            private final boolean[] covered;
            private final long buildVisits;
            private int containmentCursor;
            private int greatestContainingEnd = -1;
            private int rangeCursor;
            private int previousContainmentStart = -1;
            private int previousRangeStart = -1;

            private TargetEnvelopeIndex(
                    List<RequestLexicalView.Span> raw,
                    int safeStart,
                    int safeEnd
            ) {
                this.safeStart = safeStart;
                this.buildVisits = raw == null ? 0 : raw.size();
                this.targets = normalizedEnvelopes(
                        raw,
                        safeStart,
                        safeEnd);
                int length = Math.max(0, safeEnd - safeStart);
                int[] boundaries = new int[length + 1];
                for (RequestLexicalView.Span target : targets) {
                    boundaries[target.start() - safeStart]++;
                    boundaries[target.end() - safeStart]--;
                }
                this.covered = new boolean[length];
                int active = 0;
                for (int index = 0; index < length; index++) {
                    active += boundaries[index];
                    covered[index] = active > 0;
                }
            }

            private int size() {
                return targets.size();
            }

            private int coverageLength() {
                return covered.length;
            }

            private long buildVisits() {
                return buildVisits;
            }

            private boolean strictlyContains(
                    ActionToken token,
                    MutableAuthorityWork work
            ) {
                work.targetEnvelopeContainmentQueries++;
                if (token.start() < previousContainmentStart) {
                    throw new IllegalStateException(
                            "action tokens must be source ordered");
                }
                previousContainmentStart = token.start();
                while (containmentCursor < targets.size()
                        && targets.get(containmentCursor).start()
                                < token.start()) {
                    RequestLexicalView.Span target =
                            targets.get(containmentCursor++);
                    work.targetEnvelopeVisits++;
                    greatestContainingEnd = Math.max(
                            greatestContainingEnd,
                            target.end());
                }
                return greatestContainingEnd > token.end();
            }

            private EnclosedTargetRange enclosedBetween(
                    int from,
                    int to,
                    MutableAuthorityWork work
            ) {
                work.targetEnvelopeRangeQueries++;
                if (from < previousRangeStart) {
                    throw new IllegalStateException(
                            "authority transitions must be source ordered");
                }
                previousRangeStart = from;
                while (rangeCursor < targets.size()
                        && targets.get(rangeCursor).start() < from) {
                    rangeCursor++;
                    work.targetEnvelopeVisits++;
                }
                RequestLexicalView.Span first = null;
                int count = 0;
                while (rangeCursor < targets.size()
                        && targets.get(rangeCursor).start() < to) {
                    RequestLexicalView.Span target =
                            targets.get(rangeCursor++);
                    work.targetEnvelopeVisits++;
                    if (target.end() <= to) {
                        if (first == null) first = target;
                        count++;
                    }
                }
                return new EnclosedTargetRange(first, count);
            }

            private boolean covers(int offset, MutableAuthorityWork work) {
                work.targetEnvelopeCoverageChecks++;
                int index = offset - safeStart;
                return index >= 0
                        && index < covered.length
                        && covered[index];
            }
        }

        private record EnclosedTargetRange(
                RequestLexicalView.Span first,
                int count
        ) {}
    }

    private static final class MutableAuthorityWork {
        private int actionScans;
        private long actionInputCharacters;
        private long nominalPrefixChecks;
        private long nominalPrefixCharacters;
        private int connectorScans;
        private long connectorInputCharacters;
        private long authorityTransitions;
        private long authorityClauseCharacters;
        private long fallbackClassifications;
        private long fallbackCharacters;
        private long fallbackFailClosedClassifications;
        private int targetEnvelopeIndexBuilds;
        private int targetEnvelopeIndexEntries;
        private long targetEnvelopeBuildCharacters;
        private long targetEnvelopeContainmentQueries;
        private long targetEnvelopeRangeQueries;
        private long targetEnvelopeVisits;
        private long targetEnvelopeCoverageChecks;

        private AuthorityWork snapshot() {
            return new AuthorityWork(
                    actionScans,
                    actionInputCharacters,
                    nominalPrefixChecks,
                    nominalPrefixCharacters,
                    connectorScans,
                    connectorInputCharacters,
                    authorityTransitions,
                    authorityClauseCharacters,
                    fallbackClassifications,
                    fallbackCharacters,
                    fallbackFailClosedClassifications,
                    targetEnvelopeIndexBuilds,
                    targetEnvelopeIndexEntries,
                    targetEnvelopeBuildCharacters,
                    targetEnvelopeContainmentQueries,
                    targetEnvelopeRangeQueries,
                    targetEnvelopeVisits,
                    targetEnvelopeCoverageChecks);
        }
    }

    private record ActionAuthority(
            boolean nonImperativeUse,
            ScopeKind scopeKind
    ) {}

    private record NeutralTransition(
            boolean neutral,
            boolean sentenceBoundary,
            String connector,
            boolean fresh,
            boolean commaBeforeConnector,
            int freshClauseStart,
            boolean previousTargetBound
    ) {
        private static NeutralTransition ambiguous() {
            return new NeutralTransition(
                    false,
                    false,
                    "",
                    false,
                    false,
                    -1,
                    false);
        }

        private boolean hasReset() {
            return fresh && !connector.isEmpty();
        }
    }

    private static boolean nonImperativeUse(
            RequestLexicalView view,
            int regionStart,
            int actionStart
    ) {
        return classifyActionAuthority(
                view,
                regionStart,
                actionStart).nonImperativeUse();
    }

    private static ActionAuthority classifyActionAuthority(
            RequestLexicalView view,
            int regionStart,
            int actionStart
    ) {
        int activeRegionStart = Math.max(0, regionStart);
        for (int offset = activeRegionStart; offset < actionStart; offset++) {
            if (view.isSentenceBoundaryAt(offset)) {
                activeRegionStart = offset + 1;
            }
        }
        String prefix = view.masked().substring(
                activeRegionStart,
                actionStart);
        if (prefix.isBlank()) {
            return new ActionAuthority(false, ScopeKind.NONE);
        }
        String active = afterClosedQuotedPayloadReset(
                view,
                activeRegionStart,
                prefix);
        String currentClause = activeClause(active);
        if (DIRECT_ASSISTANT_ACTION_REQUEST.matcher(currentClause).matches()
                && active.strip().equals(currentClause.strip())) {
            return new ActionAuthority(false, ScopeKind.NONE);
        }
        ScopeKind scope = finiteScopeKind(currentClause);
        if (positiveDirectiveSyntax(currentClause)) {
            return new ActionAuthority(false, scope);
        }
        if (nonAuthoritativeContext(active)) {
            return new ActionAuthority(true, scope);
        }
        if (directCollaborativeAuthorityContext(currentClause)) {
            return new ActionAuthority(false, scope);
        }
        boolean nonImperative = !EXPLICIT_ACTION_PREFIX.matcher(currentClause).matches()
                && !EXPLICIT_CLAUSE_BOUNDARY_PREFIX
                        .matcher(currentClause).find()
                && !TRAILING_STRUCTURAL_CONNECTOR_PREFIX
                        .matcher(currentClause).find()
                && !EXPLICIT_MODIFIER_ONLY_PREFIX
                        .matcher(currentClause).matches();
        return new ActionAuthority(nonImperative, scope);
    }

    /** Generic intent is recognized here, workspace operation authority is not granted. */
    static boolean directToolRequestPrefix(RequestLexicalView view, int actionStart) {
        return DIRECT_TOOL_ACTION_REQUEST.matcher(view.masked())
                .region(view.sentenceStartBefore(0, actionStart), actionStart).matches();
    }

    static boolean directCollaborativeAuthorityContext(String active) {
        String value = active == null ? "" : active;
        if (BARE_DIRECT_NECESSITY_END.matcher(value).find()
                || positiveDirectiveFrame(value)) {
            return true;
        }
        Matcher control = DIRECT_CONTROL_COMPLEMENT_END.matcher(value);
        if (control.find()) {
            String subject = value.substring(
                    thirdPartySubjectStart(value, control.start()),
                    control.start());
            return directFirstPersonSubject(subject);
        }
        Matcher necessity = DIRECT_COLLABORATIVE_NECESSITY_END.matcher(value);
        if (necessity.find()) {
            String subject = value.substring(
                    thirdPartySubjectStart(value, necessity.start()),
                    necessity.start());
            return runtimeCollaborativeSubject(subject);
        }
        Matcher modal = MODAL_ACTOR.matcher(value);
        return modal.find() && runtimeCollaborativeSubject(modal.group(1));
    }

    static boolean positiveDirectiveFrame(String active) {
        String value = active == null ? "" : active;
        int frameStart = lastPositiveDirectiveFrameStart(value);
        if (frameStart < 0) return false;
        String lead = value.substring(0, frameStart);
        return restrictiveAuthority(lead) == RestrictiveAuthority.NONE
                && !OPEN_PROHIBITION.matcher(lead).find();
    }

    private static boolean positiveDirectiveSyntax(String active) {
        return lastPositiveDirectiveFrameStart(
                active == null ? "" : active) >= 0;
    }

    private static int lastPositiveDirectiveFrameStart(String value) {
        int start = -1;
        for (Pattern frame : List.of(
                DIRECT_ACTOR_IMPERATIVE_END,
                POSITIVE_IMPERATIVE_END)) {
            Matcher matcher = frame.matcher(value == null ? "" : value);
            while (matcher.find()) {
                start = Math.max(start, matcher.start());
            }
        }
        Matcher performative = DIRECT_FIRST_PERSON_PERFORMATIVE.matcher(
                value == null ? "" : value);
        while (performative.find()) {
            if (directFirstPersonSubject(performative.group(1))) {
                start = Math.max(start, performative.start(1));
            }
        }
        return start;
    }

    private static boolean directFirstPersonSubject(String rawSubject) {
        return collaborativeSubject(rawSubject, Set.of("i", "we"));
    }

    static boolean runtimeCollaborativeSubject(String rawSubject) {
        return collaborativeSubject(
                rawSubject,
                Set.of("we", "you", "talos"));
    }

    private static boolean collaborativeSubject(
            String rawSubject,
            Set<String> directActors
    ) {
        String subject = nearestCommaDelimitedSubject(rawSubject)
                .replaceFirst(
                        "(?iu)^\\s*(?:and|but|then|also|so)\\b\\s*",
                        "");
        if (subject.isBlank() || unsupportedSubjectCoordinator(subject)) {
            return false;
        }
        String[] coordinated = subject.split("(?iu)\\band\\b", -1);
        if (coordinated.length == 1) {
            return directCollaborativeActorSegment(
                    coordinated[0],
                    directActors);
        }
        if (coordinated.length != 2
                || coordinated[0].isBlank()
                || coordinated[1].isBlank()) {
            return false;
        }
        boolean directLeft =
                directCollaborativeActorSegment(
                        coordinated[0],
                        directActors);
        boolean directRight =
                directCollaborativeActorSegment(
                        coordinated[1],
                        directActors);
        return (directLeft
                        && (directRight
                                || plausibleThirdPartySubjectSegment(
                                        coordinated[1])))
                || (directRight
                        && plausibleThirdPartySubjectSegment(coordinated[0]));
    }

    private static boolean unsupportedSubjectCoordinator(String subject) {
        String value = subject == null ? "" : subject;
        return value.matches(
                "(?isu).*(?:[&+/]|\\b(?:or|nor|neither|either)\\b"
                        + "|\\bas\\s+well\\s+as\\b"
                        + "|\\btogether\\s+with\\b).*");
    }

    private static boolean directCollaborativeActorSegment(
            String rawSegment,
            Set<String> directActors
    ) {
        Matcher tokens = THIRD_PARTY_SUBJECT_TOKEN.matcher(
                rawSegment == null ? "" : rawSegment);
        List<String> meaningful = new ArrayList<>();
        while (tokens.find()) {
            String token = tokens.group().toLowerCase(Locale.ROOT);
            if (!DIRECT_DISCOURSE_WORDS.contains(token)) {
                meaningful.add(token);
            }
        }
        return meaningful.size() == 1
                && directActors.contains(meaningful.getFirst());
    }

    private static boolean plausibleThirdPartySubjectSegment(
            String rawSegment
    ) {
        Matcher tokens = THIRD_PARTY_SUBJECT_TOKEN.matcher(
                rawSegment == null ? "" : rawSegment);
        List<String> meaningful = new ArrayList<>();
        while (tokens.find()) {
            String rawToken = tokens.group();
            String token = rawToken.toLowerCase(Locale.ROOT);
            if ("the".equals(token)
                    || DIRECT_DISCOURSE_WORDS.contains(token)) {
                continue;
            }
            if (DIRECT_COLLABORATIVE_ACTORS.contains(token)) return false;
            meaningful.add(rawToken);
        }
        if (meaningful.isEmpty() || meaningful.size() > 4) return false;
        if (THIRD_PARTY_ROLE_SUBJECT_SEGMENT.matcher(
                rawSegment == null ? "" : rawSegment).matches()) {
            return true;
        }
        /*
         * A free-form multi-token Title Case segment is not a bounded actor
         * grammar: it can conceal a predicate and its object (for example,
         * "Alice Anticipates Bob"). Named collaborators therefore use one
         * proper-name token here; multi-token runtime roles are admitted only
         * by THIRD_PARTY_ROLE_SUBJECT_SEGMENT above.
         */
        if (meaningful.size() != 1) return false;
        String token = meaningful.getFirst();
        return Character.isUpperCase(token.codePointAt(0));
    }

    /**
     * A reported/output payload enclosed by a completed quote cannot extend
     * across a following comma-and clause. The quote bytes are masked for
     * grammar matching, so this check deliberately consults the canonical
     * quote ownership before treating that conjunction as a fresh imperative.
     */
    private static String afterClosedQuotedPayloadReset(
            RequestLexicalView view,
            int regionStart,
            String visiblePrefix
    ) {
        int resetStart = closedQuotedPayloadResetStart(
                view,
                regionStart,
                visiblePrefix);
        return resetStart < 0
                ? visiblePrefix
                : visiblePrefix.substring(resetStart);
    }

    static boolean hasClosedQuotedPayloadReset(
            RequestLexicalView view,
            int regionStart,
            String visiblePrefix
    ) {
        return closedQuotedPayloadReset(
                view,
                regionStart,
                visiblePrefix).start() >= 0;
    }

    static int closedQuotedPayloadResetStart(
            RequestLexicalView view,
            int regionStart,
            String visiblePrefix
    ) {
        return closedQuotedPayloadReset(
                view,
                regionStart,
                visiblePrefix).start();
    }

    static int closedQuotedPayloadResetEnd(
            RequestLexicalView view,
            int regionStart,
            String visiblePrefix
    ) {
        return closedQuotedPayloadReset(
                view,
                regionStart,
                visiblePrefix).end();
    }

    static String closedQuotedPayloadOuterOwner(
            RequestLexicalView view,
            int regionStart,
            String visiblePrefix
    ) {
        return closedQuotedPayloadReset(
                view,
                regionStart,
                visiblePrefix).outerOwner();
    }

    private static ClosedQuotedPayloadReset closedQuotedPayloadReset(
            RequestLexicalView view,
            int regionStart,
            String visiblePrefix
    ) {
        if (view == null || visiblePrefix == null || visiblePrefix.isEmpty()) {
            return ClosedQuotedPayloadReset.none();
        }
        Matcher connector = CLOSED_QUOTED_PAYLOAD_RESET.matcher(visiblePrefix);
        ClosedQuotedPayloadReset active = ClosedQuotedPayloadReset.none();
        while (connector.find()) {
            int absoluteConnectorStart = regionStart + connector.start();
            RequestLexicalView.Span envelope =
                    view.completedQuotedEnvelopeImmediatelyBefore(
                            absoluteConnectorStart,
                            regionStart);
            if (envelope == null) continue;
            int relativeEnvelopeStart = envelope.start() - regionStart;
            if (relativeEnvelopeStart < 0
                    || relativeEnvelopeStart > connector.start()) {
                continue;
            }
            String before =
                    visiblePrefix.substring(0, relativeEnvelopeStart);
            int openerStart = openReportedPayloadStart(before);
            if (openerStart < 0) continue;
            String outerOwner = before.substring(0, openerStart);
            active = new ClosedQuotedPayloadReset(
                    connector.start(),
                    connector.end(),
                    outerOwner);
        }
        return active;
    }

    private record ClosedQuotedPayloadReset(
            int start,
            int end,
            String outerOwner
    ) {
        private ClosedQuotedPayloadReset {
            outerOwner = outerOwner == null ? "" : outerOwner;
        }

        static ClosedQuotedPayloadReset none() {
            return new ClosedQuotedPayloadReset(-1, -1, "");
        }
    }

    /**
     * Returns true only when the active clause ending at an action is framed as
     * reported, instructional, advisory, or as a declaration by somebody other
     * than the user/runtime collaboration.
     *
     * <p>The final {@code but}/{@code then} clause reset is intentional:
     * {@code "the help says no, but update x"} is a direct instruction, while
     * {@code "the help says how to update x"} is not. This grammar is semantic;
     * it does not depend on a filename or qualification-scenario vocabulary.
     */
    static boolean nonAuthoritativeContext(String visiblePrefix) {
        String prefix = visiblePrefix == null ? "" : visiblePrefix;
        String active = activeClause(prefix);
        if (active.isBlank()) return false;
        if (hasSubjectBoundReportedContext(prefix)
                || REPORTED_OUTPUT_LABEL.matcher(active).find()
                || ADVISORY_LABEL.matcher(active).find()
                || INSTRUCTIONAL_INFINITIVE.matcher(active).find()
                || thirdPartyContext(active)) {
            return true;
        }
        Matcher modal = MODAL_ACTOR.matcher(active);
        return modal.find() && isThirdPartySubject(modal.group(1));
    }

    static RestrictiveAuthority restrictiveAuthority(String visiblePrefix) {
        String prefix = visiblePrefix == null ? "" : visiblePrefix;
        RestrictiveAuthority carried =
                openRestrictiveAuthorityBeforeTrailingConnector(prefix);
        if (carried != RestrictiveAuthority.NONE) return carried;
        if (PROPOSAL_QUESTION.matcher(prefix).find()
                || METALINGUISTIC_PREFIX.matcher(prefix).find()
                || PROPOSAL_LABEL_PREFIX.matcher(prefix).find()
                || DECLARATIVE_ACTOR_PREFIX.matcher(prefix).find()
                || NEGATED_NECESSITY_PREFIX.matcher(prefix).find()
                || nonAuthoritativeContext(prefix)
                || REPORTED_OR_ADVISORY.matcher(prefix).find()) {
            return RestrictiveAuthority.NON_AUTHORITATIVE;
        }
        if (OPTIONAL_OR_CONDITIONAL.matcher(prefix).find()
                || OPTIONAL_LABEL_PREFIX.matcher(prefix).find()) {
            return RestrictiveAuthority.OPTIONAL;
        }
        return RestrictiveAuthority.NONE;
    }

    private static RestrictiveAuthority
            openRestrictiveAuthorityBeforeTrailingConnector(String prefix) {
        Matcher reset = AUTHORITY_SCOPE_RESET.matcher(prefix);
        int connectorStart = -1;
        int connectorEnd = -1;
        while (reset.find()) {
            if (insidePathToken(prefix, reset.start(), reset.end())
                    || !prefix.substring(reset.end())
                            .matches("[\\s,:;\\-\\u2013\\u2014]*")) {
                continue;
            }
            connectorStart = reset.start();
            connectorEnd = reset.end();
        }
        if (connectorStart < 0 || connectorEnd <= connectorStart) {
            return RestrictiveAuthority.NONE;
        }
        String before = prefix.substring(0, connectorStart);
        return rejectsAuthorityResetWithin(
                        prefix,
                        connectorStart,
                        connectorEnd)
                        && openRestrictiveScope(before)
                ? restrictiveAuthority(before)
                : RestrictiveAuthority.NONE;
    }

    static boolean hasSubjectBoundReportedContext(String visiblePrefix) {
        String prefix = visiblePrefix == null ? "" : visiblePrefix;
        String active = activeClause(prefix);
        return !active.isBlank() && hasSubjectBoundReportedPredicate(active);
    }

    /**
     * Returns the start of a reported/output payload opener only when no
     * payload bytes precede the next structural token.
     *
     * <p>Examples include {@code "verifier output:"},
     * {@code "output contains the instruction to"}, and
     * {@code "the report says:"}. A completed unquoted payload such as
     * {@code "the report says no,"} does not match.
     */
    static int openReportedPayloadStart(String visiblePrefix) {
        String prefix = visiblePrefix == null ? "" : visiblePrefix;
        Matcher output = REPORTED_OUTPUT_LABEL.matcher(prefix);
        if (output.find()) {
            return output.start();
        }
        Matcher predicate = REPORTED_PREDICATE.matcher(prefix);
        while (predicate.find()) {
            if (!prefix.substring(predicate.end()).matches("[\\s:,-]*")) {
                continue;
            }
            String lead = prefix.substring(0, predicate.start());
            Matcher subject = TRAILING_WORD.matcher(lead);
            if (!subject.find()) continue;
            String subjectWord =
                    subject.group(1).toLowerCase(Locale.ROOT);
            if (!NON_SUBJECT_REPORTING_LEADS.contains(subjectWord)
                    && !directFirstPersonDirective(
                            subjectWord,
                            predicate.group(),
                            prefix.substring(predicate.end()))) {
                /*
                 * The reported payload is owned by the reporting subject and
                 * predicate together. Returning the predicate alone leaves
                 * the subject in the alleged outer scope, so a completed
                 * quoted payload such as `the report says "no"` remains
                 * spuriously non-authoritative after its closing delimiter.
                 */
                return subject.start(1);
            }
        }
        return -1;
    }

    private static boolean hasSubjectBoundReportedPredicate(String active) {
        Matcher predicate = REPORTED_PREDICATE.matcher(active);
        while (predicate.find()) {
            String lead = active.substring(0, predicate.start());
            Matcher subject = TRAILING_WORD.matcher(lead);
            if (!subject.find()) continue;
            String subjectWord = subject.group(1).toLowerCase(Locale.ROOT);
            if (!NON_SUBJECT_REPORTING_LEADS.contains(subjectWord)
                    && !directFirstPersonDirective(
                            subjectWord,
                            predicate.group(),
                            active.substring(predicate.end()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean directFirstPersonDirective(
            String subjectWord,
            String predicate,
            String recipient
    ) {
        return Set.of("i", "we").contains(
                        subjectWord == null
                                ? ""
                                : subjectWord.toLowerCase(Locale.ROOT))
                && DIRECT_FIRST_PERSON_DIRECTIVE.matcher(
                        predicate == null ? "" : predicate).matches()
                && DIRECT_COLLABORATIVE_RECIPIENT.matcher(
                        recipient == null ? "" : recipient).matches()
                && !OPEN_PROHIBITION.matcher(
                        recipient == null ? "" : recipient).find()
                && !(recipient == null ? "" : recipient)
                        .matches("(?isu).*\\bnot\\s+to\\b.*");
    }

    static String activeClause(String prefix) {
        String visible = prefix == null ? "" : prefix;
        int start = lastFreshAuthorityResetEnd(visible);
        return visible.substring(start);
    }

    static int lastFreshAuthorityResetEnd(String visible) {
        String value = visible == null ? "" : visible;
        Matcher reset = AUTHORITY_SCOPE_RESET.matcher(value);
        List<AuthorityReset> resets = new ArrayList<>();
        while (reset.find()) {
            resets.add(new AuthorityReset(
                    reset.start(),
                    reset.end(),
                    reset.group().toLowerCase(Locale.ROOT)));
        }
        /*
         * Acceptance of a reset depends only on its own coordinates and the
         * immutable visible prefix. The old forward scan therefore computed
         * every earlier accepted reset even though only the final one could be
         * returned. Evaluate the same predicates newest-first and stop at the
         * first accepted reset. This preserves the grammar while bounding a
         * repeated-action tail to one candidate-prefix analysis per action,
         * rather than re-analysing every earlier connector for every action.
         */
        for (int index = resets.size() - 1; index >= 0; index--) {
            AuthorityReset candidate = resets.get(index);
            if (insidePathToken(value, candidate.start(), candidate.end())) {
                continue;
            }
            String before = value.substring(0, candidate.start());
            String connector = candidate.connector();
            if (NON_RESETTING_SCOPE_LEAD.matcher(before).find()) {
                continue;
            }
            if ("then".equals(connector)
                    && OPEN_CONDITIONAL_SCOPE.matcher(before).find()) {
                continue;
            }
            ScopeKind scope = finiteScopeKind(before);
            if (scope != ScopeKind.NONE
                    && !connectorResetsFiniteScope(
                            scope,
                            before,
                            connector)) {
                continue;
            }
            if (!freshScopeAfterConnector(value, candidate.end())) {
                continue;
            }
            return candidate.end();
        }
        return 0;
    }

    private record AuthorityReset(int start, int end, String connector) {}

    private static boolean connectorResetsFiniteScope(
            ScopeKind scope,
            String before,
            String connector
    ) {
        String value = before == null ? "" : before.stripTrailing();
        if (!value.endsWith(",") || scope == null) return false;
        return switch (scope) {
            case OPTIONAL -> "then".equals(connector);
            case REPORTED_ADVISORY -> "but".equals(connector)
                    || "then".equals(connector)
                            && finiteReportedLiteralComplement(value);
            case PROHIBITED, NONE -> false;
        };
    }

    static boolean rejectsAuthorityResetWithin(
            String visible,
            int connectorStart,
            int connectorEnd
    ) {
        String value = visible == null ? "" : visible;
        int safeStart = Math.max(0, connectorStart);
        int safeEnd = Math.min(
                value.length(),
                Math.max(safeStart, connectorEnd));
        Matcher reset = AUTHORITY_SCOPE_RESET.matcher(value);
        reset.region(safeStart, safeEnd);
        while (reset.find()) {
            if (insidePathToken(value, reset.start(), reset.end())) {
                continue;
            }
            return lastFreshAuthorityResetEnd(
                    value.substring(0, reset.end())) != reset.end();
        }
        return false;
    }

    private static boolean openRestrictiveScope(String visiblePrefix) {
        String prefix = visiblePrefix == null ? "" : visiblePrefix;
        return openReportedPayloadStart(prefix) >= 0
                || endsWithPattern(prefix, PROPOSAL_QUESTION)
                || endsWithPattern(prefix, REPORTED_OR_ADVISORY)
                || endsWithPattern(prefix, METALINGUISTIC_PREFIX)
                || endsWithPattern(prefix, PROPOSAL_LABEL_PREFIX)
                || endsWithPattern(prefix, OPTIONAL_LABEL_PREFIX)
                || endsWithPattern(prefix, DECLARATIVE_ACTOR_PREFIX)
                || thirdPartyContext(prefix)
                || endsWithPattern(prefix, NEGATED_NECESSITY_PREFIX)
                || endsWithPattern(prefix, OPTIONAL_OR_CONDITIONAL)
                || OPEN_PROHIBITION.matcher(prefix).find();
    }

    static ScopeKind finiteScopeKind(String visiblePrefix) {
        String prefix = visiblePrefix == null ? "" : visiblePrefix;
        int positiveFrameStart = positiveNegatedFailureDirectiveStart(prefix);
        String prohibitionOwner = positiveFrameStart >= 0
                ? prefix.substring(0, positiveFrameStart)
                : prefix;
        int prohibitionStart = mutationProhibitionStart(prohibitionOwner);
        boolean reportedBeforeProhibition = prohibitionStart >= 0
                && reportedOrAdvisoryScope(
                        prohibitionOwner.substring(0, prohibitionStart));
        if (prohibitionStart >= 0 && !reportedBeforeProhibition) {
            return ScopeKind.PROHIBITED;
        }
        if (reportedBeforeProhibition || reportedOrAdvisoryScope(prefix)) {
            return ScopeKind.REPORTED_ADVISORY;
        }
        if (prohibitionStart >= 0) {
            return ScopeKind.PROHIBITED;
        }
        if (OPTIONAL_OR_CONDITIONAL.matcher(prefix).find()
                || OPTIONAL_LABEL_PREFIX.matcher(prefix).find()) {
            return ScopeKind.OPTIONAL;
        }
        return ScopeKind.NONE;
    }

    private static boolean reportedOrAdvisoryScope(String visiblePrefix) {
        String prefix = visiblePrefix == null ? "" : visiblePrefix;
        return openReportedPayloadStart(prefix) >= 0
                || hasSubjectBoundReportedPredicate(prefix)
                || thirdPartyContextInActiveClause(prefix)
                || PROPOSAL_QUESTION.matcher(prefix).find()
                || METALINGUISTIC_PREFIX.matcher(prefix).find()
                || PROPOSAL_LABEL_PREFIX.matcher(prefix).find()
                || DECLARATIVE_ACTOR_PREFIX.matcher(prefix).find()
                || NEGATED_NECESSITY_PREFIX.matcher(prefix).find()
                || EXAMPLE_ONLY_SCOPE.matcher(prefix).find()
                || BARE_EXAMPLE_LABEL.matcher(prefix).find()
                || REPORTED_OR_ADVISORY.matcher(prefix).find();
    }

    /** Finite, fail-closed authority carried by bytes after a target. */
    static ScopeKind finitePostfixScopeKind(String visibleSuffix) {
        String suffix = visibleSuffix == null ? "" : visibleSuffix;
        return finitePostfixScopeKind(suffix, 0, suffix.length());
    }

    static ScopeKind finitePostfixScopeKind(String visible, int start, int end) {
        if (POSTFIX_REPORTED_SCOPE.matcher(visible).region(start, end).find()
                || EXAMPLE_ONLY_SCOPE.matcher(visible).region(start, end).find()) {
            return ScopeKind.REPORTED_ADVISORY;
        }
        if (POSTFIX_OPTIONAL_SCOPE.matcher(visible).region(start, end).find()) {
            return ScopeKind.OPTIONAL;
        }
        return ScopeKind.NONE;
    }

    private static int mutationProhibitionStart(String visiblePrefix) {
        String prefix = visiblePrefix == null ? "" : visiblePrefix;
        Matcher prohibition = GENERAL_PROHIBITION.matcher(prefix);
        while (prohibition.find()) {
            if (READ_ONLY_PROHIBITION_COMPLEMENT.matcher(
                    prefix.substring(prohibition.end())).find()) {
                continue;
            }
            return prohibition.start();
        }
        return -1;
    }

    static boolean reportedOwnerPrecedesProhibition(String visiblePrefix) {
        String prefix = visiblePrefix == null ? "" : visiblePrefix;
        int prohibitionStart = mutationProhibitionStart(prefix);
        return prohibitionStart >= 0
                && reportedOrAdvisoryScope(
                        prefix.substring(0, prohibitionStart));
    }

    private static boolean thirdPartyContext(String visiblePrefix) {
        String active = activeClause(
                visiblePrefix == null ? "" : visiblePrefix).strip();
        return thirdPartyContextInActiveClause(active);
    }

    private static boolean thirdPartyContextInActiveClause(String activeClause) {
        String active = activeClause == null ? "" : activeClause.strip();
        if (active.isBlank()) return false;
        if (THIRD_PARTY_ATTRIBUTION_PREFIX.matcher(active).find()
                || THIRD_PARTY_POSSESSIVE_PREFIX.matcher(active).find()) {
            return true;
        }
        Matcher intent = THIRD_PARTY_INTENT_END.matcher(active);
        if (intent.find()) {
            return isThirdPartySubject(
                    active.substring(
                            thirdPartySubjectStart(active, intent.start()),
                            intent.start()));
        }
        Matcher to = THIRD_PARTY_COMPLEMENT_TO_END.matcher(active);
        if (!to.find()) return false;
        String beforeRecipientEnd = active.substring(0, to.start());
        Matcher control = THIRD_PARTY_COMPLEMENT_VERB.matcher(
                beforeRecipientEnd);
        while (control.find()) {
            String recipient = beforeRecipientEnd.substring(
                    control.end()).strip();
            if (!THIRD_PARTY_SUBJECT_TOKEN.matcher(recipient).find()) {
                continue;
            }
            String subject = beforeRecipientEnd.substring(
                    thirdPartySubjectStart(
                            beforeRecipientEnd,
                            control.start()),
                    control.start());
            if (isThirdPartySubject(subject)) return true;
        }
        return false;
    }

    private static int thirdPartySubjectStart(String value, int end) {
        Matcher boundary = THIRD_PARTY_CLAUSE_BOUNDARY.matcher(value);
        int start = 0;
        while (boundary.find()) {
            if (boundary.end() > end) break;
            start = boundary.end();
        }
        return start;
    }

    private static boolean isThirdPartySubject(String rawSubject) {
        String subject = nearestCommaDelimitedSubject(rawSubject);
        if (collaborativeSubject(
                subject,
                DIRECT_COLLABORATIVE_ACTORS)) return false;
        Matcher tokens = THIRD_PARTY_SUBJECT_TOKEN.matcher(
                subject);
        while (tokens.find()) {
            String token = tokens.group().toLowerCase(Locale.ROOT);
            if (!DIRECT_DISCOURSE_WORDS.contains(token)) return true;
        }
        return false;
    }

    private static String nearestCommaDelimitedSubject(String rawSubject) {
        String value = rawSubject == null ? "" : rawSubject;
        String[] segments = value.split(",", -1);
        for (int index = segments.length - 1; index >= 0; index--) {
            if (!segments[index].isBlank()) {
                return segments[index];
            }
        }
        return "";
    }

    static boolean hasOpenProhibitionScope(String visiblePrefix) {
        String prefix = visiblePrefix == null ? "" : visiblePrefix;
        int positiveFrameStart =
                positiveNegatedFailureDirectiveStart(prefix);
        String authoritativePrefix = positiveFrameStart >= 0
                ? prefix.substring(0, positiveFrameStart)
                : prefix;
        if (OPEN_PROHIBITION.matcher(authoritativePrefix).find()
                || GENERAL_PROHIBITION
                        .matcher(authoritativePrefix).find()) {
            return true;
        }
        Matcher reset = AUTHORITY_SCOPE_RESET.matcher(authoritativePrefix);
        int connectorStart = -1;
        while (reset.find()) {
            if (insidePathToken(
                            authoritativePrefix,
                            reset.start(),
                            reset.end())
                    || !authoritativePrefix.substring(reset.end())
                            .matches("[\\s,:;\\-\\u2013\\u2014]*")) {
                continue;
            }
            connectorStart = reset.start();
        }
        return connectorStart >= 0
                && OPEN_PROHIBITION.matcher(
                        authoritativePrefix.substring(
                                0,
                                connectorStart)).find();
    }

    static boolean isPositiveNegatedFailureDirective(String visiblePrefix) {
        return positiveNegatedFailureDirectiveStart(visiblePrefix) >= 0;
    }

    static int positiveNegatedFailureDirectiveStart(String visiblePrefix) {
        String prefix = visiblePrefix == null ? "" : visiblePrefix;
        Matcher directive =
                POSITIVE_NEGATED_FAILURE_DIRECTIVE.matcher(prefix);
        int start = -1;
        while (directive.find()) {
            start = Math.max(start, directive.start());
        }
        return start;
    }

    private static boolean endsWithPattern(String value, Pattern pattern) {
        Matcher matcher = pattern.matcher(value == null ? "" : value);
        while (matcher.find()) {
            if (value.substring(matcher.end())
                    .matches("[\\s,:;\\-\\u2013\\u2014]*")) {
                return true;
            }
        }
        return false;
    }

    private static boolean freshScopeAfterConnector(
            String visible,
            int connectorEnd
    ) {
        String suffix = visible.substring(connectorEnd);
        Matcher action = ACTION.matcher(suffix);
        while (action.find()) {
            if (insidePathToken(suffix, action.start(), action.end())) {
                continue;
            }
            return FRESH_SCOPE_AFTER_CONNECTOR_PREFIX.matcher(
                    suffix.substring(0, action.start())).matches();
        }
        return FRESH_SCOPE_AFTER_CONNECTOR_PREFIX.matcher(suffix).matches();
    }

    static boolean freshAuthorityPrefix(String visiblePrefix) {
        String prefix = visiblePrefix == null ? "" : visiblePrefix;
        return FRESH_SCOPE_AFTER_CONNECTOR_PREFIX.matcher(prefix).matches();
    }

    static boolean finiteReportedLiteralComplement(String visiblePrefix) {
        return FINITE_REPORTED_LITERAL_COMPLEMENT.matcher(
                visiblePrefix == null ? "" : visiblePrefix).find();
    }

    private static boolean insidePathToken(String request, int start, int end) {
        if (request == null || request.isEmpty()) return false;
        return start > 0 && pathJoinCharacter(request.charAt(start - 1))
                || end < request.length()
                && rightPathJoinCharacter(request, end);
    }

    private static boolean rightPathJoinCharacter(String request, int offset) {
        char value = request.charAt(offset);
        if (value != '.') return pathJoinCharacter(value);
        return offset + 1 < request.length()
                && !Character.isWhitespace(request.charAt(offset + 1));
    }

    private static boolean pathJoinCharacter(char value) {
        return value == '_'
                || value == '-'
                || value == '.'
                || value == '/'
                || value == '\\';
    }

    static boolean directlyBindsContentTarget(String text, String target) {
        String request = text == null ? "" : text;
        String normalizedTarget;
        try {
            normalizedTarget = TargetRef.normalizePath(target);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        RequestLexicalView view = RequestLexicalView.of(request);
        String lexicalRequest = request.replace('\\', '/');
        String lexicalTarget = normalizedTarget.replace('\\', '/');
        for (Action action : actions(view, 0, request.length())) {
            if (action.family() != Family.CONTENT_MUTATION) continue;
            if (action.nonImperativeUse()) continue;
            for (int offset = action.end();
                    offset + lexicalTarget.length() <= lexicalRequest.length();
                    offset++) {
                if (!lexicalRequest.regionMatches(
                        false,
                        offset,
                        lexicalTarget,
                        0,
                        lexicalTarget.length())) {
                    continue;
                }
                int start = offset;
                while (start >= 2
                        && lexicalRequest.charAt(start - 2) == '.'
                        && lexicalRequest.charAt(start - 1) == '/') {
                    start -= 2;
                }
                int end = offset + lexicalTarget.length();
                if (!targetBoundary(lexicalRequest, start, end)) continue;
                RequestLexicalView.Span quotedEnvelope =
                        view.exactQuotedEnvelope(start, end);
                if (quotedEnvelope == null
                        && !view.isUnquotedRange(start, end)) {
                    continue;
                }
                int envelopeStart = quotedEnvelope == null
                        ? start
                        : quotedEnvelope.start();
                String gap = view.masked().substring(action.end(), envelopeStart);
                if (DIRECT_TARGET_PREFIX.matcher(gap).matches()) return true;
            }
        }
        return false;
    }

    private static boolean workspaceOperation(String verb) {
        String lower = verb == null ? "" : verb.toLowerCase(Locale.ROOT);
        return lower.equals("mkdir")
                || lower.equals("rename")
                || lower.equals("move")
                || lower.equals("copy")
                || lower.equals("delete")
                || lower.equals("remove")
                || lower.equals("rm")
                || lower.equals("touch")
                || lower.contains("directory")
                || lower.contains("directories")
                || lower.contains("folder")
                || lower.matches(".*\\bdirs?\\b.*");
    }

    static boolean actionMatchesFamily(
            Action action,
            Family expected,
            String trailingContext
    ) {
        if (action == null || expected == null) return false;
        String verb = action.verb().toLowerCase(Locale.ROOT);
        String context = trailingContext == null ? "" : trailingContext;
        boolean naturalWorkspace = ("create".equals(verb)
                        || "make".equals(verb))
                && (DIRECTORY_CONTEXT.matcher(context).find()
                        || actions(
                                RequestLexicalView.of(context),
                                0,
                                context.length()).stream().anyMatch(next ->
                                next.family() == Family.WORKSPACE_OPERATION));
        Family effective = action.family() == Family.WORKSPACE_OPERATION
                        || naturalWorkspace
                ? Family.WORKSPACE_OPERATION
                : Family.CONTENT_MUTATION;
        return effective == expected;
    }

    private static boolean targetBoundary(String value, int start, int end) {
        boolean left = start == 0 || !targetCharacter(value.charAt(start - 1));
        boolean right = end == value.length()
                || !targetCharacter(value.charAt(end))
                || value.charAt(end) == '.'
                && (end + 1 == value.length()
                || Character.isWhitespace(value.charAt(end + 1)));
        return left && right;
    }

    private static boolean targetCharacter(char value) {
        return Character.isLetterOrDigit(value)
                || value == '_'
                || value == '-'
                || value == '.'
                || value == '/'
                || value == '\\';
    }

    record Action(
            Family family,
            String verb,
            int start,
            int end,
            boolean nonImperativeUse,
            ScopeKind scopeKind
    ) {
        Action {
            if (family == null
                    || verb == null
                    || scopeKind == null
                    || start < 0
                    || end <= start) {
                throw new IllegalArgumentException("invalid mutation action");
            }
        }
    }
}
