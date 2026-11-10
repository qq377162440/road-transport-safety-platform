package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * V26 事故/交通违法 表结构补强（幂等漂移修复）。
 *
 * <p>背景：开发期 dev 库部分事故表为手动预建（缺少 V25 定义的列，或字段顺序/默认值与 V25
 * 不一致），导致 V25 的 CREATE TABLE IF NOT EXISTS 被跳过、表结构漂移；且 V25 本身未定义
 * 控制器实际写入的 created_by/deleted/source 列。本迁移对所有控制器使用的列做幂等补齐，
 * 兼容「全新库」（V25 建表后由本迁移补全缺失列）与「漂移库」（仅补齐缺失列）。
 *
 * <p>MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS（MariaDB 扩展），故用 information_schema
 * 判断 + 预处理语句动态加列。以 Java 迁移实现，避免 Flyway SQL 解析 DELIMITER 的兼容问题。
 */
public class V26__AccidentSchemaHardening extends BaseJavaMigration {

    @Override
    public void migrate(Context context) {
        JdbcTemplate jdbc = new JdbcTemplate(
                new SingleConnectionDataSource(context.getConnection(), true));

        // accident_record：补齐 source / created_by（控制器登记时写入）
        addColumn(jdbc, "accident_record", "source", "VARCHAR(20) DEFAULT 'pc'");
        addColumn(jdbc, "accident_record", "created_by", "BIGINT DEFAULT 0");

        // accident_service：补齐 created_by / deleted（控制器申请时写入）
        addColumn(jdbc, "accident_service", "created_by", "BIGINT DEFAULT 0");
        addColumn(jdbc, "accident_service", "deleted", "TINYINT DEFAULT 0");

        // traffic_violation：补齐 created_by（控制器登记违法时写入）
        addColumn(jdbc, "traffic_violation", "created_by", "BIGINT DEFAULT 0");
    }

    private void addColumn(JdbcTemplate jdbc, String table, String column, String definition) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                        "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
        if (cnt != null && cnt == 0) {
            jdbc.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
        }
    }
}
