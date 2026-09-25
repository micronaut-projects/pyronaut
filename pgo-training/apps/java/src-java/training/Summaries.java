package training;

import io.micronaut.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Java counterpart of the Python training transform: JSON parsing, regular expressions,
 * date arithmetic and hashing.
 */
final class Summaries {
    private static final Pattern WORD = Pattern.compile("[a-z]+");
    private static final JsonMapper JSON = JsonMapper.createDefault();

    private Summaries() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> summarize(String text) {
        Map<String, Object> document;
        try {
            document = JSON.readValue(text, Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Integer> words = new TreeMap<>();
        Matcher matcher = WORD.matcher(String.valueOf(document.getOrDefault("notes", "")).toLowerCase());
        while (matcher.find()) {
            words.merge(matcher.group(), 1, Integer::sum);
        }
        List<Map<String, Object>> visits = new ArrayList<>((List<Map<String, Object>>) document.getOrDefault("visits", List.of()));
        visits.sort(Comparator.comparing(visit -> LocalDateTime.parse((String) visit.get("when"))));
        Map<String, Double> costByPet = new LinkedHashMap<>();
        for (Map<String, Object> visit : visits) {
            costByPet.merge((String) visit.get("pet"), ((Number) visit.get("cost")).doubleValue(), Double::sum);
        }
        long spanDays = visits.isEmpty() ? 0 : Duration.between(
            LocalDateTime.parse((String) visits.get(0).get("when")),
            LocalDateTime.parse((String) visits.get(visits.size() - 1).get("when"))).toDays();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("topWords", words.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(5)
            .map(Map.Entry::getKey)
            .toList());
        result.put("visits", visits.size());
        result.put("spanDays", spanDays);
        result.put("costByPet", costByPet);
        result.put("digest", digest(text));
        return result;
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
