package com.scivicslab.workfloweditor.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CatalogScanner {

    public static List<Map<String, String>> scan(List<Path> dirs) {
        List<Map<String, String>> entries = new ArrayList<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) continue;
            try (var stream = Files.walk(dir)) {
                stream.filter(p -> {
                    String n = p.getFileName().toString();
                    return n.endsWith(".yaml") || n.endsWith(".yml");
                }).forEach(yaml -> {
                    var entry = parseEntry(yaml, dir.toString());
                    if (entry != null) entries.add(entry);
                });
            } catch (IOException e) {
                // skip unreadable dir
            }
        }
        return entries;
    }

    public static Map<String, String> parseEntry(Path yamlFile, String sourceName) {
        try {
            String content = Files.readString(yamlFile);
            return parseContent(content, yamlFile.getFileName().toString(), sourceName,
                    yamlFile.toAbsolutePath().toString());
        } catch (IOException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, String> parseContent(String content, String filename,
                                                    String sourceName, String path) {
        try {
            var yaml = new org.yaml.snakeyaml.Yaml();
            Object loaded = yaml.load(content);
            if (!(loaded instanceof Map)) return null;
            Map<String, Object> map = (Map<String, Object>) loaded;
            if (!map.containsKey("steps") && !map.containsKey("states")) return null;

            String name = filename.replaceAll("\\.ya?ml$", "");
            if (map.get("name") instanceof String s && !s.isBlank()) name = s;

            String description = "";
            if (map.get("description") instanceof String s) description = s;

            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("name", name);
            entry.put("description", description);
            entry.put("file", filename);
            entry.put("source", sourceName);
            entry.put("path", path);
            return entry;
        } catch (Exception e) {
            return null;
        }
    }
}
