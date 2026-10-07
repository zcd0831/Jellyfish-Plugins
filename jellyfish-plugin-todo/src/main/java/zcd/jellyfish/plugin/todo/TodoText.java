package zcd.jellyfish.plugin.todo;

import java.util.List;

/**
 * 待办的文本渲染：把同一份列表渲染成三种给人 / 给模型看的形态。
 * <p>
 * 集中在一处是为了让「模型看到的」与「人看到的」永远一致：两处各写一遍迟早会漂移
 * （例如一边显示 {@code [x]}、一边显示 {@code done}），而它们描述的本就是同一件事。
 * 四态各自的标记定义在 {@link TodoStatus} 里，本类只负责把它们拼成行。
 * <p>
 * <b>卡住的原因必须出现在每一处</b>（注入块、编号清单、面板）：不写为什么，「卡住」与「没人做」
 * 在人看来是一样的，而这两件事该由谁去处理完全不同。
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
     * 渲染随本轮用户消息送达的待办块。
     * <p>
     * 与内核原先的形态一致（{@code [待办]} 标题 + 每行 {@code - [ ] 内容}）：模型看到的东西不该因为
     * 「待办从内核搬到插件」而变化。唯一的例外是出现了「进行中」或「卡住」项——那时标题后面会补一句图例，
     * 因为 {@code [~]} 与 {@code [!]} 不像 {@code [ ]} / {@code [x]} 那样自明，而这两项恰恰是
     * 模型此刻最该认准的；两者都没有时保持原样，不为用不上的图例每轮多花 token。
     * <p>
     * <b>卡住的那几条要把原因一起带上</b>：模型看到「[!] 核对缓存策略」只知道它没在做，
     * 看到原因才知道该换个做法、换个 agent，还是该问用户。
     * <p>
     * <b>块尾补一句批间引导</b>：清单回答「还剩什么」，而模型下一步该做什么并不自明
     * （例如「[~] 的已经在做，别再派一遍」）。引导按当前状态现算，只写用得上的那几句。
     * <p>
     * <b>全部已完成时返回 {@code null}，不再每轮重申</b>：本处理器每一轮都会被问到，而清单里全是
     * {@code [x]} 时这段话对「接下来做什么」已无信息量，却会跟着此后每一句用户输入一起发出去、
     * 一并落进历史。注意这<b>不是</b>「内容没变就不送」那类去重——只要还剩一件没做完，仍然每轮发送
     * <b>全量</b>清单：计划必须永远可见，而一旦改成按文本去重，上一次注入落进被压缩掉的那一段之后，
     * 模型就再也看不到自己的计划，且日志里什么都看不出来（见内核 {@code docs/constraints.md} 的「提示词布局与缓存」）。
     * 全部完成的情形没有这个问题：没有活要干，也就无所谓失忆。清单本身仍在 {@code /todo} 与面板上
     * 完整可见，只是不再往对话里塞。
     *
     * @param items 待办列表，不可为 {@code null}
     * @return 待办块文本；没有待办或全部已完成时返回 {@code null}
     */
    static String promptBlock(List<TodoItem> items) {
        if (!hasOpen(items)) {
            return null;
        }
        StringBuilder text = new StringBuilder(needsLegend(items) ? legendHeader() : HEADER);
        for (TodoItem item : items) {
            text.append('\n').append("- ").append(item.status().mark()).append(lineOf(item));
        }
        return text.append('\n').append(guidance(items)).toString();
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
     * 「现在在做哪件事」与「做完了几件」同等重要。有卡住项时再补一段 {@code · 卡住 1}：
     * 那几件**不会自己往前走**，而进度数字涨不上去的两种原因（还在做 / 做不了）必须分得开。
     * 状态栏只有一行，因此没有的那一段不占别人的列。
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
        int blocked = 0;
        for (TodoItem item : items) {
            if (item.status() == TodoStatus.COMPLETED) {
                completed++;
            } else if (item.status() == TodoStatus.IN_PROGRESS) {
                inProgress++;
            } else if (item.status() == TodoStatus.BLOCKED) {
                blocked++;
            }
        }
        StringBuilder progress = new StringBuilder("待办 ").append(completed).append('/').append(items.size());
        if (inProgress > 0) {
            progress.append(" · 进行中 ").append(inProgress);
        }
        return blocked == 0 ? progress.toString() : progress.append(" · 卡住 ").append(blocked).toString();
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
                    .append(i + 1).append(". ").append(lineOf(item));
        }
        return text.toString();
    }

    /**
     * 判断列表里是否还有未完成的事。
     * <p>
     * 按「非已完成即未完成」判断，而不是逐一枚举 {@code PENDING} / {@code IN_PROGRESS}：将来新增状态时，
     * 漏改这里的表现是「新状态的内容不送给模型」——模型看不到自己的计划，而这正是难查的那一侧。
     *
     * @param items 待办列表，不可为 {@code null}
     * @return 存在未完成项返回 {@code true}
     */
    private static boolean hasOpen(List<TodoItem> items) {
        for (TodoItem item : items) {
            if (item.status() != TodoStatus.COMPLETED) {
                return true;
            }
        }
        return false;
    }

    /**
     * 拼出带图例的待办块标题。
     *
     * @return 形如 {@code [待办]（[ ] 未开始，[~] 进行中，[x] 已完成，[!] 卡住）} 的标题
     */
    private static String legendHeader() {
        return HEADER + "（" + TodoStatus.PENDING.mark().trim() + " 未开始，"
                + TodoStatus.IN_PROGRESS.mark().trim() + " 进行中，"
                + TodoStatus.COMPLETED.mark().trim() + " 已完成，"
                + TodoStatus.BLOCKED.mark().trim() + " 卡住）";
    }

    /**
     * 渲染一条待办的内容部分（不含标记）。
     * <p>
     * 卡住的条目把原因接在后面：那是它此刻唯一的有效信息。
     *
     * @param item 待办项，不可为 {@code null}
     * @return 内容文本（卡住时形如 {@code 内容 —— 原因}），保证非空白
     */
    private static String lineOf(TodoItem item) {
        if (item.status() != TodoStatus.BLOCKED || item.reason() == null) {
            return item.content();
        }
        return item.content() + " —— " + item.reason();
    }

    /**
     * 判断是否需要补图例。
     * <p>
     * 需要的是那些「标记本身说不清」的状态：{@code [~]} 与 {@code [!]}。
     * {@code [ ]} / {@code [x]} 一看就懂，为它们每轮多花 token 不值。
     *
     * @param items 待办列表，不可为 {@code null}
     * @return 需要图例返回 {@code true}
     */
    private static boolean needsLegend(List<TodoItem> items) {
        for (TodoItem item : items) {
            if (item.status() == TodoStatus.IN_PROGRESS || item.status() == TodoStatus.BLOCKED) {
                return true;
            }
        }
        return false;
    }

    /**
     * 拼出块尾的批间引导。
     * <p>
     * <b>为什么要有这一段</b>：清单本身只回答「还剩什么」。而模型的下一步并不自明——它可能
     * 把已经在做的又派一遍，也可能对着一条卡住的活再派一批子代理去撞同一堵墙。
     * 这几句就是「批间决策」的落点（分册 D-P4-5）：**只写当前状态下用得上的那些**。
     * <p>
     * 三句各有触发条件，因此一段引导最长三句、短则一句：
     * <ul>
     *     <li>还有没人认领的 → 可以派子代理认领（点名工具，模型不必去猜）；</li>
     *     <li>有在做而没做完的 → 别重复派；</li>
     *     <li>有卡住的 → 需要人决定（这是唯一需要模型停下来问的情形）。</li>
     * </ul>
     *
     * @param items 待办列表，不可为 {@code null}
     * @return 引导文本，保证非空白
     */
    private static String guidance(List<TodoItem> items) {
        int pending = 0;
        int inProgress = 0;
        int blocked = 0;
        for (TodoItem item : items) {
            if (item.status() == TodoStatus.PENDING) {
                pending++;
            } else if (item.status() == TodoStatus.IN_PROGRESS) {
                inProgress++;
            } else if (item.status() == TodoStatus.BLOCKED) {
                blocked++;
            }
        }
        StringBuilder guidance = new StringBuilder("- 提示：");
        if (pending > 0) {
            guidance.append("还有 ").append(pending)
                    .append(" 条没人做，可以派子代理用 todo_claim 认领它们");
        }
        if (inProgress > 0) {
            guidance.append(pending > 0 ? "；" : "").append(inProgress).append(" 条已经在做，别再派一遍");
        }
        if (blocked > 0) {
            guidance.append(pending > 0 || inProgress > 0 ? "；" : "")
                    .append(blocked).append(" 条卡住了，需要你或用户决定怎么做");
        }
        return guidance.append('。').toString();
    }
}
