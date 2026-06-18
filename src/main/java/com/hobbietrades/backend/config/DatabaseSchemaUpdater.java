package com.hobbietrades.backend.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Ensures photo blob columns are large enough (LONGBLOB).
 * Fixes older databases where Hibernate created a tiny BLOB column.
 */
@Component
@ConditionalOnProperty(name = "hobbietrades.schema.auto-update", havingValue = "true", matchIfMissing = true)
public class DatabaseSchemaUpdater implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public DatabaseSchemaUpdater(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ensureLongBlobColumn("items", "photo_data", true);
            ensureVarcharColumn("items", "photo_mime", 64);
            ensureTextColumn("items", "gallery_urls");
            ensureGalleryTable();
            System.out.println("[Schema] Photo storage columns verified.");
        } catch (Exception e) {
            System.out.println("[Schema] Photo column update skipped: " + e.getMessage());
        }
    }

    private void ensureLongBlobColumn(String table, String column, boolean nullable) {
        if (columnExists(table, column)) {
            jdbc.execute("ALTER TABLE " + table + " MODIFY COLUMN " + column + " LONGBLOB"
                    + (nullable ? " NULL" : " NOT NULL"));
        } else {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " LONGBLOB"
                    + (nullable ? " NULL" : " NOT NULL"));
        }
    }

    private void ensureVarcharColumn(String table, String column, int size) {
        if (!columnExists(table, column)) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " VARCHAR(" + size + ") NULL");
        }
    }

    private void ensureTextColumn(String table, String column) {
        if (!columnExists(table, column)) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " TEXT NULL");
        }
    }

    private void ensureGalleryTable() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS item_gallery_images (
              id INT AUTO_INCREMENT PRIMARY KEY,
              item_id INT NOT NULL,
              slot INT NOT NULL,
              image_data LONGBLOB NOT NULL,
              mime_type VARCHAR(64),
              UNIQUE KEY uk_item_slot (item_id, slot),
              CONSTRAINT fk_gallery_item FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE CASCADE
            )
            """);
        if (columnExists("item_gallery_images", "image_data")) {
            jdbc.execute("ALTER TABLE item_gallery_images MODIFY COLUMN image_data LONGBLOB NOT NULL");
        }
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """,
                Integer.class,
                table, column);
        return count != null && count > 0;
    }
}
