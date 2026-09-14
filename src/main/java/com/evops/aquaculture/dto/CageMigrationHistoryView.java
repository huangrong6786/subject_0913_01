package com.evops.aquaculture.dto;

import com.evops.aquaculture.entity.CageMigration;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 网箱迁移历史链路视图：沿“鱼群（批次）→ 链 → 有序迁移事件”给出不可断裂的时间链，
 * 并对相邻事件做交接守恒校验（前一新对象起始值 == 后一原对象结束值）。
 */
@Data
public class CageMigrationHistoryView {

    private String chainNo;
    private Long batchId;
    private String batchNo;
    private String originCageNo;
    private String currentCageNo;
    private Integer currentSeq;
    /** 链上事件数（= currentSeq） */
    private Integer eventCount;
    /** 链完整性与交接守恒校验结果（全部 true 才是可核对的完整链） */
    private List<CheckItem> checks = new ArrayList<>();
    /** 有序迁移事件（seq 1..N） */
    private List<CageMigration> events = new ArrayList<>();

    @Data
    public static class CheckItem {
        private String name;
        private boolean passed;
        private String detail;

        public static CheckItem of(String name, boolean passed, String detail) {
            CheckItem item = new CheckItem();
            item.name = name;
            item.passed = passed;
            item.detail = detail;
            return item;
        }
    }
}
