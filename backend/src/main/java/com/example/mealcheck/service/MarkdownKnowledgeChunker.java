package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.springframework.stereotype.Component;

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
    private static final Pattern HEADING = Pattern.compile("(?m)^#{2,6}\\s+(.+?)\\s*$");
    private static final Pattern TOKEN = Pattern.compile(
            "[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]|[\\p{L}\\p{N}]+(?:[-_'’][\\p{L}\\p{N}]+)*|[^\\s]");
    private static final List<Pattern> RECURSIVE_BOUNDARIES = List.of(
            Pattern.compile("\\R\\s*\\R+"),
            Pattern.compile("\\R+"),
            Pattern.compile("[。！？.!?]+"),
            Pattern.compile("[；;]+"),
            Pattern.compile("[，,、]+"),
            Pattern.compile("\\s+")
    );

    private final AppProperties properties;

    public MarkdownKnowledgeChunker(AppProperties properties) {
        this.properties = properties;
    }

    public List<PgVectorKnowledgeService.KnowledgeChunk> split(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }

        String configured = properties.getKnowledge().getSplitterStrategy();
        String strategy = configured == null || configured.isBlank()
                ? "recursive-markdown"
                : configured.trim().toLowerCase(Locale.ROOT);
        return switch (strategy) {
            case "recursive-markdown" -> splitMarkdown(markdown, true);
            case "legacy-window" -> splitMarkdown(markdown, false);
            default -> throw new IllegalArgumentException(
                    "Unsupported knowledge splitter strategy: " + configured
                            + ". Expected recursive-markdown or legacy-window.");
        };
    }

    private List<PgVectorKnowledgeService.KnowledgeChunk> splitMarkdown(String markdown,
                                                                         boolean recursive) {
        List<Section> sections = sections(markdown);
        List<DraftChunk> drafts = new ArrayList<>();
        Map<String, Integer> titleOccurrences = new HashMap<>();
        for (Section section : sections) {
            String normalizedTitle = normalizeWhitespace(section.title());
            String titleId = sha256(normalizedTitle.toLowerCase(Locale.ROOT)).substring(0, 16);
            int occurrence = titleOccurrences.merge(titleId, 1, Integer::sum);
            List<TextWindow> windows = recursive
                    ? recursiveWindows(section.content())
                    : balancedWindows(section.content());
            for (int part = 0; part < windows.size(); part++) {
                TextWindow window = windows.get(part);
                drafts.add(new DraftChunk(
                        titleId + "-" + occurrence + "-" + part,
                        normalizedTitle,
                        window.text().trim(),
                        window.tokenCount()));
            }
        }

        List<List<DraftChunk>> groups = mergeShortDrafts(drafts);
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
            String content = previousContent != null && overlap > 0 && !alreadyOverlaps
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
        List<TokenSpan> tokens = tokenSpans(content);
        if (tokens.isEmpty()) {
            return List.of();
        }

        int max = Math.max(1, properties.getKnowledge().getChunkMaxTokens());
        int min = Math.max(1, Math.min(properties.getKnowledge().getChunkMinTokens(), max));
        int overlap = safeOverlap();
        if (tokens.size() <= max) {
            return List.of(new TextWindow(content.trim(), tokens.size()));
        }

        List<TextWindow> result = new ArrayList<>();
        int fromToken = 0;
        while (fromToken < tokens.size()) {
            int remaining = tokens.size() - fromToken;
            int toToken;
            if (remaining <= max) {
                toToken = tokens.size();
            } else {
                int minEnd = Math.min(tokens.size(), fromToken + min);
                int maxEnd = Math.min(tokens.size(), fromToken + max);

                // Avoid leaving a final window smaller than the configured minimum after overlap.
                int tailSafeEnd = tokens.size() + overlap - min;
                if (tailSafeEnd >= minEnd) {
                    maxEnd = Math.min(maxEnd, tailSafeEnd);
                }
                toToken = recursiveBoundary(content, tokens, minEnd, maxEnd, 0);
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
            fromToken = latestStartWithMinimumTail > fromToken
                    ? Math.min(defaultNext, latestStartWithMinimumTail)
                    : defaultNext;
        }
        return result;
    }

    private int recursiveBoundary(String content,
                                  List<TokenSpan> tokens,
                                  int minEnd,
                                  int maxEnd,
                                  int boundaryLevel) {
        if (boundaryLevel >= RECURSIVE_BOUNDARIES.size() || minEnd >= maxEnd) {
            return maxEnd;
        }

        int startOffset = tokens.get(minEnd - 1).end();
        int endOffset = tokens.get(maxEnd - 1).end();
        Matcher matcher = RECURSIVE_BOUNDARIES.get(boundaryLevel).matcher(content);
        matcher.region(Math.min(startOffset, endOffset), endOffset);
        int selected = -1;
        while (matcher.find()) {
            int candidate = tokenIndexAtOrBefore(tokens, matcher.end(), minEnd, maxEnd);
            if (candidate >= minEnd) {
                selected = candidate;
            }
        }
        return selected >= minEnd
                ? selected
                : recursiveBoundary(content, tokens, minEnd, maxEnd, boundaryLevel + 1);
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
