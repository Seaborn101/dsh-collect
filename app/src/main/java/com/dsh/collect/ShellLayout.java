package com.dsh.collect;

import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 应用外壳：内容区 + 左侧抽屉 + 遮罩，并接管手势。
 *
 * <p>手势约定：
 * <ul>
 *   <li>从屏幕左边缘右滑 → 打开抽屉</li>
 *   <li>抽屉打开时右滑/点遮罩 → 关闭</li>
 *   <li>内容区上滑 → 切到「今日精选」页</li>
 * </ul>
 */
public class ShellLayout extends FrameLayout {

    /** 抽屉相关回调 */
    public interface Host {
        /** 上滑：切到精选页 */
        void onSwipeUp();

        /** 抽屉开合状态变化 */
        void onDrawerStateChanged(boolean open);
    }

    private View drawer;
    private View scrim;
    private Host host;

    private float drawerWidth = 0f;
    private boolean open = false;
    private boolean dragging = false;
    private float downX = 0f;
    private float downY = 0f;

    private GestureDetector detector;

    public ShellLayout(Context context) {
        super(context);
        init();
    }

    public ShellLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        detector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                downX = e.getX();
                downY = e.getY();
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (drawer == null) {
                    return false;
                }
                float from = e1 == null ? downX : e1.getX();
                if (!open && from > edgeWidth() + 8) {
                    return false; // 只有从左边缘起才认为是开抽屉
                }
                if (Math.abs(dx) < Math.abs(dy)) {
                    return false;
                }
                dragging = true;
                float base = open ? 0f : -drawerWidth;
                float nx = base + dx;
                if (nx > 0) {
                    nx = 0;
                }
                if (nx < -drawerWidth) {
                    nx = -drawerWidth;
                }
                drawer.setTranslationX(nx);
                if (scrim != null) {
                    scrim.setAlpha((1f - (-nx / drawerWidth)) * 0.5f);
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (dragging) {
                    dragging = false;
                    float tx = drawer == null ? -drawerWidth : drawer.getTranslationX();
                    if (vx > 600) {
                        openDrawer();
                    } else if (vx < -600) {
                        closeDrawer();
                    } else if (tx > -drawerWidth / 2f) {
                        openDrawer();
                    } else {
                        closeDrawer();
                    }
                    return true;
                }
                if (open) {
                    if (vx < -400) {
                        closeDrawer();
                        return true;
                    }
                    return false;
                }
                // 内容区上滑 → 精选页
                if (vy < -700 && Math.abs(vx) < 500) {
                    if (host != null) {
                        host.onSwipeUp();
                    }
                    return true;
                }
                return false;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                return false;
            }
        });
    }

    public void setHost(Host h) {
        host = h;
    }

    /** 绑定抽屉与遮罩 */
    public void bindDrawer(View drawerView, View scrimView) {
        this.drawer = drawerView;
        this.scrim = scrimView;
        drawerWidth = drawerView.getWidth() > 0 ? drawerView.getWidth()
                : dp(300);
        drawerView.setTranslationX(-drawerWidth);
        if (scrimView != null) {
            scrimView.setAlpha(0f);
            scrimView.setVisibility(View.GONE);
            scrimView.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeDrawer();
                }
            });
        }
    }

    /** 布局完成后确定抽屉宽度 */
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (drawer != null && drawer.getWidth() > 0) {
            float w = drawer.getWidth();
            if (Math.abs(w - drawerWidth) > 1f && !open) {
                drawerWidth = w;
                drawer.setTranslationX(-w);
            }
        }
    }

    public boolean isDrawerOpen() {
        return open;
    }

    public void openDrawer() {
        if (drawer == null) {
            return;
        }
        open = true;
        animate(-0f);
        if (host != null) {
            host.onDrawerStateChanged(true);
        }
    }

    public void closeDrawer() {
        if (drawer == null) {
            return;
        }
        open = false;
        animate(-drawerWidth);
        if (host != null) {
            host.onDrawerStateChanged(false);
        }
    }

    private void animate(final float target) {
        if (drawer == null) {
            return;
        }
        drawer.animate().translationX(target).setDuration(180).start();
        if (scrim != null) {
            if (target == 0f) {
                scrim.setVisibility(View.VISIBLE);
            }
            scrim.animate().alpha(target == 0f ? 0.5f : 0f).setDuration(180)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            if (target != 0f && scrim != null) {
                                scrim.setVisibility(View.GONE);
                            }
                        }
                    }).start();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (detector != null) {
            detector.onTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    private float edgeWidth() {
        return dp(48);
    }

    private float dp(int v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
