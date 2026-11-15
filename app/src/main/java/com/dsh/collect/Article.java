package com.dsh.collect;

/** 一条收藏记录。 */
public class Article {
    /** 文章永久链接（mp.weixin.qq.com/s/...） */
    public String url = "";
    /** 文章标题 */
    public String title = "";
    /** 公众号名称 */
    public String account = "";
    /** 收藏时间戳（毫秒） */
    public long time = 0L;
    /** 落盘文件名（相对收藏目录） */
    public String file = "";

    public Article() {
    }

    public Article(String url, String title, String account, long time) {
        this.url = url;
        this.title = title;
        this.account = account;
        this.time = time;
    }
}
