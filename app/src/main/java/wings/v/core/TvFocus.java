package wings.v.core;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import androidx.annotation.NonNull;
import androidx.appcompat.view.WindowCallbackWrapper;
import androidx.appcompat.widget.Toolbar;
import androidx.core.widget.NestedScrollView;
import dev.oneuiproject.oneui.widget.BottomTabLayout;
import wings.v.R;

/**
 * Вёрстка под палец фокус не объявляет, и пультом по ней хуй походишь. Раздаём
 * его всему кликабельному разом, а не правим каждый экран руками
 */
@SuppressWarnings(
    {
        "PMD.CommentRequired",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.OnlyOneReturn",
        "PMD.LawOfDemeter",
        "PMD.CyclomaticComplexity",
    }
)
public final class TvFocus {

    // Разметка пересчитывается на каждый скролл и каждую анимацию, а обход
    // дерева не бесплатный: без паузы телик будет тормозить как говно
    private static final long RESCAN_EVERY_MS = 250L;

    private TvFocus() {}

    /** Пасёт фокус весь срок жизни экрана, иначе он разъебётся на первом списке */
    public static void attach(@NonNull Activity activity) {
        if (!TvMode.isTelevision(activity)) {
            return;
        }
        View root = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (root == null) {
            return;
        }
        // Resume прилетает дохуя раз, а слушатели нужны одни
        if (Boolean.TRUE.equals(root.getTag(R.id.tv_focus_attached))) {
            sweep(root);
            return;
        }
        root.setTag(R.id.tv_focus_attached, Boolean.TRUE);
        hookKeys(activity, root);
        Drawable ring = activity.getDrawable(R.drawable.tv_focus_ring);
        if (ring != null) {
            root.getOverlay().add(ring);
            root.setTag(R.id.tv_focus_ring, ring);
        }
        sweep(root);
        long[] lastScan = { 0L };
        root
            .getViewTreeObserver()
            .addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
                drawRing(root);
                if (newFocus == null || !isWorthFocusing(newFocus)) {
                    root.post(() -> ensureSomethingHasFocus(root));
                }
            });
        // Списки и диалоги подкладывают элементы позже, разовый обход их проебёт
        root
            .getViewTreeObserver()
            .addOnGlobalLayoutListener(() -> {
                long now = SystemClock.uptimeMillis();
                if (now - lastScan[0] < RESCAN_EVERY_MS) {
                    return;
                }
                lastScan[0] = now;
                sweep(root);
            });
        // Рамка рисуется по месту вьюхи, а при скролле место уезжает
        root.getViewTreeObserver().addOnScrollChangedListener(() -> drawRing(root));
    }

    private static void sweep(@NonNull View root) {
        apply(root);
        wireTabBar(root);
        // Внутри прохода разметки фокус не ставится: ждём, пока он кончится
        root.post(() -> {
            ensureSomethingHasFocus(root);
            drawRing(root);
        });
    }

    /** Раздаёт фокус всему, что кликается нахуй */
    public static void apply(@NonNull View view) {
        if (view instanceof ViewGroup group) {
            // Контейнер, хапнувший фокус себе, запирает крестовину нахуй:
            // внутрь она не лезет, а нажать на нём нечего
            // Соседние страницы пейджер запирает по делу - фокусу туда нехуй
            if (group.getDescendantFocusability() == ViewGroup.FOCUS_BLOCK_DESCENDANTS && isOnScreen(group)) {
                group.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            }
            if (isScroller(group)) {
                // Скроллер хапнет фокус только когда внутри нечего нажать, зато
                // тогда катает себя крестовиной сам
                group.setFocusable(true);
                group.setFocusableInTouchMode(true);
            } else if (group.isFocusable() && !group.isClickable()) {
                group.setFocusable(false);
            } else if (isEmptyShell(group)) {
                // Виджет перекидывает слушатель во внутренний контейнер, а сам
                // остаётся кликабельным впустую: жмёшь ОК, а там нихуя нет
                group.setFocusable(false);
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                apply(group.getChildAt(i));
            }
        }
        if (view instanceof EditText) {
            // Оно и так фокусируется, а полезешь - разъебёшь клавиатуру
            return;
        }
        if (view instanceof ViewGroup shell && isEmptyShell(shell)) {
            return;
        }
        if (view.isClickable() || view.isLongClickable()) {
            if (!view.isFocusable()) {
                view.setFocusable(true);
            }
            // Без этого флага система держит окно в тач-режиме и requestFocus
            // высирает false: фокуса нет, крестовина жмёт в пустоту нахуй
            if (!view.isFocusableInTouchMode()) {
                view.setFocusableInTouchMode(true);
            }
        }
    }

    /** Без фокуса пульт жмёт в пустоту нахуй: системе не с чего начать */
    private static void ensureSomethingHasFocus(@NonNull View root) {
        // Поверх висит дропдаун или диалог, и фокус сейчас у них. Залезешь
        // туда своим - попап его потеряет и закроется нахуй
        if (root.getWindowToken() == null || !root.hasWindowFocus()) {
            return;
        }
        View current = root.findFocus();
        if (current != null && isWorthFocusing(current)) {
            return;
        }
        View first = firstFocusable(root);
        if (first != null && first != current && !first.requestFocus()) {
            first.requestFocusFromTouch();
        }
    }

    /**
     * Своя подсветка: системную SESL-виджеты шлют нахуй, и без неё хуй поймёшь,
     * куда тыкаешь. Висит в оверлее окна, потому что в foreground она крутилась
     * бы вместе с кнопкой подключения
     */
    private static void drawRing(@NonNull View root) {
        if (!(root.getTag(R.id.tv_focus_ring) instanceof Drawable ring) || !(root instanceof ViewGroup group)) {
            return;
        }
        View focused = root.findFocus();
        boolean show =
            focused != null &&
            root.hasWindowFocus() &&
            isWorthFocusing(focused) &&
            !isScroller(focused) &&
            isOnScreen(focused);
        if (show) {
            Rect spot = new Rect(0, 0, focused.getWidth(), focused.getHeight());
            group.offsetDescendantRectToMyCoords(focused, spot);
            ring.setBounds(spot);
        } else {
            ring.setBounds(0, 0, 0, 0);
        }
        root.invalidate();
    }

    /** Нажать можно - значит фокус тут к месту, а не хуй пойми где */
    public static boolean isWorthFocusing(@NonNull View view) {
        if (view.getVisibility() != View.VISIBLE || !view.isEnabled()) {
            return false;
        }
        // Выбранный таб некликабелен, но фокус на нём законный: иначе его
        // сбрасывает на контент, и из навбара хуй выберешься
        if (!view.isFocusable()) {
            return false;
        }
        boolean actionable =
            view.isClickable() ||
            view instanceof EditText ||
            isScroller(view) ||
            (view.isFocusable() && isTabBarItem(view));
        if (!actionable) {
            return false;
        }
        return isReachable(view);
    }

    /** Кликабельна, своего слушателя нет, а внутри сидит тот, кто реально жмётся */
    private static boolean isEmptyShell(@NonNull ViewGroup group) {
        return group.isClickable() && !group.hasOnClickListeners() && hasClickableChild(group);
    }

    private static boolean hasClickableChild(@NonNull ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.isClickable() && child.getVisibility() == View.VISIBLE) {
                return true;
            }
            if (child instanceof ViewGroup nested && hasClickableChild(nested)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTabBarItem(@NonNull View view) {
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (parent instanceof BottomTabLayout) {
                return true;
            }
        }
        return false;
    }

    /**
     * Скроллер жрёт нажатие вниз раньше, чем система успеет поискать соседа
     * за его краем, и до навбара не спрыгнуть. Ловим кнопку до дерева
     */
    private static void hookKeys(@NonNull Activity activity, @NonNull View root) {
        Window window = activity.getWindow();
        Window.Callback callback = window.getCallback();
        if (callback != null) {
            window.setCallback(
                new WindowCallbackWrapper(callback) {
                    @Override
                    public boolean dispatchKeyEvent(KeyEvent event) {
                        boolean down =
                            event.getAction() == KeyEvent.ACTION_DOWN &&
                            event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN;
                        if (!down) {
                            return super.dispatchKeyEvent(event);
                        }
                        // Сначала отдаём нажатие экрану, а судим по факту: не
                        // уехал ни фокус, ни экран под ним - значит дно.
                        // Смотрим на место вьюхи, потому что список двигает
                        // детей, и getScrollY у него вечный ноль
                        View was = root.findFocus();
                        int spot = was == null ? 0 : contentMark(was);
                        boolean handled = super.dispatchKeyEvent(event);
                        // Судим из post: к этому моменту ViewRootImpl уже
                        // закончил двигать фокус, раньше смотреть бесполезно
                        root.post(() -> {
                            // Система могла увести фокус на вьюху за кадром:
                            // такой ход не в счёт, это тупик
                            View now = root.findFocus();
                            boolean landed = now != null && isWorthFocusing(now);
                            if (!landed || !hasMoved(root, was, spot)) {
                                jumpToTabBar(root);
                            }
                        });
                        return handled;
                    }
                }
            );
        }
    }

    private static boolean jumpToTabBar(@NonNull View root) {
        View bar = findTabBar(root);
        View focused = root.findFocus();
        if (bar == null || focused == null || !isOnScreen(bar) || isTabBarItem(focused)) {
            return false;
        }
        return bar.requestFocus();
    }

    /**
     * Список длинный, и вниз крестовина всегда находит следующую строку: до
     * навбара так хуй доберёшься. Отдаём ему и бока - в списках всё равно
     * некуда ходить вбок
     */
    private static void wireTabBar(@NonNull View root) {
        View bar = findTabBar(root);
        if (bar == null || bar.getId() == View.NO_ID) {
            return;
        }
        linkSides(root, bar.getId());
    }

    /** Скроллеры про свои возможности пиздят, так что верим только глазам */
    private static boolean hasMoved(@NonNull View root, View was, int spot) {
        if (root.findFocus() != was) {
            return true;
        }
        return was != null && contentMark(was) != spot;
    }

    /**
     * Экранные координаты не годятся: кнопка подключения крутится, и они
     * пляшут каждый кадр. Берём место в разметке и прокрутку предков - на
     * повороты и прочую красоту им насрать
     */
    private static int contentMark(@NonNull View view) {
        int mark = view.getTop();
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) {
            View step = (View) parent;
            mark += step.getTop() - step.getScrollY();
        }
        return mark;
    }

    private static View findTabBar(@NonNull View view) {
        // По id надёжнее: в release-сборке класс перелопачен, да и футер
        // тулбар таскает по дереву как хочет
        View byId = view.findViewById(R.id.bottom_tab);
        if (byId != null) {
            return byId;
        }
        if (view instanceof BottomTabLayout) {
            return view;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findTabBar(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void linkSides(@NonNull View view, int barId) {
        if (view instanceof BottomTabLayout) {
            return;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                linkSides(group.getChildAt(i), barId);
            }
        }
        if (!view.isClickable()) {
            return;
        }
        // Заданное в разметке не трогаем: там сосед выбран осознанно
        if (view.getNextFocusLeftId() == View.NO_ID) {
            view.setNextFocusLeftId(barId);
        }
        if (view.getNextFocusRightId() == View.NO_ID) {
            view.setNextFocusRightId(barId);
        }
    }

    /**
     * Уехавшее в списке вернётся первым же нажатием, и перебивать такой фокус
     * нельзя: выделение тащило наверх, и ниже первой секции хуй пролистаешь.
     * А страница пейджера уезжает навсегда, там фокус залипает намертво
     */
    private static boolean isReachable(@NonNull View view) {
        if (isOnScreen(view)) {
            return true;
        }
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) {
            View step = (View) parent;
            if (!isOnScreen(step)) {
                continue;
            }
            // Строго по вертикали: пейджер ездит вбок, и его уехавшая страница
            // не должна сойти за временно скрытую
            if (isScroller(step) || step.canScrollVertically(1) || step.canScrollVertically(-1)) {
                return true;
            }
        }
        return false;
    }

    /** Эти три катают себя крестовиной сами, им бы только фокус дать */
    private static boolean isScroller(@NonNull View view) {
        return view instanceof ScrollView || view instanceof HorizontalScrollView || view instanceof NestedScrollView;
    }

    /** Родитель обрезает уехавшее, так что пустой прямоугольник - это за кадром */
    private static boolean isOnScreen(@NonNull View view) {
        return view.getGlobalVisibleRect(new Rect());
    }

    /** Страница съебала вместе с фокусом, и без пинка пульт сдохнет нахуй */
    public static void refocus(@NonNull View anyView) {
        if (!TvMode.isTelevision(anyView.getContext())) {
            return;
        }
        View root = anyView.getRootView();
        if (root != null) {
            sweep(root);
        }
    }

    /**
     * Первый по дереву - это ссылка "Пропустить" в углу, а от неё крестовина
     * упирается в скроллер и до кнопок не доходит нахуй. Главное действие
     * всегда кнопка, её и берём
     */
    private static View firstFocusable(@NonNull View root) {
        View found = search(root, true, true);
        if (found == null) {
            found = search(root, false, true);
        }
        return found == null ? search(root, false, false) : found;
    }

    private static View search(@NonNull View view, boolean buttonsOnly, boolean skipToolbar) {
        if (view.getVisibility() != View.VISIBLE) {
            return null;
        }
        // Единственные настоящие кнопки экрана сидят в тулбаре, и стартовый
        // фокус улетал туда вместо списка: жмёшь ОК, а толку ноль
        if (skipToolbar && view instanceof Toolbar) {
            return null;
        }
        if (isWorthFocusing(view) && (!buttonsOnly || view instanceof Button)) {
            return view;
        }
        if (view instanceof ViewGroup group) {
            // В уехавшую страницу лезть нехуй, там всё за кадром
            if (!isOnScreen(group)) {
                return null;
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = search(group.getChildAt(i), buttonsOnly, skipToolbar);
                if (child != null) {
                    return child;
                }
            }
        }
        return null;
    }
}
