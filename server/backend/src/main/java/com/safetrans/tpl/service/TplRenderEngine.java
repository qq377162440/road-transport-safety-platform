package com.safetrans.tpl.service;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 台账范本渲染引擎（范本管理 & 电子档案柜共用的唯一渲染口径）。
 *
 * <p>负责三件事：
 * <ol>
 *   <li>解析用户范本中的条件段落：{% if post == '主要负责人' %} / {% elif %} / {% else %} / {% endif %}</li>
 *   <li>填充变量：{{company_name}}、{{post}}、{{sign_date}}、{{公司名称}} 等，未知变量渲染为可手写填写的空栏</li>
 *   <li>套统一 A4 版式后输出完整 HTML，供前端预览与 PdfService 生成 PDF</li>
 * </ol>
 *
 * <p>范本 HTML 使用 doc-* 类名（来自原型 archive_shared.js 的版式约定），
 * 样例范本中的 no-indent / sign-block 等类名在此一并兼容。
 */
@Component
public class TplRenderEngine {

    /** 变量占位符 {{xxx}}，兼容中文变量名 */
    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([^}]+?)\\s*\\}\\}");

    /** 最内层 if 块（负向前瞻保证块内不含新的 if，从而由内向外逐层消解） */
    private static final Pattern IF_BLOCK = Pattern.compile(
            "\\{%\\s*if\\s+([^%]+?)%\\}((?:(?!\\{%\\s*if).)*?)\\{%\\s*endif\\s*%\\}",
            Pattern.DOTALL);

    /** 单个条件项：key == 'value' / key != 'value' */
    private static final Pattern TERM = Pattern.compile(
            "^\\s*([\\w\\u4e00-\\u9fa5]+)\\s*(==|!=)\\s*['\"]([^'\"]*)['\"]\\s*$");

    /** 空白填写栏（用于未提供的变量，保证打印出来可手写补全） */
    private static final String BLANK = "<span class=\"fill\">&#160;&#160;&#160;&#160;&#160;&#160;&#160;&#160;</span>";

    /** 签字图片类变量渲染为手写签名线 */
    private static final String SIGN_LINE = "<span class=\"sign-underline\">&#160;&#160;&#160;&#160;&#160;&#160;&#160;&#160;</span>";

    /**
     * 构建默认变量表：企业名称、年度、岗位、日期、以及用户样例范本用到的全部变量。
     *
     * @param company  当前企业名称
     * @param year     归档年度
     * @param post     岗位（决定条件段走向，默认「主要负责人」）
     * @param itemName 归档项名称（用于标题）
     */
    public Map<String, String> defaultVars(String company, int year, String post, String itemName) {
        String co = company == null || company.isBlank() ? "____________________" : company;
        String p = post == null || post.isBlank() ? "主要负责人" : post;
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy年M月d日"));
        String iso = LocalDate.now().toString();

        Map<String, String> v = new LinkedHashMap<>();
        // 企业名称（中英文两套变量名，兼容 sys_docs 与样例）
        v.put("company_name", co);
        v.put("公司名称", co);
        v.put("简称", co);
        v.put("title_prefix", co);
        v.put("title", itemName == null || itemName.isBlank() ? "安全生产责任书" : itemName);

        // 岗位
        v.put("post", p);
        v.put("post_title", p);
        v.put("manager_post", "主要负责人");
        v.put("主要负责人", p);
        v.put("法定代表人", p);
        v.put("sign_relation_intro", "根据公司与" + p + "岗位签订的劳动合同及《安全生产责任制》要求");
        v.put("appoint_intro", "经公司研究决定，聘任下列人员担任相应安全管理岗位");
        v.put("dept_scope", "公司各部门、各岗位");

        // 日期 / 年度
        v.put("year", String.valueOf(year));
        v.put("年度", String.valueOf(year));
        v.put("sign_date", date);
        v.put("effect_date", date);
        v.put("start_date", date);
        v.put("period_start", year + "-01-01");
        v.put("period_end", year + "-12-31");
        v.put("发布日期", date);
        v.put("实施日期", date);
        v.put("doc_no", "");

        // 责任人 / 签发人
        v.put("person_name", BLANK);
        v.put("leader_name", BLANK);
        v.put("issuer", BLANK);
        v.put("安全管理员", BLANK);
        v.put("监控员", BLANK);
        v.put("车队长", BLANK);

        // 企业基础信息（应急预案范本用到）
        v.put("企业地址", BLANK);
        v.put("值守电话", BLANK);
        v.put("统一信用代码", BLANK);
        v.put("主营货物", BLANK);
        v.put("主要线路", BLANK);
        v.put("车辆数", BLANK);
        v.put("车辆清单", BLANK);
        v.put("序号", "1");

        // 签字图片：留手写签名线
        v.put("sign_image", SIGN_LINE);
        v.put("manager_sign_image", SIGN_LINE);
        return v;
    }

    /** 把范本内容解析为完整可渲染 HTML 文档 */
    public String renderDocument(String content, Map<String, String> vars) {
        String body = content == null ? "" : content;
        body = resolveConditions(body, vars);
        body = fillVariables(body, vars);
        return wrapHtml(body);
    }

    /** 只输出正文片段（前端 A4 预览容器内嵌用） */
    public String renderBody(String content, Map<String, String> vars) {
        String body = content == null ? "" : content;
        body = resolveConditions(body, vars);
        return fillVariables(body, vars);
    }

    /**
     * 条件段解析：由内向外消解所有 {% if %}...{% endif %}，只保留命中分支的内容。
     */
    public String resolveConditions(String html, Map<String, String> vars) {
        String out = html;
        for (int guard = 0; guard < 20; guard++) {
            Matcher m = IF_BLOCK.matcher(out);
            if (!m.find()) break;
            String replaced = pickBranch(m.group(1), m.group(2), vars);
            out = out.substring(0, m.start()) + replaced + out.substring(m.end());
        }
        // 兜底：清除残留的条件标签，避免 {% %} 出现在 PDF 正文里
        return out.replaceAll("\\{%[^%]*%\\}", "");
    }

    /** 在 if 块内容中选出第一条条件成立的分支 */
    private String pickBranch(String ifCond, String block, Map<String, String> vars) {
        // 按 {% elif %} / {% else %} 切成若干段，段首带条件标签
        String[] parts = block.split("(?=\\{%\\s*(?:elif|else)\\b)");
        String cond = ifCond.trim();
        StringBuilder fallback = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String seg = parts[i];
            String text = seg;
            if (i > 0) {
                Matcher tag = Pattern.compile("^\\{%\\s*(elif|else)\\b([^%]*)%\\}").matcher(seg);
                if (tag.find()) {
                    cond = "else".equals(tag.group(1)) ? null : tag.group(2).trim();
                    text = seg.substring(tag.end());
                }
            }
            if (cond == null) {
                fallback.setLength(0);
                fallback.append(text);
                continue;
            }
            if (eval(cond, vars)) {
                return text;
            }
        }
        return fallback.toString();
    }

    /** 条件求值：支持 ==、!=、or、and，以及裸变量真值判断 */
    private boolean eval(String expr, Map<String, String> vars) {
        if (expr == null || expr.isBlank()) return false;
        for (String orPart : expr.split("\\s+or\\s+")) {
            boolean all = true;
            for (String andPart : orPart.split("\\s+and\\s+")) {
                if (!evalTerm(andPart.trim(), vars)) { all = false; break; }
            }
            if (all) return true;
        }
        return false;
    }

    private boolean evalTerm(String term, Map<String, String> vars) {
        Matcher m = TERM.matcher(term);
        if (m.matches()) {
            String actual = vars.getOrDefault(m.group(1), "");
            boolean eq = actual.equals(m.group(3));
            return "==".equals(m.group(2)) ? eq : !eq;
        }
        String val = vars.get(term);
        return val != null && !val.isBlank();
    }

    /** 变量替换：已知变量填值，未知变量留空白填写栏 */
    private String fillVariables(String html, Map<String, String> vars) {
        Matcher m = VAR.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1).trim();
            String val = vars.get(key);
            if (val == null) val = BLANK;
            // 系统内置变量值可能含 HTML 片段（签字线），其余按纯文本转义
            String safe = val.trim().startsWith("<") ? val : escape(val);
            m.appendReplacement(sb, Matcher.quoteReplacement(safe));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** 统一 A4 版式：原型 archive_shared.js 的 doc-* 类名 + 样例范本的兼容类名 */
    public String pageCss() {
        return "@page{size:A4;margin:18mm 16mm;}"
                + "body{font-family:'WenQuanYi Zen Hei','WenQuanYi Micro Hei',serif;font-size:11pt;line-height:1.75;color:#111;}"
                + ".doc-h0{font-size:19pt;font-weight:bold;text-align:center;margin:6px 0 18px;letter-spacing:2px;}"
                + ".doc-h1{font-size:14pt;font-weight:bold;margin:16px 0 8px;}"
                + ".doc-h2{font-size:12pt;font-weight:bold;margin:12px 0 6px;}"
                + ".doc-h3{font-size:11pt;font-weight:bold;margin:9px 0 4px;}"
                + ".doc-p{text-indent:2em;margin:5px 0;}"
                + ".doc-li{margin:4px 0 4px 2em;}"
                + ".doc-ol{margin:4px 0 4px 1em;}"
                + ".doc-no{text-align:right;font-size:10pt;margin-bottom:12px;}"
                + ".ptitle{font-size:18pt;font-weight:bold;text-align:center;margin:6px 0 16px;}"
                + ".doc-tbl{width:100%;border-collapse:collapse;margin:10px 0;font-size:9.5pt;}"
                + ".doc-tbl th,.doc-tbl td{border:1px solid #333;padding:5px 6px;text-align:center;}"
                + ".doc-tbl th{background:#f2f4f7;font-weight:bold;}"
                + ".doc-sign{margin-top:26px;font-size:11pt;line-height:2.2;}"
                + ".fill{display:inline-block;min-width:90px;border-bottom:1px solid #333;}"
                + ".sign-underline{display:inline-block;min-width:120px;border-bottom:1px solid #333;}"
                + ".no-indent,.no-indent .doc-p{text-indent:0;}"
                + ".sign-block{margin-top:26px;}"
                + ".sign-line{margin:12px 0;}"
                + ".sign-item{display:inline-block;min-width:45%;text-align:center;}"
                + "ol{margin:4px 0 8px 2em;}li{margin:3px 0;}"
                + "p{text-indent:2em;margin:5px 0;}"
                + "h1{font-size:19pt;text-align:center;margin:6px 0 16px;}"
                + "h2{font-size:14pt;margin:14px 0 8px;}"
                + "table{width:100%;border-collapse:collapse;margin:10px 0;font-size:9.5pt;}"
                + "th,td{border:1px solid #333;padding:5px 6px;}";
    }

    private String wrapHtml(String body) {
        return "<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"UTF-8\"/>"
                + "<style>" + pageCss() + "</style><title>台账范本</title></head><body>"
                + body + "</body></html>";
    }
}
