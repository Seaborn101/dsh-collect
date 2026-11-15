package com.dsh.collect;

import java.util.ArrayList;
import java.util.List;

/** 一条收藏记录。 */
public class Article {

    public static final String STATUS_NEW = "new";
    public static final String STATUS_READ = "read";
    public static final String STATUS_ARCHIVED = "archived";

    public static final String FETCH_PENDING = "pending";
    public static final String FETCH_OK = "ok";
    public static final String FETCH_FAILED = "failed";

    public long id = 0L;
    /** 文章永久链接 */
    public String url = "";
    public String title = "";
    public String account = "";
    public String source = "wechat";
    public long collectedAt = 0L;
    public long updatedAt = 0L;
    /** 抓下来的原始 HTML（可离线阅读） */
    public String contentHtml = "";
    /** 清洗后的正文纯文本 */
    public String contentText = "";
    public String summary = "";
    /** 空格分隔的标签 */
    public String tags = "";
    public String status = STATUS_NEW;
    public boolean starred = false;
    public long readAt = 0L;
    /** pending / ok / failed */
    public String fetchState = FETCH_PENDING;
    public String notes = "";
    /** 导出到共享目录时用的文件名（稳定，标题变化时重命名而不是新建） */
    public String exportName = "";
    /** 加工层级：L0 原始素材 / L1 已初加工 / L2 已综述 */
    public String layer = "L0";
    /** 作者（原文作者/机构） */
    public String author = "";
    /** 原文发布时间（毫秒） */
    public long publishedAt = 0L;
    /** 人工信号：high / neutral / low */
    public String signal = "";
    /** 分类目录名（AI 判定） */
    public String category = "";
    /** AI 要点（换行分隔） */
    public String keyPoints = "";
    /** AI 抽出的可复用结论（换行分隔） */
    public String takeaways = "";
    /** AI 选出的原文金句 */
    public String quote = "";
    /** AI 点评 */
    public String note = "";

    public boolean isUnread() {
        return STATUS_NEW.equals(status);
    }

    public List<String> tagList() {
        List<String> out = new ArrayList<>();
        if (tags == null) {
            return out;
        }
        for (String t : tags.trim().split("\\s+")) {
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    public void setTags(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String t : list) {
            String s = t.trim().replace("#", "");
            if (!s.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(s);
            }
        }
        tags = sb.toString();
    }

    public int contentLength() {
        return contentText == null ? 0 : contentText.length();
    }

    /** 是否已被 AI 加工过（L1 及以上） */
    public boolean isProcessed() {
        return layer != null && !layer.isEmpty() && !"L0".equals(layer);
    }
}
