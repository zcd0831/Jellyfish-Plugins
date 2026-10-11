package zcd.jellyfish.plugin.workflow;

import zcd.jellyfish.api.ui.UiEmphasis;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.api.ui.UiSegment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 编排面板的排版：把一份 spec 的步骤画成「链 + 汇合线」的行。
 * <p>
 * <b>它要回答的问题</b>：平铺清单看不出「哪些步骤会一起跑、谁在等谁、现在到哪一步」——而这正是并行
 * 编排最需要一眼看清的事。这里按<b>静态层号</b>（见 {@link WorkflowLayers}）把步骤排成图形：
 * 同一层 = 可以并行，箭头 = 谁把结论交给谁。
 * <p>
 * <b>三条画法</b>：
 * <ol>
 *     <li><b>链压一行</b>：一个步骤若只有一个上游、而那个上游正好是它所在行的行尾，就接在同一行后面：
 *     {@code L1 [x] 调研 A（scout） ───> L2 [~] 写方案（planner）}；</li>
 *     <li><b>汇合</b>：一个步骤的多个上游落在不同行时，下游画在<b>第一个上游</b>那一行的右端，
 *     其余上游各自在自己行尾用 {@code ───┤}/{@code ───┘} 拐上去，竖线对齐在同一列；</li>
 *     <li><b>分叉</b>：一个步骤有多个下游时，第一个接在行内，其余的从它右边往下引，
 *     下行以 {@code └──>} 开头。</li>
 * </ol>
 * <pre>
 *  L1 [x] 调研 A（scout） ───┬──> L2 [ ] 写方案（planner）
 *  L1 [~] 调研 B（scout） ───┘
 * </pre>
 * <p>
 * <b>连线的列位置由本类算</b>：这是对「插件不做界面计算」的一次偏离（另一处在 {@link WorkflowText}）。
 * 理由是列宽只有外壳知道，而「下行画在第几列」决定这张图能不能读懂。对齐靠显示宽度（中日韩算两列），
 * 框线字符按 1 列算——与外壳的 {@code DisplayWidth} 同一口径，这是能对齐的前提。
 * <p>
 * <b>算不出就退化成清单，而不是画歪</b>：一行上出现两个连线字形、竖线要穿过一行比它更宽的内容、
 * 行宽超过 {@link #ROW_WIDTH_BUDGET}、或汇合点落在行首（它的左臂没地方去）时，整条编排改用平铺清单
 * （每步一行，行尾标 {@code <- 上游}）。宁可少画一张图，也不要给一张会让人读错的图。
 * <p>
 * <b>行数不够时先撤「已完成的层」</b>：外壳给 {@code TOP} 区域的内容行有上限（8 行），而终局已定的层
 * （全部成功或跳过）对「接下来会发生什么」没有贡献。撤掉它们时，下游那一行的行首补 {@code … ───>}，
 * 说明它是从上面接下来的——不能让人以为那是编排的起点。失败<b>不在其列</b>：失败必须一直看得见。
 * <p>
 * 无状态、纯函数：给定快照与行数预算，输出的行完全确定，因此可以逐字断言。
 *
 * @author zcd
 */
final class WorkflowLayout {

    /** 一行的显示宽度预算（显示列）。 */
    static final int ROW_WIDTH_BUDGET = 84;

    /** 单个节点文本的显示宽度预算（显示列）。 */
    static final int NODE_WIDTH_BUDGET = 52;

    /** 名字的显示宽度上限（显示列）。 */
    static final int NAME_WIDTH_BUDGET = 20;

    /** 子代理类型的显示宽度上限（显示列）。 */
    static final int AGENT_WIDTH_BUDGET = 12;

    /** 行首「接自上方」标记：层被收起之后，下游行靠它说明自己不是起点。 */
    static final String ENTRY = WorkflowText.ELLIPSIS + " \u2500\u2500\u2500> ";

    /** 普通箭头（两个节点之间没有汇合点）。 */
    private static final String ARROW = " \u2500\u2500\u2500> ";

    /** 汇合点之后通向节点的那半段。 */
    private static final String TAIL = "\u2500\u2500> ";

    /** 横线。 */
    private static final char DASH = '\u2500';

    /** 汇合点（上行）：左、右、下三条臂。 */
    private static final char BRANCH = '\u252c';

    /** 汇合点在行尾（上行）：左、下两条臂。 */
    private static final char BRANCH_END = '\u2510';

    /** 中间的上游行：左、上、下三条臂。 */
    private static final char TEE = '\u2524';

    /** 最下面的上游行：左、上两条臂。 */
    private static final char JOIN_ELBOW = '\u2518';

    /** 分叉的下行：上、右两条臂。 */
    private static final char FAN_ELBOW = '\u2514';

    /** 竖线穿过某一行时的字形。 */
    private static final char VERTICAL = '\u2502';

    /** 汇合点之前至少留的列数（一个空格 + 三条横线）。 */
    private static final int JUNCTION_LEAD = 4;

    /**
     * 工具类，禁止实例化。
     */
    private WorkflowLayout() {
    }

    /**
     * 排一次版。
     *
     * @param progress  一次编排的只读快照，不可为 {@code null}
     * @param nowMillis 当前时刻（毫秒），用于算在跑的那一步已跑多久
     * @param maxRows   正文可用行数（不含标题），小于 1 时按 1 处理
     * @return 排版结果，保证非 {@code null}
     */
    static Result of(WorkflowProgress progress, long nowMillis, int maxRows) {
        int limit = Math.max(1, maxRows);
        List<WorkflowProgress.Step> steps = progress.getSteps();
        Set<String> dropped = new LinkedHashSet<String>();
        while (true) {
            List<Body> bodies = render(steps, dropped, nowMillis);
            if (bodies.size() <= limit) {
                return new Result(linesOf(bodies), 0);
            }
            Set<String> layer = droppableLayer(steps, dropped);
            if (layer.isEmpty()) {
                return truncated(bodies, limit);
            }
            dropped.addAll(layer);
        }
    }

    /**
     * 渲染一次（不裁行）。
     *
     * @param steps     全部步骤（声明顺序）
     * @param dropped   被撤掉的步骤标识
     * @param nowMillis 当前时刻
     * @return 正文行（声明顺序无法保证，但顺序稳定），保证非 {@code null}
     */
    private static List<Body> render(List<WorkflowProgress.Step> steps, Set<String> dropped, long nowMillis) {
        List<WorkflowProgress.Step> kept = new ArrayList<WorkflowProgress.Step>();
        for (WorkflowProgress.Step step : steps) {
            if (!dropped.contains(step.getId())) {
                kept.add(step);
            }
        }
        if (kept.isEmpty()) {
            return new ArrayList<Body>();
        }
        Map<String, Node> nodes = new LinkedHashMap<String, Node>();
        for (WorkflowProgress.Step step : kept) {
            nodes.put(step.getId(), nodeOf(step, nowMillis));
        }
        List<WorkflowProgress.Step> order = new ArrayList<WorkflowProgress.Step>(kept);
        Collections.sort(order, BY_LAYER_THEN_ORDER);
        Board board = plan(order, dropped, nodes);
        return board == null ? plain(kept, nodes) : board.render(nodes);
    }

    /**
     * 规划一张图：把步骤排成行，并在行间挂汇合点。
     *
     * @param order   保留的步骤（层升序、层内声明序）
     * @param dropped 被撤掉的步骤标识
     * @param nodes   节点（渲染文本与状态）
     * @return 规划结果；画不出来时返回 {@code null}（调用方退化成清单）
     */
    private static Board plan(List<WorkflowProgress.Step> order, Set<String> dropped, Map<String, Node> nodes) {
        List<Row> rows = new ArrayList<Row>();
        Map<String, Integer> rowOf = new HashMap<String, Integer>();
        List<Junction> junctions = new ArrayList<Junction>();
        for (WorkflowProgress.Step step : order) {
            List<String> upstreams = keptUpstreams(step, dropped);
            Node node = nodes.get(step.getId());
            Row source = upstreams.isEmpty() ? null : rows.get(rowOf.get(upstreams.get(0)).intValue());
            Row target;
            if (source != null && source.isTail(upstreams.get(0))) {
                if (source.fits(node.text)) {
                    source.add(node);
                    target = source;
                } else {
                    // 链本身太长，一行放不下：换到下一行，靠行首标记说明它是接上来的（而不是分叉）
                    target = new Row();
                    target.index = rows.size();
                    rows.add(target);
                    target.add(node);
                    target.entry = true;
                }
            } else {
                target = new Row();
                target.index = rows.size();
                rows.add(target);
                target.add(node);
                if (source != null) {
                    Junction junction = Junction.fan(source.indexOf(upstreams.get(0)) + 1,
                            source.isTail(upstreams.get(0)));
                    if (!attach(source, junction, target)) {
                        return null;
                    }
                    junctions.add(junction);
                } else if (!step.getNeeds().isEmpty()) {
                    // 上游全被撤掉了：留下「接自上方」的痕迹，别让人以为它是编排的起点
                    target.entry = true;
                }
            }
            rowOf.put(step.getId(), Integer.valueOf(rows.indexOf(target)));
            for (int i = 1; i < upstreams.size(); i++) {
                Row upRow = rows.get(rowOf.get(upstreams.get(i)).intValue());
                Junction junction = Junction.join(target.indexOf(step.getId()));
                if (!attach(upRow, junction, target)) {
                    return null;
                }
                junctions.add(junction);
            }
        }
        return Board.of(rows, junctions, nodes);
    }

    /**
     * 把一个汇合点挂到它所在的行上。
     * <p>
     * <b>一行只允许一个连线字形</b>：两个字形会让竖线、横线与节点抢同一段列，画出来几乎一定读错，
     * 而算得准的收益远不抵复杂度——这时整条编排退化成清单（见类注释）。
     *
     * @param row      汇合点所在的这一端
     * @param junction 汇合点
     * @param target   另一端（分叉时的落点行；汇合时与 {@code row} 相同的那个）
     * @return 挂上了返回 {@code true}；这一行已经有字形时返回 {@code false}
     */
    private static boolean attach(Row row, Junction junction, Row target) {
        if (row.glyph != Glyph.NONE || target.glyph != Glyph.NONE) {
            return false;
        }
        if (junction.fan) {
            junction.ownRow = row.index;
            target.glyph = Glyph.RIGHT;
            junction.rightRows.add(Integer.valueOf(target.index));
        } else {
            junction.ownRow = target.index;
            row.glyph = Glyph.LEFT;
            junction.leftRows.add(Integer.valueOf(row.index));
        }
        row.junction = junction;
        target.junction = junction;
        return true;
    }

    /**
     * 取一个步骤「保留下来的」上游标识。
     * <p>
     * <b>只排出一个稳定的次序，按步骤标识升序</b>：第一个上游那一行承担下游的画位，其余上游各自在行尾
     * 拐上去。选谁当宿主行影响的是图形长相、不影响它表达的信息，但必须固定下来——否则同一份 spec
     * 每次可能画出不同的图，而「图变了」会被读成「编排变了」。
     *
     * @param step    步骤
     * @param dropped 被撤掉的步骤标识
     * @return 上游标识列表，保证非 {@code null}
     */
    private static List<String> keptUpstreams(WorkflowProgress.Step step, Set<String> dropped) {
        List<String> result = new ArrayList<String>();
        for (String need : step.getNeeds()) {
            if (!dropped.contains(need)) {
                result.add(need);
            }
        }
        Collections.sort(result, new Comparator<String>() {

            @Override
            public int compare(String left, String right) {
                return left.compareTo(right);
            }
        });
        return result;
    }

    /**
     * 挑一个可以撤掉的层：该层所有步骤都已成功或跳过，且还没有被撤。
     * <p>
     * <b>含失败或仍在跑的层不撤</b>：失败需要一直看得见，而正在跑的层正是用户此刻要看的东西。
     * <b>按层号从小到大撤</b>：越早的层越是已经翻篇的历史。
     *
     * @param steps   全部步骤（声明顺序）
     * @param dropped 已被撤掉的步骤标识
     * @return 该层全部步骤的标识；没有可撤的层时返回空集合
     */
    private static Set<String> droppableLayer(List<WorkflowProgress.Step> steps, Set<String> dropped) {
        Map<Integer, List<WorkflowProgress.Step>> byLayer =
                new LinkedHashMap<Integer, List<WorkflowProgress.Step>>();
        for (WorkflowProgress.Step step : steps) {
            if (dropped.contains(step.getId())) {
                continue;
            }
            Integer key = Integer.valueOf(step.getLayer());
            List<WorkflowProgress.Step> layer = byLayer.get(key);
            if (layer == null) {
                layer = new ArrayList<WorkflowProgress.Step>();
                byLayer.put(key, layer);
            }
            layer.add(step);
        }
        for (Map.Entry<Integer, List<WorkflowProgress.Step>> entry : byLayer.entrySet()) {
            if (isSettledLayer(entry.getValue())) {
                Set<String> ids = new LinkedHashSet<String>();
                for (WorkflowProgress.Step step : entry.getValue()) {
                    ids.add(step.getId());
                }
                return ids;
            }
        }
        return Collections.emptySet();
    }

    /**
     * 判断一层是不是已经翻篇：全部成功或跳过。
     *
     * @param layer 该层的步骤
     * @return 可以撤掉返回 {@code true}
     */
    private static boolean isSettledLayer(List<WorkflowProgress.Step> layer) {
        for (WorkflowProgress.Step step : layer) {
            StepState state = step.getState();
            if (state != StepState.DONE && state != StepState.SKIPPED) {
                return false;
            }
        }
        return true;
    }

    /**
     * 裁到预算之内：留最后一行说清「还有几步没显示」。
     *
     * @param bodies 全部正文行
     * @param limit  可用行数
     * @return 结果，保证非 {@code null}
     */
    private static Result truncated(List<Body> bodies, int limit) {
        if (limit == 1) {
            return new Result(Collections.<UiLine>emptyList(), countSteps(bodies));
        }
        List<Body> kept = new ArrayList<Body>(bodies.subList(0, limit - 1));
        int hidden = 0;
        for (int i = kept.size(); i < bodies.size(); i++) {
            hidden += bodies.get(i).stepCount;
        }
        List<UiLine> lines = linesOf(kept);
        lines.add(UiLine.of(UiSegment.of(WorkflowText.ELLIPSIS + "另有 " + hidden + " 步未显示",
                UiEmphasis.DIM)));
        return new Result(lines, hidden);
    }

    /**
     * 统计一批正文行承载的步骤数。
     *
     * @param bodies 正文行
     * @return 步骤数
     */
    private static int countSteps(List<Body> bodies) {
        int count = 0;
        for (Body body : bodies) {
            count += body.stepCount;
        }
        return count;
    }

    /**
     * 取一批正文行的界面行。
     *
     * @param bodies 正文行
     * @return 界面行列表
     */
    private static List<UiLine> linesOf(List<Body> bodies) {
        List<UiLine> lines = new ArrayList<UiLine>(bodies.size());
        for (Body body : bodies) {
            lines.add(body.line);
        }
        return lines;
    }

    /**
     * 退化成清单：每步一行，行尾标出依赖的步骤标识。
     * <p>
     * <b>清单也要能回答「谁在等谁」</b>：所以行尾带上 {@code <- 上游}，而不是干脆把依赖丢掉。
     *
     * @param kept  保留的步骤（声明顺序）
     * @param nodes 节点
     * @return 正文行，保证非 {@code null}
     */
    private static List<Body> plain(List<WorkflowProgress.Step> kept, Map<String, Node> nodes) {
        List<Body> bodies = new ArrayList<Body>(kept.size());
        for (WorkflowProgress.Step step : kept) {
            StringBuilder text = new StringBuilder(nodes.get(step.getId()).text);
            if (!step.getNeeds().isEmpty()) {
                text.append(" <- ").append(String.join("\u3001", step.getNeeds()));
            }
            bodies.add(new Body(UiLine.of(UiSegment.of(text.toString(), emphasisOf(step.getState()))), 1));
        }
        return bodies;
    }

    /**
     * 构造一个节点的渲染文本。
     *
     * @param step      步骤
     * @param nowMillis 当前时刻
     * @return 节点，保证非 {@code null}
     */
    private static Node nodeOf(WorkflowProgress.Step step, long nowMillis) {
        String prefix = "L" + step.getLayer() + " " + step.getState().mark();
        String suffix = "\uff08" + WorkflowText.clip(step.getAgent(), AGENT_WIDTH_BUDGET) + "\uff09";
        String running = runningText(step, nowMillis);
        String name = step.getName() == null || step.getName().trim().isEmpty()
                ? step.getId() : step.getName().trim();
        int fixed = WorkflowText.displayWidth(prefix) + WorkflowText.displayWidth(suffix)
                + WorkflowText.displayWidth(running) + WorkflowText.displayWidth(WorkflowText.ELLIPSIS);
        String text = prefix + WorkflowText.clip(name,
                Math.min(NAME_WIDTH_BUDGET, Math.max(2, NODE_WIDTH_BUDGET - fixed))) + suffix + running;
        return new Node(step.getId(), text, step.getState());
    }

    /**
     * 取「在跑的那一步」的耗时后缀。
     * <p>
     * <b>只有正在跑的那一步带耗时</b>：已结束的步骤不需要再证明自己跑过，未开始的没有耗时可说。
     * 真正回答「卡没卡」的是标题行上的整条编排耗时，以及这一步的「还在动」。
     *
     * @param step      步骤
     * @param nowMillis 当前时刻
     * @return 后缀文本；不在跑时返回空串
     */
    private static String runningText(WorkflowProgress.Step step, long nowMillis) {
        if (step.getState() != StepState.RUNNING || step.getStartedAt() <= 0L) {
            return "";
        }
        return " 已 " + WorkflowText.elapsed(nowMillis - step.getStartedAt());
    }

    /**
     * 取状态对应的强调档位。
     *
     * @param state 状态
     * @return 强调档位，保证非 {@code null}
     */
    private static UiEmphasis emphasisOf(StepState state) {
        switch (state) {
            case RUNNING:
                return UiEmphasis.ACCENT;
            case DONE:
            case SKIPPED:
                return UiEmphasis.DIM;
            case FAILED:
                return UiEmphasis.ERROR;
            default:
                return UiEmphasis.NORMAL;
        }
    }

    /** 层升序、层内声明序。 */
    private static final Comparator<WorkflowProgress.Step> BY_LAYER_THEN_ORDER =
            new Comparator<WorkflowProgress.Step>() {

                @Override
                public int compare(WorkflowProgress.Step left, WorkflowProgress.Step right) {
                    int byLayer = Integer.compare(left.getLayer(), right.getLayer());
                    return byLayer != 0 ? byLayer : Integer.compare(left.getOrder(), right.getOrder());
                }
            };

    /**
     * 一次排版的结果。
     */
    static final class Result {

        /** 正文行（不含标题）。 */
        private final List<UiLine> lines;

        /** 没能显示出来的步骤数（0 表示全画上了）。 */
        private final int hiddenSteps;

        /**
         * 构造结果。
         *
         * @param lines       正文行
         * @param hiddenSteps 没能显示出来的步骤数
         */
        private Result(List<UiLine> lines, int hiddenSteps) {
            this.lines = Collections.unmodifiableList(new ArrayList<UiLine>(lines));
            this.hiddenSteps = hiddenSteps;
        }

        /**
         * 获取正文行。
         *
         * @return 不可变列表，保证非 {@code null}
         */
        List<UiLine> getLines() {
            return lines;
        }

        /**
         * 获取没能显示出来的步骤数。
         *
         * @return 步骤数，保证非负
         */
        int getHiddenSteps() {
            return hiddenSteps;
        }
    }

    /** 一行正文：界面行 + 它承载的步骤数（裁行时要按步骤数据实汇报）。 */
    private static final class Body {

        /** 界面行。 */
        private final UiLine line;

        /** 该行承载的步骤数。 */
        private final int stepCount;

        /**
         * 构造正文行。
         *
         * @param line      界面行
         * @param stepCount 步骤数
         */
        private Body(UiLine line, int stepCount) {
            this.line = line;
            this.stepCount = stepCount;
        }
    }

    /** 一个节点：渲染文本 + 状态。 */
    private static final class Node {

        /** 步骤标识。 */
        private final String id;

        /** 渲染文本（已按预算截断）。 */
        private final String text;

        /** 状态（决定强调档位）。 */
        private final StepState state;

        /**
         * 构造节点。
         *
         * @param id    步骤标识
         * @param text  渲染文本
         * @param state 状态
         */
        private Node(String id, String text, StepState state) {
            this.id = id;
            this.text = text;
            this.state = state;
        }
    }

    /** 一行在连线里的角色。 */
    private enum Glyph {

        /** 不参与连线。 */
        NONE,

        /** 汇合点所在行。 */
        OWN,

        /** 上游行：行尾拐上去。 */
        LEFT,

        /** 分叉的落点行：行首从此列开始。 */
        RIGHT
    }

    /** 一行：左到右的节点序列，可能挂一个汇合点。 */
    private static final class Row {

        /** 行号（建行顺序），由 {@link #plan} 在收尾时统一填。 */
        private int index;

        /** 本行的节点（左到右）。 */
        private final List<Node> nodes = new ArrayList<Node>();

        /** 行首「接自上方」标记。 */
        private boolean entry;

        /** 本行的连线角色。 */
        private Glyph glyph = Glyph.NONE;

        /** 本行挂的汇合点（本行是汇合点或上游行时非空）。 */
        private Junction junction;

        /** 竖线穿过的列；没有时小于 0。 */
        private int passColumn = -1;

        /**
         * 追加一个节点。
         *
         * @param node 节点
         */
        private void add(Node node) {
            nodes.add(node);
        }

        /**
         * 判断某个节点是不是本行的行尾。
         *
         * @param nodeId 步骤标识
         * @return 是行尾返回 {@code true}
         */
        private boolean isTail(String nodeId) {
            return !nodes.isEmpty() && nodes.get(nodes.size() - 1).id.equals(nodeId);
        }

        /**
         * 判断「在这一行后面再接一个节点」是否放得下。
         *
         * @param text 待接上的节点文本
         * @return 放得下返回 {@code true}
         */
        private boolean fits(String text) {
            return width(nodes.size()) + WorkflowText.displayWidth(ARROW) + WorkflowText.displayWidth(text)
                    <= ROW_WIDTH_BUDGET;
        }

        /**
         * 取某个节点的下标。
         *
         * @param nodeId 节点标识
         * @return 下标；不在本行时返回 -1
         */
        private int indexOf(String nodeId) {
            for (int i = 0; i < nodes.size(); i++) {
                if (nodes.get(i).id.equals(nodeId)) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * 取本行前若干个节点（不含连线字形）的显示宽度。
         *
         * @param count 节点个数
         * @return 显示宽度
         */
        private int width(int count) {
            int width = entry ? WorkflowText.displayWidth(ENTRY) : 0;
            for (int i = 0; i < Math.min(count, nodes.size()); i++) {
                if (i > 0) {
                    width += WorkflowText.displayWidth(ARROW);
                }
                width += WorkflowText.displayWidth(nodes.get(i).text);
            }
            return width;
        }

        /**
         * 取本行某个下标之后的节点（用箭头相连）的显示宽度。
         *
         * @param from 起始下标（含）
         * @return 显示宽度
         */
        private int widthFrom(int from) {
            int width = 0;
            for (int i = from; i < nodes.size(); i++) {
                if (i > from) {
                    width += WorkflowText.displayWidth(ARROW);
                }
                width += WorkflowText.displayWidth(nodes.get(i).text);
            }
            return width;
        }
    }

    /** 一个汇合点：竖线、它的两条臂，以及参与的行。 */
    private static final class Junction {

        /** 汇合点所在行在 {@code rows} 里的下标。 */
        private int ownRow;

        /** 汇合点插在该行第几个节点之前。 */
        private int beforeIndex;

        /** 该行在汇合点之后没有内容（画 {@code ┐} 而不是 {@code ┬}）。 */
        private boolean atRowEnd;

        /** 列位置。 */
        private int column;

        /** 竖线最下的行。 */
        private int bottomRow;

        /** 是不是分叉（出口在上行、落点在下行）。 */
        private boolean fan;

        /** 节点在左、需要 {@code ───┤}/{@code ───┘} 收尾的行。 */
        private final List<Integer> leftRows = new ArrayList<Integer>();

        /** 节点在右、需要以 {@code └──>} 开头的行。 */
        private final List<Integer> rightRows = new ArrayList<Integer>();

        /**
         * 构造一个分叉汇合点。
         *
         * @param beforeIndex 出口插在上行第几个节点之后
         * @param atRowEnd    出口节点就是该行的行尾
         * @return 汇合点
         */
        private static Junction fan(int beforeIndex, boolean atRowEnd) {
            Junction junction = new Junction();
            junction.fan = true;
            junction.beforeIndex = beforeIndex;
            junction.atRowEnd = atRowEnd;
            return junction;
        }

        /**
         * 构造一个汇合点。
         *
         * @param beforeIndex 汇合处插在下游所在行的第几个节点之前
         * @return 汇合点
         */
        private static Junction join(int beforeIndex) {
            Junction junction = new Junction();
            junction.fan = false;
            junction.beforeIndex = beforeIndex;
            return junction;
        }
    }

    /**
     * 一张排好的图：行、汇合点，以及它们的列位置。
     * <p>
     * 建不出来的两种情形都在 {@link #of(List, List, Map)} 里判掉：汇合点落在行首（它的左臂没地方去），
     * 或者某行的宽度越界、竖线穿不过去。它们都返回 {@code null}，由调用方退化成清单。
     */
    private static final class Board {

        /** 行。 */
        private final List<Row> rows;

        /** 汇合点。 */
        private final List<Junction> junctions;

        /**
         * 构造图。
         *
         * @param rows      行
         * @param junctions 汇合点
         */
        private Board(List<Row> rows, List<Junction> junctions) {
            this.rows = rows;
            this.junctions = junctions;
        }

        /**
         * 建图：算列位置并检查能不能画下。
         *
         * @param rows      行
         * @param junctions 汇合点
         * @param nodes     节点（未用到，保留给后续扩展）
         * @return 图；画不出来时返回 {@code null}
         */
        private static Board of(List<Row> rows, List<Junction> junctions, Map<String, Node> nodes) {
            for (Junction junction : junctions) {
                Row own = rows.get(junction.ownRow);
                if (!junction.fan && junction.beforeIndex <= 0) {
                    // 汇合点落在行首：左臂要与左边的节点相接，而行首左边什么都没有
                    return null;
                }
                int column = own.width(junction.beforeIndex) + JUNCTION_LEAD;
                for (Integer rowIndex : junction.leftRows) {
                    Row left = rows.get(rowIndex.intValue());
                    column = Math.max(column, left.width(left.nodes.size()) + 2);
                }
                junction.column = column;
                if (junction.atRowEnd) {
                    if (column + 1 > ROW_WIDTH_BUDGET) {
                        return null;
                    }
                } else if (column + 1 + WorkflowText.displayWidth(TAIL)
                        + own.widthFrom(junction.beforeIndex) > ROW_WIDTH_BUDGET) {
                    return null;
                }
                for (Integer rowIndex : junction.leftRows) {
                    if (column > ROW_WIDTH_BUDGET) {
                        return null;
                    }
                }
                for (Integer rowIndex : junction.rightRows) {
                    Row target = rows.get(rowIndex.intValue());
                    if (column + 1 + WorkflowText.displayWidth(TAIL) + target.width(target.nodes.size())
                            > ROW_WIDTH_BUDGET) {
                        return null;
                    }
                }
                int bottom = junction.ownRow;
                for (Integer rowIndex : junction.leftRows) {
                    bottom = Math.max(bottom, rowIndex.intValue());
                }
                for (Integer rowIndex : junction.rightRows) {
                    bottom = Math.max(bottom, rowIndex.intValue());
                }
                junction.bottomRow = bottom;
                for (int i = junction.ownRow + 1; i <= bottom; i++) {
                    Row passRow = rows.get(i);
                    if (junction.leftRows.contains(Integer.valueOf(i))
                            || junction.rightRows.contains(Integer.valueOf(i))) {
                        continue;
                    }
                    if (passRow.glyph != Glyph.NONE || passRow.passColumn >= 0
                            || passRow.width(passRow.nodes.size()) >= column) {
                        // 这一行自己的内容已经越过竖线，或者也要画字形：竖线过不去
                        return null;
                    }
                    passRow.passColumn = column;
                }
            }
            return new Board(rows, junctions);
        }

        /**
         * 把每一行渲染成界面行。
         *
         * @param nodes 节点
         * @return 正文行，保证非 {@code null}
         */
        private List<Body> render(Map<String, Node> nodes) {
            List<Body> bodies = new ArrayList<Body>(rows.size());
            for (Row row : rows) {
                bodies.add(new Body(lineOf(row), row.nodes.size()));
            }
            return bodies;
        }

        /**
         * 渲染一行。
         *
         * @param row 行
         * @return 界面行，保证非 {@code null}
         */
        private static UiLine lineOf(Row row) {
            List<UiSegment> segments = new ArrayList<UiSegment>();
            Junction junction = row.junction;
            if (row.glyph == Glyph.RIGHT) {
                segments.add(UiSegment.of(spaces(junction.column) + FAN_ELBOW + TAIL, UiEmphasis.NORMAL));
            } else if (row.entry) {
                segments.add(UiSegment.of(ENTRY, UiEmphasis.DIM));
            }
            int width = widthOf(segments);
            for (int i = 0; i < row.nodes.size(); i++) {
                Node node = row.nodes.get(i);
                if (i > 0) {
                    String separator = junction != null && junction.ownRow == row.index
                            && !junction.atRowEnd && junction.beforeIndex == i
                            ? lead(junction.column - width) + BRANCH + TAIL
                            : ARROW;
                    segments.add(UiSegment.of(separator, UiEmphasis.NORMAL));
                    width += WorkflowText.displayWidth(separator);
                }
                segments.add(UiSegment.of(node.text, emphasisOf(node.state)));
                width += WorkflowText.displayWidth(node.text);
            }
            if (row.glyph == Glyph.OWN && junction.atRowEnd) {
                segments.add(UiSegment.of(lead(junction.column - width) + BRANCH_END, UiEmphasis.NORMAL));
            } else if (row.glyph == Glyph.LEFT) {
                char glyph = row.index == junction.bottomRow ? JOIN_ELBOW : TEE;
                segments.add(UiSegment.of(lead(junction.column - width) + glyph, UiEmphasis.NORMAL));
            } else if (row.passColumn >= 0 && row.passColumn > width) {
                segments.add(UiSegment.of(spaces(row.passColumn - width) + VERTICAL, UiEmphasis.NORMAL));
            }
            return new UiLine(segments);
        }

        /**
         * 取「到目标列之前」的那一段：一个空格 + 若干横线。
         *
         * @param room 从当前位置到目标列还差多少列（目标列本身不含在内）
         * @return 文本，保证非 {@code null}
         */
        private static String lead(int room) {
            return " " + repeat(DASH, Math.max(1, room - 1));
        }

        /**
         * 取已拼好的段的显示宽度。
         *
         * @param segments 段列表
         * @return 显示宽度
         */
        private static int widthOf(List<UiSegment> segments) {
            int width = 0;
            for (UiSegment segment : segments) {
                width += WorkflowText.displayWidth(segment.getText());
            }
            return width;
        }

        /**
         * 生成一串重复字符。
         *
         * @param character 字符
         * @param count     个数，小于 0 时按 0 处理
         * @return 文本，保证非 {@code null}
         */
        private static String repeat(char character, int count) {
            StringBuilder text = new StringBuilder(Math.max(0, count));
            for (int i = 0; i < count; i++) {
                text.append(character);
            }
            return text.toString();
        }

        /**
         * 生成一串空格。
         *
         * @param count 个数，小于 0 时按 0 处理
         * @return 文本，保证非 {@code null}
         */
        private static String spaces(int count) {
            return repeat(' ', count);
        }
    }
}
