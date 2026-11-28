package com.blueai.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** V18 must produce the same usable catalogue schema after every supported upgrade path. */
final class ProductSchemaAssertions {
    private ProductSchemaAssertions() { }

    static void verify(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT sku,name,region,cost_price,selling_price,currency_code,duration_days,volume_mb,
                            payload,search_text,location_codes,available,synced_at,source_base_url,source_environment,snapshot_id
                     FROM products WHERE 1=0
                     """)) {
            var columns = result.getMetaData();
            assertThat(columns.getColumnCount()).isEqualTo(16);
            assertThat(columns.getColumnType(1)).isEqualTo(Types.VARCHAR);
            assertThat(columns.getPrecision(1)).isEqualTo(160);
            assertThat(columns.getColumnType(5)).isEqualTo(Types.DECIMAL);
            assertThat(columns.getPrecision(5)).isEqualTo(19);
            assertThat(columns.getScale(5)).isEqualTo(4);
            assertThat(result.next()).isFalse();
        }
        var metadata = connection.getMetaData();
        List<String> primaryKey = new ArrayList<>();
        try (var keys = metadata.getPrimaryKeys(connection.getCatalog(), null, "products")) {
            while (keys.next()) primaryKey.add(keys.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        assertThat(primaryKey).containsExactly("sku");
        Map<String, List<String>> indexes = new LinkedHashMap<>();
        try (var rows = metadata.getIndexInfo(connection.getCatalog(), null, "products", false, false)) {
            while (rows.next()) {
                String index = rows.getString("INDEX_NAME"), column = rows.getString("COLUMN_NAME");
                if (index != null && column != null)
                    indexes.computeIfAbsent(index.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                            .add(column.toLowerCase(Locale.ROOT));
            }
        }
        assertThat(indexes.get("idx_products_available_price")).containsExactly("available", "selling_price");
        assertThat(indexes.get("idx_products_synced_at")).containsExactly("synced_at");
        assertThat(indexes.get("idx_products_snapshot")).containsExactly("snapshot_id", "available");
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT answer_status FROM rag_conversation_messages WHERE 1=0")) {
            assertThat(result.getMetaData().getColumnType(1)).isEqualTo(Types.VARCHAR);
        }
    }
}
