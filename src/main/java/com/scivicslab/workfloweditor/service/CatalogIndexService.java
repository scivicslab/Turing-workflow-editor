package com.scivicslab.workfloweditor.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;

import java.io.IOException;
import java.util.*;

@ApplicationScoped
public class CatalogIndexService {

    public record Entry(String filename, String path, String name, String description) {}

    public record SearchResult(long total, int page, int size, List<Entry> results) {}

    private volatile IndexSearcher searcher = null;
    private volatile List<Entry> allEntries = List.of();
    private final StandardAnalyzer analyzer = new StandardAnalyzer();

    public synchronized void rebuild(List<Entry> entries) throws IOException {
        var dir = new ByteBuffersDirectory();
        var config = new IndexWriterConfig(analyzer);
        try (var writer = new IndexWriter(dir, config)) {
            for (var e : entries) {
                var doc = new Document();
                doc.add(new StringField("filename", e.filename(), Field.Store.YES));
                doc.add(new StoredField("path", e.path()));
                doc.add(new TextField("name", e.name(), Field.Store.YES));
                doc.add(new TextField("description", e.description(), Field.Store.YES));
                doc.add(new SortedDocValuesField("filename_sort",
                        new org.apache.lucene.util.BytesRef(e.filename().toLowerCase())));
                writer.addDocument(doc);
            }
        }
        searcher = new IndexSearcher(DirectoryReader.open(dir));
        allEntries = List.copyOf(entries);
    }

    public SearchResult search(String query, int page, int size) throws IOException, ParseException {
        if (searcher == null) return new SearchResult(0, page, size, List.of());

        TopDocs hits;
        if (query == null || query.isBlank()) {
            hits = searcher.search(new MatchAllDocsQuery(),
                    Integer.MAX_VALUE, new Sort(new SortField("filename_sort", SortField.Type.STRING)));
        } else {
            var parser = new MultiFieldQueryParser(
                    new String[]{"filename", "name", "description"}, analyzer);
            var q = parser.parse(MultiFieldQueryParser.escape(query.trim()) + "*");
            hits = searcher.search(q, Integer.MAX_VALUE,
                    new Sort(new SortField("filename_sort", SortField.Type.STRING)));
        }

        long total = hits.totalHits.value;
        int from = page * size;
        List<Entry> results = new ArrayList<>();
        for (int i = from; i < Math.min(from + size, hits.scoreDocs.length); i++) {
            var doc = searcher.storedFields().document(hits.scoreDocs[i].doc);
            results.add(new Entry(
                    doc.get("filename"),
                    doc.get("path"),
                    doc.get("name"),
                    doc.get("description")));
        }
        return new SearchResult(total, page, size, results);
    }
}
