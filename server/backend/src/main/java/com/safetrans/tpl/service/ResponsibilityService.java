package com.safetrans.tpl.service;

import com.safetrans.org.entity.OrgPost;
import com.safetrans.org.service.OrgPostService;
import com.safetrans.tenant.entity.TenantInfo;
import com.safetrans.tenant.service.TenantService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 安全生产责任制动态生成器。
 * 读启用岗位（enabled=1）的 goals / duties / assess_std，按 func_key 稳定排序拼装，
 * 岗位名用实时 post_name（改名即联动），禁用岗位自动剔除。
 */
@Service
public class ResponsibilityService {

    private final OrgPostService orgPostService;
    private final TenantService tenantService;

    public ResponsibilityService(OrgPostService orgPostService, TenantService tenantService) {
        this.orgPostService = orgPostService;
        this.tenantService = tenantService;
    }

    /**
     * 责任制结构化数据：启用岗位列表（含岗位名/目标/职责/考核）。
     */
    public List<Map<String, Object>> build() {
        List<OrgPost> posts = orgPostService.enabledPosts();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OrgPost p : posts) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("funcKey", p.getFuncKey());
            row.put("name", p.getPostName());
            row.put("goals", splitLines(p.getGoals()));
            row.put("duties", splitLines(p.getDuties()));
            row.put("assess", splitLines(p.getAssessStd()));
            rows.add(row);
        }
        return rows;
    }

    /**
     * 责任制完整正文（HTML），供预览 / PDF 渲染。
     * 结构：标题 + 制定依据/总则 + 岗位目标分解 + 岗位职责 + 考核标准。
     */
    public String previewHtml() {
        List<Map<String, Object>> rows = build();
        String company = tenantService.currentTenantName();
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"UTF-8\" />")
          .append("<style>")
          .append("body{font-family:'WenQuanYi Zen Hei','WenQuanYi Micro Hei',serif;font-size:12pt;line-height:1.6;}")
          .append("h1{font-family:'WenQuanYi Zen Hei',sans-serif;font-size:18pt;text-align:center;margin:10px 0;}")
          .append("h2{font-family:'WenQuanYi Zen Hei',sans-serif;font-size:14pt;margin:14px 0 6px;}")
          .append("h3{font-size:12pt;margin:10px 0 4px;}")
          .append("ul{margin:2px 0 8px 20px;padding:0;}")
          .append("li{margin:2px 0;}")
          .append("</style>")
          .append("<title>安全生产责任制</title></head><body>");
        sb.append("<h1>安全生产责任制</h1>");
        sb.append("<p style=\"text-align:center;font-weight:bold\">").append(escape(company)).append("</p>");

        sb.append("<h2>一、总则</h2>");
        sb.append("<p>为贯彻“安全第一、预防为主、综合治理”方针，落实全员安全生产责任制，")
          .append("明确各级各岗位安全生产职责，依据《中华人民共和国安全生产法》《中华人民共和国道路运输条例》等法律法规，结合本公司实际，制定本责任制。</p>");

        // 岗位目标分解
        sb.append("<h2>二、岗位安全生产目标</h2>");
        for (Map<String, Object> r : rows) {
            sb.append("<h3>").append(escape((String) r.get("name"))).append("</h3>");
            appendList(sb, (List<String>) r.get("goals"));
        }

        // 岗位职责
        sb.append("<h2>三、岗位安全生产职责</h2>");
        for (Map<String, Object> r : rows) {
            sb.append("<h3>").append(escape((String) r.get("name"))).append("</h3>");
            appendList(sb, (List<String>) r.get("duties"));
        }

        // 考核标准
        sb.append("<h2>四、安全生产考核标准</h2>");
        for (Map<String, Object> r : rows) {
            sb.append("<h3>").append(escape((String) r.get("name"))).append("</h3>");
            appendList(sb, (List<String>) r.get("assess"));
        }

        sb.append("</body></html>");
        return sb.toString();
    }

    /** 把分号/换行分隔的长文本拆成条目列表 */
    private List<String> splitLines(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            result.add("");
            return result;
        }
        for (String seg : text.split("[；;\\n]")) {
            String s = seg.trim();
            if (!s.isEmpty()) {
                result.add(s);
            }
        }
        if (result.isEmpty()) {
            result.add(text.trim());
        }
        return result;
    }

    private void appendList(StringBuilder sb, List<String> items) {
        if (items == null || items.isEmpty()) {
            sb.append("<p>（暂无）</p>");
            return;
        }
        sb.append("<ul>");
        for (String item : items) {
            sb.append("<li>").append(escape(item)).append("</li>");
        }
        sb.append("</ul>");
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
