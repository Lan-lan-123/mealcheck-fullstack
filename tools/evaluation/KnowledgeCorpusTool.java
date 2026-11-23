import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.service.MarkdownKnowledgeChunker;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;

/** Offline bridge to the production splitter. No Spring startup, database or provider calls. */
public class KnowledgeCorpusTool {
    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
        var json = new ObjectMapper();
        var properties = new AppProperties();
        var splitter = new MarkdownKnowledgeChunker(properties);
        if (args.length == 1 && "--jsonl".equals(args[0])) {
            var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    var input = json.readTree(line);
                    var chunks = splitter.split(input.path("markdown").asText());
                    var response = new LinkedHashMap<String, Object>();
                    response.put("chunks", chunks);
                    response.put("tokenCount", splitter.estimateTokens(input.path("markdown").asText()));
                    System.out.println(json.writeValueAsString(response));
                } catch (Exception error) {
                    System.out.println(json.writeValueAsString(java.util.Map.of("error", error.getClass().getSimpleName())));
                }
                System.out.flush();
            }
            return;
        }
        if ((args.length != 3 && args.length != 4) || !"--manifest".equals(args[0]))
            throw new IllegalArgumentException("Use --jsonl, or --manifest CORPUS OUTPUT_UNDER_LOGS [STRATEGY]");
        var root = Path.of("").toAbsolutePath().normalize();
        var source = Path.of(args[1]).toAbsolutePath().normalize();
        var output = Path.of(args[2]).toAbsolutePath().normalize();
        if (!output.startsWith(root.resolve("logs"))) throw new IllegalArgumentException("Output must be under logs/");
        byte[] bytes = Files.readAllBytes(source);
        var strategy = MarkdownKnowledgeChunker.normalizeStrategy(args.length == 4
                ? args[3] : properties.getKnowledge().getSplitterStrategy());
        var chunks = splitter.split(new String(bytes, StandardCharsets.UTF_8), strategy);
        var result = new LinkedHashMap<String, Object>();
        result.put("corpusSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        result.put("splitterStrategy", strategy);
        if (MarkdownKnowledgeChunker.RECURSIVE.equals(strategy))
            result.put("splitterRevision", MarkdownKnowledgeChunker.RECURSIVE_REVISION);
        result.put("recursiveComponentRevision", MarkdownKnowledgeChunker.RECURSIVE_REVISION);
        result.put("minTokens", properties.getKnowledge().getChunkMinTokens());
        result.put("maxTokens", properties.getKnowledge().getChunkMaxTokens());
        result.put("overlapTokens", properties.getKnowledge().getChunkOverlapTokens());
        result.put("topicCount", chunks.stream().map(chunk -> chunk.title()).distinct().count());
        result.put("chunkCount", chunks.size());
        result.put("chunks", chunks);
        Files.createDirectories(output.getParent());
        try (var stream = Files.newOutputStream(output, java.nio.file.StandardOpenOption.CREATE_NEW)) {
            json.writerWithDefaultPrettyPrinter().writeValue(stream, result);
        }
        System.out.println("topics=" + result.get("topicCount") + " chunks=" + chunks.size());
    }
}
