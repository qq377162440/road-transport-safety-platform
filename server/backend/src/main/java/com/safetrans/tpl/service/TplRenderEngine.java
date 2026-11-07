package com.safetrans.tpl.service;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 台账范本渲染引擎（范本管理 & 电子档案柜共用的唯一渲染口径，但分两层输出）。
 *
 * <p><b>模板层 · 空白范本</b>（台账范本管理）：{@link #renderBlankBody}——
 * 变量保持 {@code {{xxx}}} 可见占位、不填企业名与人名、不盖章，供版式编辑与预览。
 *
 * <p><b>成品层 · 正式台账</b>（电子档案柜）：{@link #renderBody}——
 * 填入企业信息与岗位人名、按印章设置自动盖公司章，供预览与 PDF 生成。
 *
 * <p>两层共用同一份 {@code tpl_template.content} 与 {@link #pageCss()}，
 * 因此在范本管理里改了版式，档案柜生成的成品立刻同步。
 *
 * <p>另负责解析条件段 {@code {% if post == '主要负责人' %}} 系列、
 * 把用户范本里的重复 class / 未闭合空元素规范化为合法 XHTML。
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

    /** 空白填写栏（用于未提供的变量，保证打印出来可手写补全；人名解析失败时也用它兜底） */
    public static final String BLANK = "<span class=\"fill\">&#160;&#160;&#160;&#160;&#160;&#160;&#160;&#160;</span>";

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

    /** 把范本内容解析为完整可渲染 HTML 文档（PDF 生成用） */
    public String renderDocument(String content, Map<String, String> vars) {
        return renderDocument(content, vars, null);
    }

    public String renderDocument(String content, Map<String, String> vars, String sealHtml) {
        return wrapHtml(renderBody(content, vars, sealHtml));
    }

    /**
     * 空白范本（台账范本管理预览/下载用）：
     * 变量保持 {{xxx}} 可见占位、不填真实企业名与人名、不盖公司章。
     * 条件段仍按当前岗位解析，让管理员看到该岗位下真实的版式骨架。
     */
    public String renderBlankBody(String content, Map<String, String> vars) {
        String body = content == null ? "" : content;
        body = resolveConditions(body, vars);
        body = markVariables(body);
        return normalizeHtml(body);
    }

    public String renderBlankDocument(String content, Map<String, String> vars) {
        return wrapHtml(renderBlankBody(content, vars));
    }

    /** 把 {{变量}} 转成浅灰可见占位标签，明确「这里是待填字段」 */
    private String markVariables(String html) {
        Matcher m = VAR.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = escape(m.group(1).trim());
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    "<span class=\"tpl-var\">{{" + key + "}}</span>"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 只输出正文片段（前端 A4 预览容器内嵌用） */
    public String renderBody(String content, Map<String, String> vars) {
        return renderBody(content, vars, null);
    }

    /**
     * 成品渲染（电子档案柜预览/PDF 用）：条件段 → 变量填真实值 → 公司章注入 → XHTML 规范化。
     * 预览与 PDF 共用本方法，因此屏幕所见即 PDF 所得。
     */
    public String renderBody(String content, Map<String, String> vars, String sealHtml) {
        String body = content == null ? "" : content;
        body = resolveConditions(body, vars);
        body = fillVariables(body, vars);
        body = normalizeHtml(body);
        if (sealHtml != null && !sealHtml.isBlank()) body = injectSeal(body, sealHtml);
        return body;
    }

    /**
     * 公司章注入（原型「所有台账都盖章，生成时自动盖在落款处」）。
     * 锚定正文中最后一条落款/盖章行，把章块紧接其后；章块自身用负 margin 上提压住落款文字，
     * 不依赖 CSS absolute / transform —— 浏览器与 openhtmltopdf 都能得到一致且完整的效果。
     */
    private String injectSeal(String body, String sealHtml) {
        if (body.contains("seal-box")) return body;   // 范本已自带章，避免重复
        // 落款总在文末：只从后半段开始找，避免正文里的「公司各部门：」被误当落款
        int from = body.length() > 400 ? (int) (body.length() * 0.5) : 0;
        Matcher m = SIGN_ROW.matcher(body);
        int hitEnd = -1;
        while (m.find()) {
            if (m.start() < from) continue;
            String hay = (m.group(2) == null ? "" : m.group(2)) + " " + m.group(3);
            if (SIGN_KEYWORD.matcher(hay).find()) hitEnd = m.end();
        }
        if (hitEnd >= 0) return body.substring(0, hitEnd) + sealHtml + body.substring(hitEnd);
        // 确实没有落款的范本 → 只补一个盖章位，不重复公司名与日期
        return body + "<div class=\"doc-sign\">（盖章）</div>" + sealHtml;
    }

    /** 单行类元素（div / p / li / span / td），非贪婪取到第一个同名闭合标签 */
    private static final Pattern SIGN_ROW = Pattern.compile(
            "<(div|p|li|span|td)(\\s[^>]*)?>(.*?)</\\1>", Pattern.DOTALL);

    /** 落款行关键词（覆盖制度/任命书/通知/责任书等各类范本的落款写法） */
    private static final Pattern SIGN_KEYWORD = Pattern.compile(
            "盖章|公章|责任主体|承诺单位|填报单位|签订单位|单位名称|编制单位|签发人|"
                    + "实施日期|生效日期|成文日期|发布日期|公司名称|企业名称|公司：|公司:|"
                    + "sign-zone|class=\"company\"|sign_zone");

    /**
     * XHTML 规范化：用户范本里存在 {@code <div class="doc-p" class="no-indent">} 这类重复属性，
     * openhtmltopdf 用 SAX 严格解析会直接抛 "Attribute class was already specified"，导致 PDF 生成 500。
     * 这里统一把重复 class 合并成一个，浏览器与 PDF 引擎都能正确解析。
     */
    private String normalizeHtml(String html) {
        Matcher m = Pattern.compile("<[a-zA-Z][^>]*>").matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String tag = m.group();
            Matcher c = Pattern.compile("class\\s*=\\s*\"([^\"]*)\"").matcher(tag);
            List<int[]> spans = new ArrayList<>();
            List<String> vals = new ArrayList<>();
            while (c.find()) { spans.add(new int[]{c.start(), c.end()}); vals.add(c.group(1)); }
            if (spans.size() > 1) {
                String all = String.join(" ", vals).replaceAll("\\s+", " ").trim();
                StringBuilder b = new StringBuilder(tag);
                for (int i = spans.size() - 1; i >= 1; i--) b.delete(spans.get(i)[0], spans.get(i)[1]);
                b.replace(spans.get(0)[0], spans.get(0)[1], "class=\"" + all + "\"");
                tag = b.toString().replaceAll("\\s{2,}", " ");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(tag));
        }
        m.appendTail(sb);
        return sb.toString();
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
                + "/* 公司章：压住落款列文字末端、章身向右下探出（骑年压月），让出左侧关键字保持可读。"
                + "刻意不用 absolute/transform：openhtmltopdf 对 left:100% 解析不完整会把章推出页面裁切 */"
                + ".seal-box{text-align:left;margin-top:-70px;margin-bottom:-34px;padding-left:165px;}"
                + ".seal-img{display:inline-block;width:148px;height:148px;}"
                + "/* 样例范本的 sign-zone 落款右对齐：章随列走，跟着落款贴右，不飘到左侧正文 */"
                + ".sign-zone .seal-box{text-align:right;padding-left:0;padding-right:26px;}"
                + ".fill{display:inline-block;min-width:90px;border-bottom:1px solid #333;}"
                + "/* 空白范本模式：变量占位标签（只在台账范本管理出现，成品不会带） */"
                + ".tpl-var{display:inline-block;padding:0 5px;border:1px dashed #94a3b8;background:#f1f5f9;"
                + "color:#64748b;font-size:9pt;white-space:nowrap;}"
                + ".sign-underline{display:inline-block;min-width:120px;border-bottom:1px solid #333;}"
                + ".no-indent,.no-indent .doc-p,.meta{text-indent:0;}"
                + ".sign-block{margin-top:26px;}"
                + ".sign-line{margin:12px 0;}"
                + ".sign-item{display:inline-block;min-width:45%;text-align:center;}"
                + "/* 用户提供的样例范本类名（成立领导小组通知 / 复工复产六个一）兼容 */"
                + ".doc-head{text-align:center;font-size:13pt;font-weight:bold;letter-spacing:2px;margin-bottom:8px;}"
                + ".rule{border-bottom:2px solid #d0021b;margin:8px 0 18px;}"
                + ".title,.doc-title{font-size:18pt;font-weight:bold;text-align:center;margin:14px 0 18px;}"
                + ".center{text-align:center;}"
                + ".member-list{margin:12px 0 12px 2em;line-height:2.1;}"
                + ".sign-zone{margin-top:26px;text-align:right;line-height:2.2;}"
                + ".sign-zone .sign-line{display:inline-block;min-width:120px;border-bottom:1px solid #333;margin:0;}"
                + ".fill-var,.fill-empty{display:inline-block;min-width:90px;border-bottom:1px solid #333;}"
                + ".meta{font-size:10pt;color:#444;}"
                + ".photo-holder{border:1px dashed #999;padding:28px;text-align:center;color:#777;margin:10px 0;}"
                + ".page-break{page-break-before:always;break-before:page;margin-top:26px;border-top:1px dashed #ccc;}"
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
