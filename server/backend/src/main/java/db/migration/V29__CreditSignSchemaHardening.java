package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * V29 C4 考核奖惩 / C6 自定义文件签署 表结构补强（幂等漂移修复）。
 *
 * <p>背景：V27/V28 用 CREATE TABLE IF NOT EXISTS 建表，对开发期手动预建的旧表不生效；
 * 旧表可能缺少控制器实际写入的列（dev 库已出现过 credit_assess.ledger_pdf、
 * sign_task.archive_box 缺失导致 500）。本迁移对 C4/C6 控制器使用的全部列做幂等补齐，
 * 兼容「全新库」（V27/V28 建表后本迁移无操作）与「漂移库」（仅补缺失列）。
 *
 * <p>沿用 V26 的做法：MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS（MariaDB 扩展），
 * 用 information_schema 判断后动态 ALTER；以 Java 迁移实现，避开 Flyway SQL 解析
 * DELIMITER 存储过程的兼容问题。
 */
public class V29__CreditSignSchemaHardening extends BaseJavaMigration {

    @Override
    public void migrate(Context context) {
        JdbcTemplate jdbc = new JdbcTemplate(
                new SingleConnectionDataSource(context.getConnection(), true));

        // ---- C4 考核奖惩 ----
        addColumn(jdbc, "credit_assess", "ledger_pdf", "VARCHAR(500) DEFAULT NULL");
        addColumn(jdbc, "credit_assess", "updated_at", "DATETIME DEFAULT NULL");
        addColumn(jdbc, "credit_assess", "created_by", "BIGINT DEFAULT 0");
        addColumn(jdbc, "credit_assess", "deleted", "TINYINT DEFAULT 0");
        addColumn(jdbc, "credit_assess_item", "targets", "TEXT");
        addColumn(jdbc, "credit_assess_item", "deduction", "INT DEFAULT 0");
        addColumn(jdbc, "credit_assess_item", "deduction_note", "VARCHAR(500) DEFAULT NULL");
        addColumn(jdbc, "credit_assess_item", "level", "VARCHAR(20) DEFAULT NULL");
        addColumn(jdbc, "credit_assess_item", "sign_image", "VARCHAR(500) DEFAULT NULL");
        addColumn(jdbc, "credit_assess_item", "sign_time", "DATETIME DEFAULT NULL");
        addColumn(jdbc, "credit_assess_item", "deleted", "TINYINT DEFAULT 0");

        // ---- C6 自定义文件签署 ----
        addColumn(jdbc, "sign_task", "source_pdf", "VARCHAR(500) DEFAULT NULL");
        addColumn(jdbc, "sign_task", "result_pdf", "VARCHAR(500) DEFAULT NULL");
        addColumn(jdbc, "sign_task", "anchors", "LONGTEXT");
        addColumn(jdbc, "sign_task", "archive_box", "VARCHAR(30) DEFAULT NULL");
        addColumn(jdbc, "sign_task", "signer", "VARCHAR(50) DEFAULT NULL");
        addColumn(jdbc, "sign_task", "signed_at", "DATETIME DEFAULT NULL");
        addColumn(jdbc, "sign_task", "updated_at",
                "DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
        addColumn(jdbc, "sign_task", "created_by", "BIGINT NOT NULL DEFAULT 0");
        addColumn(jdbc, "sign_task", "deleted", "TINYINT NOT NULL DEFAULT 0");

        // ---- 流程日志表（旧表可能存在但缺列）----
        addColumn(jdbc, "sign_flow_log", "detail", "VARCHAR(500) DEFAULT NULL");
        addColumn(jdbc, "sign_flow_log", "from_status", "VARCHAR(20) DEFAULT NULL");
        addColumn(jdbc, "sign_flow_log", "to_status", "VARCHAR(20) DEFAULT NULL");
        addColumn(jdbc, "credit_flow_log", "detail", "VARCHAR(500) DEFAULT NULL");
    }

    private void addColumn(JdbcTemplate jdbc, String table, String column, String definition) {
        Integer tableCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                        "WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class, table);
        if (tableCnt == null || tableCnt == 0) return; // 表由 V27/V28 负责新建，或该版本尚未引入
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                        "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
        if (cnt != null && cnt == 0) {
            jdbc.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
        }
    }
}
