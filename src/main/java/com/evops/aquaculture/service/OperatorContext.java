package com.evops.aquaculture.service;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;

/**
 * 操作者解析：迁移必须记录操作者。
 *
 * 优先取请求头 X-Operator-Id / X-Operator-Name（程序化调用显式携带），
 * 缺省时回落到当前 HTTP Basic 登录账号（Shiro Subject），账号名作为 operatorName、
 * 其稳定散列映射为非 0 的 operatorId（0 保留给系统）。
 */
public final class OperatorContext {

    public static final String HEADER_ID = "X-Operator-Id";
    public static final String HEADER_NAME = "X-Operator-Name";

    private OperatorContext() {
    }

    public static long currentOperatorId() {
        String headerId = header(HEADER_ID);
        if (headerId != null && !headerId.isEmpty()) {
            try {
                long id = Long.parseLong(headerId.trim());
                if (id >= 0) {
                    return id;
                }
            } catch (NumberFormatException ignore) {
                // 落到登录账号推导
            }
        }
        String principal = currentPrincipal();
        if (principal == null || principal.isEmpty()) {
            return 0L;
        }
        // 稳定非 0 派生：避免与系统用户 0 冲突，同账号始终映射同一 ID。
        long hash = 1L + (Math.abs((long) principal.hashCode()) % 900_000_000L);
        return hash;
    }

    public static String currentOperatorName() {
        String headerName = header(HEADER_NAME);
        if (headerName != null && !headerName.trim().isEmpty()) {
            return headerName.trim();
        }
        String principal = currentPrincipal();
        return (principal == null || principal.isEmpty()) ? "SYSTEM" : principal;
    }

    private static String currentPrincipal() {
        try {
            org.apache.shiro.SecurityUtils.getSubject();
            Object principal = org.apache.shiro.SecurityUtils.getSubject().getPrincipal();
            return principal == null ? null : principal.toString();
        } catch (Exception ex) {
            // 非 Web/无安全上下文（定时任务、单元直调）
            return null;
        }
    }

    private static String header(String name) {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            String value = attrs.getRequest().getHeader(name);
            if (value == null) {
                return null;
            }
            // HTTP 头按 ISO-8859-1 读入；网关/客户端通常直接承载 UTF-8 字节，按字节还原中文操作者名。
            // 若还原后不是合法 UTF-8 文本（如本就是 ASCII 账号），则原样返回。
            byte[] asIso = value.getBytes(StandardCharsets.ISO_8859_1);
            String utf8 = new String(asIso, StandardCharsets.UTF_8);
            return utf8.equals(value) ? value : utf8;
        } catch (Exception ex) {
            return null;
        }
    }
}
