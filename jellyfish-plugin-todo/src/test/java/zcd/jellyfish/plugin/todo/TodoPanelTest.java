package zcd.jellyfish.plugin.todo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.event.notification.AgentRunProgressEvent;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.subagent.DelegationStatus;
import zcd.jellyfish.api.ui.UiEmphasis;
import zcd.jellyfish.api.ui.UiRegion;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TodoPanel} 的单元测试。
 *
 * @author zcd
 */
@DisplayName("待办面板贡献")
class TodoPanelTest {

    /** 每个用例一个独立目录。 */
    @TempDir
    Path directory;

    /** 被测仓库。 */
    private TodoStore store;

    /** 认领者在场记录。 */
    private RunPresence presence;

    /** 被测处理器。 */
    private TodoPanel panel;

    @BeforeEach
    void setUp() {
        store = new TodoStore(directory);
        presence = new RunPresence();
        panel = new TodoPanel(store, presence);
    }

    @Test
    @DisplayName("把清单渲染成面板：标题 + 每行一条 + 建议左栏")
    void handle_should_renderList() {
        store.replace("s-1", Arrays.asList(new TodoItem("写文档", TodoStatus.COMPLETED),
                new TodoItem("跑测试", TodoStatus.IN_PROGRESS), new TodoItem("提交", TodoStatus.PENDING)));

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s-1"));

        assertEquals("待办", contribution.getTitle());
        assertEquals(UiRegion.LEFT, contribution.getPreferredRegion());
        assertEquals(3, contribution.getLines().size());
        assertEquals("[x] 写文档", contribution.getLines().get(0).text());
        assertEquals("[~] 跑测试", contribution.getLines().get(1).text());
        assertEquals("[ ] 提交", contribution.getLines().get(2).text());
    }

    @Test
    @DisplayName("三态强调各不相同：做完的退到背景，正在做的要被扫到")
    void handle_should_emphasizeByStatus() {
        store.replace("s-1", Arrays.asList(new TodoItem("写文档", TodoStatus.COMPLETED),
                new TodoItem("跑测试", TodoStatus.IN_PROGRESS), new TodoItem("提交", TodoStatus.PENDING)));

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s-1"));

        assertEquals(UiEmphasis.DIM, contribution.getLines().get(0).getSegments().get(0).getEmphasis());
        assertEquals(UiEmphasis.ACCENT, contribution.getLines().get(1).getSegments().get(0).getEmphasis());
        assertEquals(UiEmphasis.NORMAL, contribution.getLines().get(2).getSegments().get(0).getEmphasis());
    }

    @Test
    @DisplayName("没有待办时不贡献：面板要独占一整个区域，没内容就不该抢地盘")
    void handle_should_returnEmpty_when_noTodos() {
        assertTrue(panel.handle(new PanelContributionRequest("s-1")).isEmpty());
    }

    @Test
    @DisplayName("没有会话上下文时不贡献，不抛异常打扰界面")
    void handle_should_returnEmpty_when_noSession() {
        assertTrue(panel.handle(new PanelContributionRequest(null)).isEmpty());
    }

    @Test
    @DisplayName("只读当前会话那一份，别的会话的待办不进本会话的面板")
    void handle_should_ignoreOtherSessions() {
        store.replace("s-2", Collections.singletonList(new TodoItem("别人的事", TodoStatus.PENDING)));

        assertTrue(panel.handle(new PanelContributionRequest("s-1")).isEmpty());
    }
    @Test
    @DisplayName("被认领的条目带上「谁在做」；没认领的条目不添这一截")
    void handle_should_showOwnerWhenClaimed() {
        store.replace("s-1", Arrays.asList(new TodoItem("跑测试", TodoStatus.IN_PROGRESS, "run-1"),
                new TodoItem("提交", TodoStatus.PENDING)));
        presence.on(AgentRunProgressEvent.started("run-1", null, "root-1", "s-1", "researcher"));

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s-1"));

        assertEquals("[~] 跑测试 · researcher", contribution.getLines().get(0).text());
        assertEquals("[ ] 提交", contribution.getLines().get(1).text());
    }

    @Test
    @DisplayName("认领者已结束而待办还挂在进行中：转警示档，提醒这条没人收尾")
    void handle_should_warnWhenClaimantFinished() {
        store.replace("s-1", Arrays.asList(new TodoItem("跑测试", TodoStatus.IN_PROGRESS, "run-1")));
        presence.on(AgentRunProgressEvent.finished("run-1", null, "root-1", "s-1", "researcher",
                DelegationStatus.FAILED, 1, 5L));

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s-1"));

        assertEquals("[~] 跑测试 · researcher（已结束）", contribution.getLines().get(0).text());
        assertEquals(UiEmphasis.WARN, contribution.getLines().get(0).getSegments().get(2).getEmphasis());
    }

    @Test
    @DisplayName("不知道是谁：说「认领者未知」，不虚构一个正在跑的人")
    void handle_should_admitUnknownClaimant() {
        store.replace("s-1", Arrays.asList(new TodoItem("跑测试", TodoStatus.IN_PROGRESS, "run-404")));

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s-1"));

        assertEquals("[~] 跑测试 · 认领者未知", contribution.getLines().get(0).text());
        assertEquals(UiEmphasis.DIM, contribution.getLines().get(0).getSegments().get(2).getEmphasis());
    }
    @Test
    @DisplayName("卡住的条目显示原因（转 ERROR 档），并让位给原因而不显示认领者")
    void handle_should_showBlockReason() {
        store.replace("s-1", Arrays.asList(new TodoItem("核对缓存策略", TodoStatus.BLOCKED, "run-1", "缺写权限")));

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s-1"));

        assertEquals("[!] 核对缓存策略 —— 缺写权限", contribution.getLines().get(0).text());
        assertEquals(UiEmphasis.ERROR, contribution.getLines().get(0).getSegments().get(0).getEmphasis());
        // 三段：标记、内容、原因——没有第四段「谁在做」
        assertEquals(3, contribution.getLines().get(0).getSegments().size());
        assertTrue(contribution.getLines().get(0).text().indexOf(" · ") < 0,
                "卡住时原因占掉认领者那一截: " + contribution.getLines().get(0).text());
    }

    @Test
    @DisplayName("原因过长时截断：面板一行放不下，长原因会把内容挤没")
    void handle_should_truncateLongReason() {
        StringBuilder longReason = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            longReason.append("很长");
        }
        store.replace("s-1", Arrays.asList(new TodoItem("甲", TodoStatus.BLOCKED, null, longReason.toString())));

        String text = panel.handle(new PanelContributionRequest("s-1")).getLines().get(0).text();

        assertTrue(text.endsWith("…"), text);
        assertEquals(("[!] 甲 —— ").length() + 40 + 1, text.length(), text);
    }
}
