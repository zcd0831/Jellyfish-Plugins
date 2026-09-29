package zcd.jellyfish.plugin.todo;

import java.util.List;

/**
 * 待办的文本渲染：把同一份列表渲染成三种给人 / 给模型看的形态。
 * <p>
 * 集中在一处是为了让「模型看到的」与「人看到的」永远一致：两处各写一遍迟早会漂移
 * （例如一边显示 {@code [x]}、一边显示 {@code done}），而它们描述的本就是同一件事。
 * 三态各自的标记定义在 {@link TodoStatus} 里，本类只负责把它们拼成行。
 *
 * @author zcd
 */
final class TodoText {

    /** 提示词里的待办块标题。 */
    static final String HEADER = "[待办]";

    /**
     * 工具类，禁止实例化。
     */
    private TodoText() {
    }

    /**
     * 渲染注入 system prompt 的待办块。
     * <p>
     * 与内核原先的形态一致（{@code [待办]} 标题 + 每行 {@code - [ ] 内容}）：模型看到的东西不该因为
     * 「待办从内核搬到插件」而变化。唯一的例外是出现了「进行中」项——那时标题后面会补一句图例，
     * 因为 {@code [~]} 不像 {@code [ ]} / {@code [x]} 那样自明，而这一项恰恰是模型此刻最该认准的；
     * 没有进行中项时保持原样，不为用不上的图例每轮多花 token。
     *
     * @param items 待办列表，不可为 {@code null}
     * @return 待办块文本；没有待办时返回 {@code null}
     */
    static String promptBlock(List<TodoItem> items) {
        if (items.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder(hasInProgress(items) ? legendHeader() : HEADER);
        for (TodoItem item : items) {
            text.append('\n').append("- ").append(item.status().mark()).append(item.content());
        }
        return text.toString();
    }

    /**
     * 渲染给用户看的带编号清单。
     *
     * @param items 待办列表，不可为 {@code null}
     * @return 清单文本；没有待办时是一句明确说明而不是空串
     */
    static String list(List<TodoItem> items) {
        if (items.isEmpty()) {
            return "当前没有待办。";
        }
        return "待办：" + numbered(items);
    }

    /**
     * 渲染工具执行后的确认文本：附带覆盖后的整份清单，让模型不必再读一次。
     *
     * @param items 覆盖后的待办列表，不可为 {@code null}
     * @return 确认文本
     */
    static String confirmation(List<TodoItem> items) {
        if (items.isEmpty()) {
            return "待办已清空。";
        }
        return "待办已更新：" + numbered(items);
    }

    /**
     * 渲染状态栏上的进度片段。
     * <p>
     * 基础形态仍是 {@code 待办 2/5}（完成数 / 总数）。有进行中项时补一段 {@code · 进行中 1}：
     * 「现在在做哪件事」与「做完了几件」同等重要，而状态栏只有一行——没有进行中项时不多占别人的列。
     *
     * @param items 待办列表，不可为 {@code null}
     * @return 形如 {@code 待办 2/5} 的片段；没有待办时返回 {@code null}（状态栏不显示）
     */
    static String statusLine(List<TodoItem> items) {
        if (items.isEmpty()) {
            return null;
        }
        int completed = 0;
        int inProgress = 0;
        for (TodoItem item : items) {
            if (item.status() == TodoStatus.COMPLETED) {
                completed++;
            } else if (item.status() == TodoStatus.IN_PROGRESS) {
                inProgress++;
            }
        }
        String progress = "待办 " + completed + "/" + items.size();
        return inProgress == 0 ? progress : progress + " · 进行中 " + inProgress;
    }

    /**
     * 渲染带编号的清单主体（不含前缀）。
     *
     * @param items 非空待办列表
     * @return 每行 {@code "  [ ] 1. 内容"} 的多行文本
     */
    private static String numbered(List<TodoItem> items) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            TodoItem item = items.get(i);
            text.append('\n').append("  ").append(item.status().mark())
                    .append(i + 1).append(". ").append(item.content());
        }
        return text.toString();
    }

    /**
     * 判断列表里是否有进行中的项。
     *
     * @param items 待办列表，不可为 {@code null}
     * @return 存在返回 {@code true}
     */
    private static boolean hasInProgress(List<TodoItem> items) {
        for (TodoItem item : items) {
            if (item.status() == TodoStatus.IN_PROGRESS) {
                return true;
            }
        }
        return false;
    }

    /**
     * 拼出带图例的待办块标题。
     *
     * @return 形如 {@code [待办]（[ ] 未开始，[~] 进行中，[x] 已完成）} 的标题
     */
    private static String legendHeader() {
        return HEADER + "（" + TodoStatus.PENDING.mark().trim() + " 未开始，"
                + TodoStatus.IN_PROGRESS.mark().trim() + " 进行中，"
                + TodoStatus.COMPLETED.mark().trim() + " 已完成）";
    }
}
