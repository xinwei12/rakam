package org.rakam.report;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.rakam.collection.SchemaField;
import org.rakam.collection.event.metastore.QueryMetadataStore;
import org.rakam.plugin.ContinuousQuery;
import org.rakam.plugin.ContinuousQueryService;
import org.rakam.util.QueryFormatter;

import javax.inject.Inject;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static java.lang.String.format;

public class PrestoContinuousQueryService extends ContinuousQueryService {
    public final static String PRESTO_STREAMING_CATALOG_NAME = "streaming";
    private final QueryMetadataStore database;
    private final PrestoQueryExecutor executor;
    private final PrestoConfig config;

    @Inject
    public PrestoContinuousQueryService(QueryMetadataStore database, PrestoQueryExecutor executor, PrestoConfig config) {
        super(database);
        this.database = database;
        this.executor = executor;
        this.config = config;
    }

    @Override
    public CompletableFuture<QueryResult> create(ContinuousQuery report) {
        StringBuilder builder = new StringBuilder();

        new QueryFormatter(builder, name -> {
            if(name.getParts().size() == 1) {
                return "_source.\""+report.project+"\".\""+name.toString()+"\"";
            }
            return executor.formatTableReference(report.project, name);
        }).process(report.getQuery(), 1);

        String prestoQuery = format("create view %s.\"%s\".\"%s\" as %s", PRESTO_STREAMING_CATALOG_NAME,
                report.project, report.tableName, builder.toString());
        return executor.executeRawQuery(prestoQuery).getResult().thenApply(result -> {
            if(result.getError() == null) {
                database.createContinuousQuery(report);
                return QueryResult.empty();
            }
            return QueryResult.empty();
        });
    }

    @Override
    public CompletableFuture<Boolean> delete(String project, String name) {
        ContinuousQuery continuousQuery = database.getContinuousQuery(project, name);

        String prestoQuery = format("drop view %s.\"%s\".\"%s\"", PRESTO_STREAMING_CATALOG_NAME,
                continuousQuery.project, continuousQuery.tableName);
        return executor.executeRawQuery(prestoQuery).getResult().thenApply(result -> {
            if(result.getError() == null) {
                database.createContinuousQuery(continuousQuery);
                return true;
            }
            return false;
        });
    }

    @Override
    public Map<String, List<SchemaField>> getSchemas(String project) {
        List<SimpleImmutableEntry<String, CompletableFuture<QueryResult>>> collect = database.getContinuousQueries(project).stream()
                .map(query -> {
                    PrestoQueryExecution prestoQueryExecution = executor.executeRawQuery(format("select * from %s.\"%s\".\"%s\" limit 0",
                            PRESTO_STREAMING_CATALOG_NAME, project, query.tableName));
                    return new SimpleImmutableEntry<>(query.tableName, prestoQueryExecution
                            .getResult());
                }).collect(Collectors.toList());

        CompletableFuture.allOf(collect.stream().map(c -> c.getValue()).toArray(CompletableFuture[]::new)).join();

        ImmutableMap.Builder<String, List<SchemaField>> builder = ImmutableMap.builder();
        for (SimpleImmutableEntry<String, CompletableFuture<QueryResult>> entry : collect) {
            QueryResult join = entry.getValue().join();
            if(join.isFailed()) {
                continue;
            }
            builder.put(entry.getKey(), join.getMetadata());
        }
        return builder.build();
    }

    @Override
    public List<SchemaField> test(String project, String query) {
        StringBuilder builder = new StringBuilder();
        ContinuousQuery continuousQuery = new ContinuousQuery(project, "", "", query, ImmutableList.of(), ImmutableMap.of());

        new QueryFormatter(builder, qualifiedName -> {
            if(qualifiedName.getParts().size() == 2) {
                return config.getColdStorageConnector()+"."+qualifiedName.getPrefix().get()+"."+qualifiedName.getSuffix();
            }
            return executor.formatTableReference(project, qualifiedName);
        }).process(continuousQuery.getQuery(), 1);

        QueryExecution execution = executor
                .executeRawQuery(builder.toString() + " limit 0");
        return execution.getResult().join().getMetadata();
    }

}
