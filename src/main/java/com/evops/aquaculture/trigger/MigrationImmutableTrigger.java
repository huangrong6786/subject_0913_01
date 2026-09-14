package com.evops.aquaculture.trigger;

import org.h2.api.Trigger;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 迁移历史台账防回写触发器（数据库层兜底）。
 *
 * 应用层对 t_cage_migration 只有 insert/select 暴露；触发器在数据库层再兜底：
 * UPDATE/DELETE 无论来自应用 Bug、其他连接还是手工 SQL，都会被拒绝并抛错，
 * 把“历史快照不可回写”落实为存储引擎强制的不变量。
 *
 * 注意：应用代码不对历史表执行 update/delete；Mapper 只继承 BaseMapper 但不暴露修改方法。
 * 若后续扩展需要修正历史，必须新增补充事件（append-only），不能原地改写。
 */
public class MigrationImmutableTrigger implements Trigger {

    @Override
    public void init(Connection conn, String schemaName, String triggerName,
                     String tableName, boolean before, int type) {
        // 无状态，无需初始化。
    }

    @Override
    public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
        throw new SQLException("迁移历史快照为只增台账，禁止更新或删除（历史不可回写）", "42501");
    }

    @Override
    public void close() {
    }

    @Override
    public void remove() {
    }
}
