package com.evops.aquaculture.csv;

/**
 * 一条数据行：保留原始文件物理行号（表头为第 1 行）与原始文本。
 * 空行（去除空白后为空）在解析阶段被剔除，不进入数据行列表。
 */
public class DataLine {

    private final int lineNo;
    private final String text;

    public DataLine(int lineNo, String text) {
        this.lineNo = lineNo;
        this.text = text;
    }

    public int getLineNo() {
        return lineNo;
    }

    public String getText() {
        return text;
    }
}
