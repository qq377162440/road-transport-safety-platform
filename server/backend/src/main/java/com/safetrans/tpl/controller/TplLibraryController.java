package com.safetrans.tpl.controller;

import com.safetrans.common.auth.RequireRole;
import com.safetrans.common.web.Result;
import com.safetrans.tpl.service.TplLibraryService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 台账范本库接口 —— 「台账范本管理」页面与「电子档案柜」共用的同源数据入口。
 * 档案柜生成 PDF 时通过 /match 找到归档项对应范本，再交给 ArchiveService 按该范本渲染。
 */
@RestController
@RequestMapping("/api/tpl/library")
@RequireRole({"enterprise", "provider", "admin"})
public class TplLibraryController {

    private final TplLibraryService library;

    public TplLibraryController(TplLibraryService library) {
        this.library = library;
    }

    /** 当前企业生效的范本清单（轻量，不含正文） */
    @GetMapping
    public Result<List<TplLibraryService.TplVO>> list() {
        return Result.ok(library.list());
    }

    /** 清单 + 正文（前端一次拉取，用于编辑/预览） */
    @GetMapping("/full")
    public Result<List<TplLibraryService.TplVO>> full() {
        return Result.ok(library.listWithContent());
    }

    /** 统一 A4 版式 CSS（前端预览容器注入，保证屏幕与 PDF 一致） */
    @GetMapping("/css")
    public Result<String> css() {
        return Result.ok(library.pageCss());
    }

    /** 当前应用范本套 ↔ 电子档案柜归档项映射（含无范本、需手动上传的归档项） */
    @GetMapping("/mapping")
    public Result<Map<String, Object>> mapping() {
        return Result.ok(library.mapping());
    }

    /** 按档案柜归档项名称匹配可用范本（电子档案柜联动用） */
    @GetMapping("/match")
    public Result<List<TplLibraryService.TplVO>> match(@RequestParam("archiveItem") String archiveItem,
                                                       @RequestParam(value = "boxNo", required = false) Integer boxNo) {
        List<TplLibraryService.TplVO> out = new ArrayList<>();
        for (var t : library.forItem(archiveItem, boxNo)) {
            TplLibraryService.TplVO v = new TplLibraryService.TplVO();
            v.setId(t.getId());
            v.setCode(t.getCode());
            v.setName(t.getName());
            v.setCategory(t.getCategory());
            v.setBoxNo(t.getBoxNo());
            v.setArchiveItem(t.getArchiveItem());
            v.setVersionLabel(t.getVersionLabel());
            out.add(v);
        }
        return Result.ok(out);
    }

    /** 单个范本详情（含正文与内置基线正文） */
    @GetMapping("/doc/{code}")
    public Result<TplLibraryService.TplVO> detail(@PathVariable String code) {
        var tpl = library.effective(code);
        if (tpl == null) return Result.fail("范本不存在：" + code);
        TplLibraryService.TplVO v = new TplLibraryService.TplVO();
        v.setId(tpl.getId());
        v.setCode(tpl.getCode());
        v.setName(tpl.getName());
        v.setCategory(tpl.getCategory());
        v.setBoxNo(tpl.getBoxNo());
        v.setArchiveItem(tpl.getArchiveItem());
        v.setVersion(tpl.getVersion());
        v.setVersionLabel(tpl.getVersionLabel());
        v.setContent(tpl.getContent());
        for (var item : library.list()) {
            if (item.getCode().equals(code)) {
                v.setCustomized(item.isCustomized());
                v.setBaseContent(item.getBaseContent());
                break;
            }
        }
        return Result.ok(v);
    }

    /**
     * A4 正文 HTML。
     * mode=blank（默认，台账范本管理用）：空白范本，变量占位、无章、无真实人名；
     * mode=final（电子档案柜用）：成品，填企业信息与岗位人名、自动盖公司章。
     */
    @GetMapping("/render/{code}")
    public Result<String> render(@PathVariable String code,
                                 @RequestParam(value = "itemName", required = false) String itemName,
                                 @RequestParam(value = "year", required = false) Integer year,
                                 @RequestParam(value = "post", required = false) String post,
                                 @RequestParam(value = "mode", defaultValue = "blank") String mode) {
        int y = year == null ? java.time.Year.now().getValue() : year;
        return Result.ok("final".equalsIgnoreCase(mode)
                ? library.renderBody(code, itemName, y, post)
                : library.renderBlankBody(code, post));
    }

    /** PDF：同样区分空白范本与盖章成品 */
    @GetMapping("/pdf/{code}")
    public ResponseEntity<byte[]> pdf(@PathVariable String code,
                                      @RequestParam(value = "itemName", required = false) String itemName,
                                      @RequestParam(value = "year", required = false) Integer year,
                                      @RequestParam(value = "post", required = false) String post,
                                      @RequestParam(value = "mode", defaultValue = "blank") String mode) {
        int y = year == null ? java.time.Year.now().getValue() : year;
        boolean blank = !"final".equalsIgnoreCase(mode);
        byte[] bytes = blank ? library.renderBlankPdf(code, post) : library.renderPdf(code, itemName, y, post);
        String name = URLEncoder.encode(code + (blank ? "_空白范本" : "_台账成品"),
                StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + name + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(bytes);
    }

    /** 保存本范本（写本企业当前应用范本，档案柜生成随即同步） */
    @PutMapping("/doc/{code}")
    public Result<TplLibraryService.TplVO> save(@PathVariable String code, @RequestBody Map<String, Object> body) {
        String content = body.get("content") == null ? "" : String.valueOf(body.get("content"));
        String label = body.get("versionLabel") == null ? "" : String.valueOf(body.get("versionLabel"));
        return Result.ok(library.save(code, content, label));
    }

    /** 恢复内置范本（删除本企业定制） */
    @DeleteMapping("/doc/{code}")
    public Result<Void> reset(@PathVariable String code) {
        library.reset(code);
        return Result.ok();
    }

    /** 联动编辑：一次修改同时更新《安全生产责任制》与该岗位《安全生产责任书》 */
    @PostMapping("/responsibility")
    public Result<Void> responsibility(@RequestBody Map<String, Object> body) {
        String post = str(body.get("post"));
        library.saveResponsibility(post, lines(body.get("target")), lines(body.get("duty")), lines(body.get("assess")));
        return Result.ok();
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 支持数组或换行分隔的文本 */
    @SuppressWarnings("unchecked")
    private List<String> lines(Object o) {
        List<String> out = new ArrayList<>();
        if (o == null) return out;
        if (o instanceof List<?> list) {
            for (Object x : list) {
                String s = String.valueOf(x).trim();
                if (!s.isEmpty()) out.add(s);
            }
        } else {
            for (String s : String.valueOf(o).split("[\\r\\n]+")) {
                if (!s.trim().isEmpty()) out.add(s.trim());
            }
        }
        return out;
    }
}
