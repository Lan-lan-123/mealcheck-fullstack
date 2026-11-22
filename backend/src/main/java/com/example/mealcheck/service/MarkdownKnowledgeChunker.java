package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.springframework.stereotype.Component;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.TokenCountEstimator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MarkdownKnowledgeChunker {
    public static final String RECURSIVE = "recursive-markdown";
    public static final String FRAMEWORK = "langchain4j-recursive";
    public static final String STRUCTURAL = "structural-markdown";
    public static final String ENHANCED_FRAMEWORK = "langchain4j-enhanced";
    public static final String RECURSIVE_REVISION = "sentence-heading-boundaries-v2";
    public static final List<String> SELECTABLE_STRATEGIES = List.of(RECURSIVE, FRAMEWORK, STRUCTURAL, ENHANCED_FRAMEWORK);
    // Curated entries have their own context; do not merge or prepend unrelated topics.
    private static final String INDEPENDENT_TOPICS = "<!-- mealcheck:independent-topics -->";
    private static final Pattern HEADING = Pattern.compile("(?m)^#{2,6}\\s+(.+?)\\s*$");
    private static final Pattern TOKEN = Pattern.compile(
            "[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]|[\\p{L}\\p{N}]+(?:[-_'’][\\p{L}\\p{N}]+)*|[^\\s]");
    private static final Pattern NATURAL_BREAK = Pattern.compile(
            "[。！？]+[”’」』）)]*|[.!?]+[\\\"'”’)]*(?=\\s|$)|\\R\\s*\\R+");
    private static final Pattern BLOCK_HEADING = Pattern.compile(
            "(?m)^(?:#{1,6}[ \\t]+[^\\r\\n]+|\\*\\*[^\\r\\n]+?\\*\\*)[ \\t]*$");
    private static final List<Pattern> RECURSIVE_BOUNDARIES = List.of(
            Pattern.compile("\\R\\s*\\R+"),
            Pattern.compile("\\R+"),
            NATURAL_BREAK,
            Pattern.compile("[；;]+"),
            Pattern.compile("[，,、]+"),
            Pattern.compile("\\s+")
    );

    private final AppProperties properties;

    public MarkdownKnowledgeChunker(AppProperties properties) {
        this.properties = properties;
    }

    public List<PgVectorKnowledgeService.KnowledgeChunk> split(String markdown) {
        return split(markdown, properties.getKnowledge().getSplitterStrategy());
    }

    public static String normalizeStrategy(String strategy) {
        String value = strategy == null || strategy.isBlank() ? RECURSIVE : strategy.trim().toLowerCase(Locale.ROOT);
        if (!SELECTABLE_STRATEGIES.contains(value) && !"legacy-window".equals(value))
            throw new IllegalArgumentException("Unsupported knowledge splitter strategy: " + strategy);
        return value;
    }

    /** Explicit per-job selection; never mutate the shared application configuration. */
    public List<PgVectorKnowledgeService.KnowledgeChunk> split(String markdown, String configured) {
        String strategy = normalizeStrategy(configured);
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        return splitMarkdown(markdown, strategy);
    }

    private List<PgVectorKnowledgeService.KnowledgeChunk> splitMarkdown(String markdown,
                                                                         String strategy) {
        List<Section> sections = STRUCTURAL.equals(strategy) ? structuralSections(markdown) : sections(markdown);
        List<DraftChunk> drafts = new ArrayList<>();
        Map<String, Integer> titleOccurrences = new HashMap<>();
        for (Section section : sections) {
            String normalizedTitle = normalizeWhitespace(section.title());
            String titleId = sha256(normalizedTitle.toLowerCase(Locale.ROOT)).substring(0, 16);
            int occurrence = titleOccurrences.merge(titleId, 1, Integer::sum);
            List<TextWindow> windows = switch (strategy) {
                case FRAMEWORK -> frameworkWindows(section.content());
                case ENHANCED_FRAMEWORK -> enhancedFrameworkWindows(section.content());
                case STRUCTURAL -> structuralWindows(section.content());
                case "legacy-window" -> balancedWindows(section.content());
                default -> recursiveWindows(section.content());
            };
            for (int part = 0; part < windows.size(); part++) {
                TextWindow window = windows.get(part);
                drafts.add(new DraftChunk(
                        titleId + "-" + occurrence + "-" + part,
                        normalizedTitle,
                        window.text().trim(),
                        window.tokenCount()));
            }
        }

        boolean independentTopics = markdown.stripLeading().startsWith(INDEPENDENT_TOPICS)
                || FRAMEWORK.equals(strategy) || STRUCTURAL.equals(strategy) || ENHANCED_FRAMEWORK.equals(strategy);
        List<List<DraftChunk>> groups = independentTopics
                ? drafts.stream().map(List::of).toList()
                : mergeShortDrafts(drafts);
        List<PgVectorKnowledgeService.KnowledgeChunk> result = new ArrayList<>(groups.size());
        int chunkIndex = 0;
        String previousContent = null;
        List<DraftChunk> previousGroup = null;
        int overlap = safeOverlap();
        for (List<DraftChunk> group : groups) {
            String title = group.stream().map(DraftChunk::title).distinct().reduce((a, b) -> a + " / " + b).orElse("General");
            String baseContent = renderContent(group);
            boolean alreadyOverlaps = previousGroup != null
                    && previousGroup.size() == 1
                    && group.size() == 1
                    && previousGroup.get(0).title().equals(group.get(0).title());
            String content = !independentTopics && previousContent != null && overlap > 0 && !alreadyOverlaps
                    ? suffixTokens(previousContent, overlap) + "\n\n" + baseContent
                    : baseContent;
            String chunkKey = group.get(0).key() + "--" + group.get(group.size() - 1).key();
            result.add(new PgVectorKnowledgeService.KnowledgeChunk(
                    chunkKey, title, content, chunkIndex++, estimateTokens(content), contentHash(title, content)));
            previousContent = baseContent;
            previousGroup = group;
        }
        return result;
    }

    private List<TextWindow> frameworkWindows(String content) {
        // Use the same estimator as A/C so the experiment does not also change the length unit.
        TokenCountEstimator estimator = new TokenCountEstimator() {
            @Override public int estimateTokenCountInText(String text) { return estimateTokens(text); }
            @Override public int estimateTokenCountInMessage(ChatMessage message) { throw new UnsupportedOperationException(); }
            @Override public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) { throw new UnsupportedOperationException(); }
        };
        return DocumentSplitters.recursive(Math.max(1, properties.getKnowledge().getChunkMaxTokens()), safeOverlap(), estimator)
                .split(Document.from(content)).stream()
                .map(segment -> new TextWindow(segment.text(), estimateTokens(segment.text()))).toList();
    }

    /** B's windows with A's heading/body boundary protection and oversize fallback.
     * Short windows remain valid; do not manufacture overlap or pad to the minimum.
     * Called within one source section so headings never move into another topic.
     */
    private List<TextWindow> enhancedFrameworkWindows(String content) {
        List<TextWindow> originals = frameworkWindows(content);
        List<TextWindow> result = new ArrayList<>();
        String carry = "";
        int max = Math.max(1, properties.getKnowledge().getChunkMaxTokens());
        for (int index = 0; index < originals.size(); index++) {
            String text = carry.isBlank() ? originals.get(index).text()
                    : carry + "\n\n" + originals.get(index).text();
            carry = "";
            // An actual terminal heading has no following body. Preserve it rather
            // than moving it across topics or silently discarding source content.
            if (index + 1 < originals.size()) {
                List<String> paragraphs = new ArrayList<>(List.of(text.trim().split("\\R\\s*\\R+")));
                List<String> headings = new ArrayList<>();
                while (!paragraphs.isEmpty()
                        && BLOCK_HEADING.matcher(paragraphs.get(paragraphs.size() - 1).trim()).matches()) {
                    headings.add(0, paragraphs.remove(paragraphs.size() - 1));
                }
                if (!headings.isEmpty()) {
                    text = String.join("\n\n", paragraphs);
                    carry = String.join("\n\n", headings);
                }
            }
            if (text.isBlank()) continue;
            int tokens = estimateTokens(text);
            if (tokens > max) result.addAll(recursiveWindows(text));
            else result.add(new TextWindow(text.trim(), tokens));
        }
        return result;
    }

    private List<Section> structuralSections(String markdown) {
        Pattern subheading = Pattern.compile("(?m)^\\*\\*([^\\r\\n]+?)\\*\\*\\s*$");
        List<Section> result = new ArrayList<>();
        for (Section topic : sections(markdown)) {
            Matcher matcher = subheading.matcher(topic.content());
            String title = topic.title();
            int start = 0;
            while (matcher.find()) {
                String body = topic.content().substring(start, matcher.start()).trim();
                if (!body.isBlank()) result.add(new Section(title, body));
                title = topic.title() + " / " + matcher.group(1).trim();
                start = matcher.end();
            }
            String body = topic.content().substring(start).trim();
            if (!body.isBlank()) result.add(new Section(title, body));
        }
        return result;
    }

    /** Pack whole paragraphs/list blocks; only oversized units fall back to recursive splitting. */
    private List<TextWindow> structuralWindows(String content) {
        int max = Math.max(1, properties.getKnowledge().getChunkMaxTokens());
        int overlap = safeOverlap();
        int budget = max - overlap;
        List<String> units = new ArrayList<>();
        StringBuilder fenced = new StringBuilder();
        boolean inFence = false;
        for (String paragraph : content.split("\\R\\s*\\R+")) {
            if (paragraph.isBlank()) continue;
            if (inFence || paragraph.stripLeading().startsWith("```") || paragraph.stripLeading().startsWith("~~~")) {
                if (!fenced.isEmpty()) fenced.append("\n\n");
                fenced.append(paragraph);
                long fences = paragraph.lines().filter(line -> line.stripLeading().startsWith("```") || line.stripLeading().startsWith("~~~")).count();
                if (fences % 2 == 1) inFence = !inFence;
                if (!inFence) { units.add(fenced.toString()); fenced.setLength(0); }
            } else units.add(paragraph.trim());
        }
        if (!fenced.isEmpty()) units.add(fenced.toString());
        List<TextWindow> bases = new ArrayList<>();
        String current = "";
        for (String unit : units) {
            if (estimateTokens(unit) > budget) {
                if (!current.isBlank()) { bases.add(new TextWindow(current, estimateTokens(current))); current = ""; }
                bases.addAll(recursiveWindows(unit, budget, Math.min(budget, Math.max(1, properties.getKnowledge().getChunkMinTokens())), 0));
            } else {
                String candidate = current.isBlank() ? unit : current + "\n\n" + unit;
                if (estimateTokens(candidate) > budget) {
                    bases.add(new TextWindow(current, estimateTokens(current)));
                    current = unit;
                } else current = candidate;
            }
        }
        if (!current.isBlank()) bases.add(new TextWindow(current, estimateTokens(current)));
        List<TextWindow> result = new ArrayList<>();
        String previous = null;
        for (TextWindow base : bases) {
            String text = previous != null && overlap > 0 ? suffixTokens(previous, overlap) + "\n\n" + base.text() : base.text();
            result.add(new TextWindow(text, estimateTokens(text)));
            previous = base.text();
        }
        return result;
    }

    public int estimateTokens(String text) {
        return tokenSpans(text == null ? "" : text).size();
    }

    public String contentHash(String title, String content) {
        return sha256(normalizeWhitespace(title) + "\n\n" + normalizeWhitespace(content));
    }

    private List<Section> sections(String markdown) {
        Matcher matcher = HEADING.matcher(markdown);
        List<HeadingMatch> headings = new ArrayList<>();
        while (matcher.find()) {
            headings.add(new HeadingMatch(matcher.group(1), matcher.end(), matcher.start()));
        }
        List<Section> sections = new ArrayList<>();
        for (int i = 0; i < headings.size(); i++) {
            HeadingMatch current = headings.get(i);
            int end = i + 1 < headings.size() ? headings.get(i + 1).start() : markdown.length();
            String content = markdown.substring(current.contentStart(), end).trim();
            if (!content.isBlank()) {
                sections.add(new Section(current.title(), content));
            }
        }
        if (sections.isEmpty()) {
            String content = markdown.trim();
            if (!content.isBlank()) {
                sections.add(new Section("General", content));
            }
        }
        return sections;
    }

    private List<TextWindow> balancedWindows(String content) {
        List<TokenSpan> tokens = tokenSpans(content);
        if (tokens.isEmpty()) {
            return List.of();
        }

        int max = Math.max(1, properties.getKnowledge().getChunkMaxTokens());
        int min = Math.max(1, Math.min(properties.getKnowledge().getChunkMinTokens(), max));
        int overlap = safeOverlap();
        int count = Math.max(1, (int) Math.ceil((double) Math.max(1, tokens.size() - overlap) / (max - overlap)));
        while (count > 1 && (int) Math.ceil((double) (tokens.size() + (count - 1) * overlap) / count) < min) {
            count--;
        }

        int windowSize = (int) Math.ceil((double) (tokens.size() + (count - 1) * overlap) / count);
        int step = Math.max(1, windowSize - overlap);
        List<TextWindow> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int fromToken = Math.min(i * step, tokens.size() - 1);
            int toToken = i == count - 1 ? tokens.size() : Math.min(tokens.size(), fromToken + windowSize);
            TokenSpan first = tokens.get(fromToken);
            TokenSpan last = tokens.get(toToken - 1);
            result.add(new TextWindow(content.substring(first.start(), last.end()), toToken - fromToken));
        }
        return result;
    }

    private List<TextWindow> recursiveWindows(String content) {
        int max = Math.max(1, properties.getKnowledge().getChunkMaxTokens());
        int min = Math.max(1, Math.min(properties.getKnowledge().getChunkMinTokens(), max));
        return recursiveWindows(content, max, min, safeOverlap());
    }

    private List<TextWindow> recursiveWindows(String content, int max, int min, int overlap) {
        List<TokenSpan> tokens = tokenSpans(content);
        if (tokens.isEmpty()) {
            return List.of();
        }

        if (tokens.size() <= max) {
            return List.of(new TextWindow(content.trim(), tokens.size()));
        }

        List<TextWindow> result = new ArrayList<>();
        boolean[] naturalBreaks = naturalBreaks(content, tokens);
        int fromToken = 0;
        while (fromToken < tokens.size()) {
            int remaining = tokens.size() - fromToken;
            int toToken;
            if (remaining <= max) {
                toToken = tokens.size();
            } else {
                int minEnd = Math.min(tokens.size(), fromToken + min);
                int maxEnd = Math.min(tokens.size(), fromToken + max);

                // Tail length is enforced when selecting the next complete start.
                // Clamping this end using a fixed overlap can cut a sentence even
                // though an earlier, complete overlapping start is available.
                toToken = recursiveBoundary(content, tokens, minEnd, maxEnd, 0, naturalBreaks);
            }

            TokenSpan first = tokens.get(fromToken);
            TokenSpan last = tokens.get(toToken - 1);
            result.add(new TextWindow(
                    content.substring(first.start(), last.end()).trim(),
                    toToken - fromToken));
            if (toToken >= tokens.size()) {
                break;
            }
            int defaultNext = Math.max(fromToken + 1, toToken - overlap);
            int latestStartWithMinimumTail = tokens.size() - min;
            int next = latestStartWithMinimumTail > fromToken
                    ? Math.min(defaultNext, latestStartWithMinimumTail)
                    : defaultNext;
            // A fixed token suffix often begins in the middle of a condition. Prefer
            // a complete sentence/paragraph near the overlap budget instead. The
            // budget is a target, not a request to manufacture a sentence fragment.
            int latest = Math.min(toToken, Math.max(fromToken + 1, latestStartWithMinimumTail));
            int earliest = Math.max(fromToken + 1, toToken - Math.max(overlap, max - min));
            int aligned = -1;
            if (overlap > 0) {
                for (int candidate = Math.min(next, latest); candidate >= earliest; candidate--)
                    if (naturalBreaks[candidate]) { aligned = candidate; break; }
            }
            if (aligned < 0) for (int candidate = Math.max(next, earliest); candidate <= latest; candidate++)
                if (naturalBreaks[candidate]) { aligned = candidate; break; }
            // A short final tail may require more context than the usual overlap
            // target. Keep a complete earlier sentence rather than clamping its
            // start to an arbitrary token just to reach the minimum length.
            if (aligned < 0) for (int candidate = Math.min(next, latest); candidate > fromToken; candidate--)
                if (naturalBreaks[candidate]) { aligned = candidate; break; }
            fromToken = aligned >= 0 ? aligned : next;
        }
        return result;
    }

    private boolean[] naturalBreaks(String content, List<TokenSpan> tokens) {
        boolean[] breaks = new boolean[tokens.size() + 1];
        breaks[0] = true;
        breaks[tokens.size()] = true;
        Matcher matcher = NATURAL_BREAK.matcher(content);
        while (matcher.find()) breaks[tokenCountBefore(tokens, matcher.end())] = true;
        matcher = BLOCK_HEADING.matcher(content);
        while (matcher.find()) {
            int start = tokenCountBefore(tokens, matcher.start());
            int end = tokenCountBefore(tokens, matcher.end());
            breaks[start] = true;
            // Keep a heading with its first body sentence: neither an orphaned
            // heading at the end nor a start inside its Markdown delimiters.
            for (int index = start + 1; index <= end; index++) breaks[index] = false;
        }
        return breaks;
    }

    private int tokenCountBefore(List<TokenSpan> tokens, int characterOffset) {
        int low = 0, high = tokens.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (tokens.get(middle).end() <= characterOffset) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private int recursiveBoundary(String content,
                                  List<TokenSpan> tokens,
                                  int minEnd,
                                  int maxEnd,
                                  int boundaryLevel, boolean[] naturalBreaks) {
        if (boundaryLevel >= RECURSIVE_BOUNDARIES.size() || minEnd >= maxEnd) {
            for (int candidate = maxEnd; candidate >= minEnd; candidate--)
                if (naturalBreaks[candidate]) return candidate;
            return maxEnd;
        }

        int startOffset = tokens.get(minEnd - 1).end();
        int endOffset = tokens.get(maxEnd - 1).end();
        Matcher matcher = RECURSIVE_BOUNDARIES.get(boundaryLevel).matcher(content);
        matcher.region(Math.min(startOffset, endOffset), endOffset);
        int selected = -1;
        while (matcher.find()) {
            int candidate = tokenIndexAtOrBefore(tokens, matcher.end(), minEnd, maxEnd);
            if (candidate >= minEnd && naturalBreaks[candidate]) {
                selected = candidate;
            }
        }
        return selected >= minEnd
                ? selected
                : recursiveBoundary(content, tokens, minEnd, maxEnd, boundaryLevel + 1, naturalBreaks);
    }

    private int tokenIndexAtOrBefore(List<TokenSpan> tokens,
                                     int characterOffset,
                                     int minEnd,
                                     int maxEnd) {
        int selected = -1;
        for (int tokenIndex = minEnd; tokenIndex <= maxEnd; tokenIndex++) {
            if (tokens.get(tokenIndex - 1).end() <= characterOffset) {
                selected = tokenIndex;
            } else {
                break;
            }
        }
        return selected;
    }

    private List<List<DraftChunk>> mergeShortDrafts(List<DraftChunk> drafts) {
        if (drafts.isEmpty()) {
            return List.of();
        }
        int min = Math.max(1, Math.min(
                properties.getKnowledge().getChunkMinTokens(),
                Math.max(1, properties.getKnowledge().getChunkMaxTokens())));
        int configuredMax = Math.max(min, properties.getKnowledge().getChunkMaxTokens());
        int max = Math.max(min, configuredMax - safeOverlap());
        List<List<DraftChunk>> groups = new ArrayList<>();
        List<DraftChunk> current = new ArrayList<>();
        for (DraftChunk draft : drafts) {
            if (current.isEmpty()) {
                current.add(draft);
                continue;
            }
            int currentTokens = estimateTokens(renderContent(current));
            List<DraftChunk> candidate = new ArrayList<>(current);
            candidate.add(draft);
            int candidateTokens = estimateTokens(renderContent(candidate));
            if (currentTokens < min && candidateTokens <= max) {
                current.add(draft);
            } else {
                groups.add(List.copyOf(current));
                current.clear();
                current.add(draft);
            }
        }
        if (!current.isEmpty()) {
            if (!groups.isEmpty() && estimateTokens(renderContent(current)) < min) {
                List<DraftChunk> previous = new ArrayList<>(groups.get(groups.size() - 1));
                List<DraftChunk> tail = new ArrayList<>(current);
                while (previous.size() > 1 && estimateTokens(renderContent(tail)) < min) {
                    tail.add(0, previous.remove(previous.size() - 1));
                }
                if (estimateTokens(renderContent(previous)) >= min
                        && estimateTokens(renderContent(previous)) <= max
                        && estimateTokens(renderContent(tail)) >= min
                        && estimateTokens(renderContent(tail)) <= max) {
                    groups.set(groups.size() - 1, List.copyOf(previous));
                    groups.add(List.copyOf(tail));
                } else {
                    previous.addAll(tail);
                    if (estimateTokens(renderContent(previous)) <= max) {
                        groups.set(groups.size() - 1, List.copyOf(previous));
                    } else {
                        groups.add(List.copyOf(current));
                    }
                }
            } else {
                groups.add(List.copyOf(current));
            }
        }
        return groups;
    }

    private String renderContent(List<DraftChunk> group) {
        if (group.size() == 1) {
            return group.get(0).content();
        }
        StringBuilder content = new StringBuilder();
        for (DraftChunk draft : group) {
            if (!content.isEmpty()) {
                content.append("\n\n");
            }
            content.append("## ").append(draft.title()).append('\n').append(draft.content());
        }
        return content.toString();
    }

    private List<TokenSpan> tokenSpans(String text) {
        List<TokenSpan> spans = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            spans.add(new TokenSpan(matcher.start(), matcher.end()));
        }
        return spans;
    }

    private String suffixTokens(String text, int count) {
        List<TokenSpan> tokens = tokenSpans(text);
        if (tokens.isEmpty()) {
            return "";
        }
        int from = Math.max(0, tokens.size() - count);
        return text.substring(tokens.get(from).start(), tokens.get(tokens.size() - 1).end());
    }

    private int safeOverlap() {
        int max = Math.max(1, properties.getKnowledge().getChunkMaxTokens());
        return Math.max(0, Math.min(properties.getKnowledge().getChunkOverlapTokens(), max - 1));
    }

    private String normalizeWhitespace(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash knowledge content", e);
        }
    }

    private record HeadingMatch(String title, int contentStart, int start) {}
    private record Section(String title, String content) {}
    private record TokenSpan(int start, int end) {}
    private record TextWindow(String text, int tokenCount) {}
    private record DraftChunk(String key, String title, String content, int tokenCount) {}
}
