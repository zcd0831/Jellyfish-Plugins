package zcd.jellyfish.plugin.todo;

import zcd.jellyfish.api.JellyfishException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 待办仓库：一个会话一个 JSON 文件，读时懒加载、写时原子替换。
 * <p>
 * <b>为什么一个会话一个文件</b>：与内核会话同构，会话之间互不影响；将来要 git 管理或搜索也是按会话走。
 * <p>
 * <b>为什么要有一层内存缓存</b>：提示词贡献在<b>每一轮</b>请求组装时都会被问到，每次都读盘既慢又无谓；
 * 而待办只有经 {@link #replace} 才会变，缓存与文件因此天然一致。
 * <p>
 * <b>读不出来就抛，不当作空</b>：如果把坏文件当成「没有待办」，紧接着的一次 {@code todo_write} 就会把
 * 坏文件覆盖掉——那是真正的数据丢失。抛出去让调用点决定（提示词贡献跳过、命令报错、工具把错误回给模型），
 * 坏文件至少还在原处。
 * <p>
 * <b>先写临时文件再原子替换</b>：直接覆盖写时进程被杀死会留下半截 JSON。临时文件用 {@code .json.tmp}
 * 后缀，因此不会被读路径当成待办文件。
 * <p>
 * 文件操作同步阻塞在调用线程上（ReAct 回合线程或外壳线程）——待办变更本就该「写完才算数」。
 * <p>
 * <b>并发正确性靠本类的方法锁，而不是靠内核的条件写入</b>：认领与完成都是「读出整个列表 → 改其中一条 →
 * 写回」，两个子代理同时做就会丢更新。凡是这类读-改-写，全部在<b>同一个 {@code synchronized} 方法内</b>
 * 完成——本类是进程内单实例，因此这把方法锁就是那把唯一的锁。调用方拿不到「读一半」的列表，
 * 也就不可能自己拼出一次有竞态的读-改-写。
 * <p>
 * <b>为什么不给内核加一条 compare-and-set</b>：容器的实际形态是插件自持的文件，内核那份会话级容器
 * 到今天没有任务类消费方；为它先补并发原语是在猜未来的形状，而比较语义（值相等还是版本号）一旦
 * 定下来就成了第三方插件依赖的公开契约。
 *
 * @author zcd
 */
final class TodoStore {

    /** 待办文件后缀。 */
    private static final String SUFFIX = ".json";

    /** 原子替换用的临时后缀，刻意不匹配 {@link #SUFFIX}。 */
    private static final String TEMP_SUFFIX = ".tmp";

    /** 待办文件所在目录。 */
    private final Path directory;

    /** sessionId → 该会话的待办列表；只由本类的方法访问，全部在实例锁内。 */
    private final Map<String, List<TodoItem>> cache = new HashMap<String, List<TodoItem>>();

    /**
     * 构造仓库。
     *
     * @param directory 待办文件目录，不可为 {@code null}
     */
    TodoStore(Path directory) {
        this.directory = directory;
    }

    /**
     * 取某会话的待办列表，首次访问时从文件懒加载。
     *
     * @param sessionId 会话标识，不可为空白且需可安全作为文件名
     * @return 不可修改的待办列表，没有待办时为空列表
     * @throws JellyfishException 会话标识非法，或文件存在但读不出来时抛出
     */
    synchronized List<TodoItem> itemsOf(String sessionId) {
        String key = requireSafeFileName(sessionId);
        List<TodoItem> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        List<TodoItem> loaded = load(key);
        cache.put(key, loaded);
        return loaded;
    }

    /**
     * 用整份新列表覆盖某会话的待办。
     * <p>
     * 覆盖而不是增量：这是模型的写入语义（它看不到稳定的编号），也顺带让「删除」与「重排」无需额外命令。
     *
     * @param sessionId 会话标识，不可为空白且需可安全作为文件名
     * @param items     新的待办列表，不可为 {@code null}
     * @return 覆盖后的不可修改列表，保证非 {@code null}
     * @throws JellyfishException 会话标识非法或落盘失败时抛出
     */
    synchronized List<TodoItem> replace(String sessionId, List<TodoItem> items) {
        String key = requireSafeFileName(sessionId);
        List<TodoItem> copy = immutableCopy(items);
        persist(key, inheritClaims(itemsOf(key), copy));
        return cache.get(key);
    }

    /**
     * 认领第一条还没人做的待办。
     * <p>
     * <b>不变量是「同一 run 至多一条未了结的认领」</b>：手上还有没做完的那条就直接返回它，
     * 而不是再领一条（否则一个子代理多调一次就会把两件事都揽到自己名下，而它的提示词里只写了其中一件）。
     * **做完之后可以接着领下一条**：{@link #complete} 会把状态改成已完成，于是下一次调用不再命中
     * 「未了结」那一支，自然就去领新的。worker 连着干活因此不需要任何额外机制。
     * <p>
     * <b>认领不到不等待、不重试</b>：立即返回 {@link TodoActionResult.Code#NONE_PENDING}。
     * 等待会让「这个 run 要跑多久」变得不可预测，而那是拉取式工作队列的形状——本项目明确不做。
     *
     * @param sessionId 会话标识，不可为空白且需可安全作为文件名
     * @param runId     认领者的 run 标识，不可为空白
     * @return 认领结果，保证非 {@code null}
     * @throws JellyfishException 会话标识或 run 标识非法、或落盘失败时抛出
     */
    synchronized TodoActionResult claim(String sessionId, String runId) {
        String key = requireSafeFileName(sessionId);
        if (runId == null || runId.trim().isEmpty()) {
            throw new JellyfishException("认领待办需要一个 run 标识，当前没有");
        }
        List<TodoItem> items = itemsOf(key);
        for (TodoItem item : items) {
            if (item.ownedBy(runId) && item.status() == TodoStatus.IN_PROGRESS) {
                // 未了结的那条优先：已经领了还在做，就不该再领第二条
                return TodoActionResult.ok(item);
            }
        }
        for (int i = 0; i < items.size(); i++) {
            TodoItem item = items.get(i);
            if (item.status() == TodoStatus.PENDING && item.owner() == null) {
                List<TodoItem> updated = new ArrayList<TodoItem>(items);
                TodoItem claimed = item.with(TodoStatus.IN_PROGRESS, runId, null);
                updated.set(i, claimed);
                persist(key, updated);
                return TodoActionResult.ok(claimed);
            }
        }
        return TodoActionResult.failed(TodoActionResult.Code.NONE_PENDING);
    }

    /**
     * 把一条待办标成完成。
     * <p>
     * <b>按内容定位</b>：内容就是模型手上唯一的把手（编号在落盘里刻意不存在）。
     * 两条内容完全相同的待办命中的是第一条——那种计划本来就有歧义，而认领者会把正确的那条做完。
     * <p>
     * <b>归属判定</b>：别人正认领着的条目不允许被改（{@link TodoActionResult.Code#TAKEN}），
     * 而无主的条目谁都可以标完成——父回合的模型在回收计划时就属于这种情况。
     * <p>
     * <b>为什么父回合不能替 worker 说「做完了」</b>：放回与卡住是清理（{@link #release} / {@link #block}
     * 都允许协调者），而完成是一个关于工作结果的声明。父回合若确实知道它做完了，正确动作是整表覆盖
     * （它手上有结论），而不是替别人签字。
     *
     * @param sessionId 会话标识，不可为空白且需可安全作为文件名
     * @param content   待办内容，不可为空白
     * @param runId     调用方的 run 标识，可为 {@code null}（父回合不在任何 run 上）
     * @return 完成结果，保证非 {@code null}
     * @throws JellyfishException 会话标识非法、内容为空白或落盘失败时抛出
     */
    synchronized TodoActionResult complete(String sessionId, String content, String runId) {
        String key = requireSafeFileName(sessionId);
        String text = requireContent(content);
        List<TodoItem> items = itemsOf(key);
        for (int i = 0; i < items.size(); i++) {
            TodoItem item = items.get(i);
            if (!item.content().equals(text)) {
                continue;
            }
            if (item.owner() != null && !item.ownedBy(runId)) {
                return TodoActionResult.failed(TodoActionResult.Code.TAKEN);
            }
            List<TodoItem> updated = new ArrayList<TodoItem>(items);
            TodoItem done = item.with(TodoStatus.COMPLETED, item.owner(), null);
            updated.set(i, done);
            persist(key, updated);
            return TodoActionResult.ok(done);
        }
        return TodoActionResult.failed(TodoActionResult.Code.NOT_FOUND);
    }

    /**
     * 把一条待办放回去：清掉认领者并回到未开始，让谁都能接。
     * <p>
     * <b>与「卡住」的区别</b>：放回说的是「我没做它」（领错了、没时间、被叫去做别的），
     * 卡住说的是「它做不了」。人看到这两种情况要做的事完全不同，因此是两个动作而不是一个。
     * <p>
     * <b>谁可以放回</b>：认领者自己，或没有 run 上下文的父回合（收拾残局）。
     * 已完成的不接受放回：那是悄悄撤销一件已经做完的事。
     *
     * @param sessionId 会话标识，不可为空白且需可安全作为文件名
     * @param content   待办内容，不可为空白
     * @param runId     调用方的 run 标识，可为 {@code null}（父回合不在任何 run 上）
     * @return 放回结果，保证非 {@code null}
     * @throws JellyfishException 会话标识非法、内容为空白或落盘失败时抛出
     */
    synchronized TodoActionResult release(String sessionId, String content, String runId) {
        String key = requireSafeFileName(sessionId);
        String text = requireContent(content);
        List<TodoItem> items = itemsOf(key);
        for (int i = 0; i < items.size(); i++) {
            TodoItem item = items.get(i);
            if (!item.content().equals(text)) {
                continue;
            }
            TodoActionResult.Code failure = movableFailure(item, runId);
            if (failure != null) {
                return TodoActionResult.failed(failure, item);
            }
            List<TodoItem> updated = new ArrayList<TodoItem>(items);
            TodoItem released = item.with(TodoStatus.PENDING, null, null);
            updated.set(i, released);
            persist(key, updated);
            return TodoActionResult.ok(released);
        }
        return TodoActionResult.failed(TodoActionResult.Code.NOT_FOUND);
    }

    /**
     * 把一条待办记成卡住，并写下为什么。
     * <p>
     * 卡住的条目**不再被认领**（认领只找未开始的），因此它不会在后续批次里被反复领走、反复失败——
     * 那正是抢单式协作最容易烧钱的地方。要让它重新可做，得有人先 {@link #release} 它（换个做法）
     * 或者用整表重写改掉这条。
     * <p>
     * 认领者保留：谁卡的也是信息，而原因文本才是人做决定时真正要看的东西。
     *
     * @param sessionId 会话标识，不可为空白且需可安全作为文件名
     * @param content   待办内容，不可为空白
     * @param runId     调用方的 run 标识，可为 {@code null}（父回合不在任何 run 上）
     * @param reason    卡住的原因，不可为空白
     * @return 卡住结果，保证非 {@code null}
     * @throws JellyfishException 会话标识非法、内容或原因为空白、或落盘失败时抛出
     */
    synchronized TodoActionResult block(String sessionId, String content, String runId, String reason) {
        String key = requireSafeFileName(sessionId);
        String text = requireContent(content);
        if (reason == null || reason.trim().isEmpty()) {
            throw new JellyfishException("卡住一条待办必须说明原因");
        }
        List<TodoItem> items = itemsOf(key);
        for (int i = 0; i < items.size(); i++) {
            TodoItem item = items.get(i);
            if (!item.content().equals(text)) {
                continue;
            }
            TodoActionResult.Code failure = movableFailure(item, runId);
            if (failure != null) {
                return TodoActionResult.failed(failure, item);
            }
            List<TodoItem> updated = new ArrayList<TodoItem>(items);
            TodoItem blocked = item.with(TodoStatus.BLOCKED, item.owner(), reason.trim());
            updated.set(i, blocked);
            persist(key, updated);
            return TodoActionResult.ok(blocked);
        }
        return TodoActionResult.failed(TodoActionResult.Code.NOT_FOUND);
    }

    /**
     * 校验内容非空白。
     *
     * @param content 待办内容，可为 {@code null}
     * @return 去空白后的内容，保证非空白
     * @throws JellyfishException 内容为空白时抛出
     */
    private static String requireContent(String content) {
        if (content == null || content.trim().isEmpty()) {
            throw new JellyfishException("待办内容不能为空");
        }
        return content;
    }

    /**
     * 判定调用方能不能清理这一条（放回与卡住共用）。
     * <p>
     * <b>「没有 run 上下文」= 协调者视角</b>（父回合的模型，或外壳发起的调用），它对这个协作空间里的
     * 任何一条都有处置权——这正是「worker 死了留下一条进行中」的解法：人/父回合看见就能放回去换人做。
     * 而**有** run 上下文时它只是一个 worker，只能动自己认领的那条（无主的也可以，与完成同口径）。
     * <p>
     * <b>完成不走这条判定</b>：见 {@link #complete}——「做完了」是一个关于工作结果的声明，
     * 该由做过的人说，或由明确重写计划的人说（整表覆盖）。
     *
     * @param item  目标条目，不可为 {@code null}
     * @param runId 调用方的 run 标识，可为 {@code null}
     * @return 不能清理时返回失败码；可以清理返回 {@code null}
     */
    private static TodoActionResult.Code movableFailure(TodoItem item, String runId) {
        if (item.status() == TodoStatus.COMPLETED) {
            return TodoActionResult.Code.WRONG_STATE;
        }
        if (runId == null || runId.trim().isEmpty()) {
            return null;
        }
        if (item.owner() != null && !item.ownedBy(runId)) {
            return TodoActionResult.Code.TAKEN;
        }
        return null;
    }

    /**
     * 让新列表继承上一份里已有的认领。
     * <p>
     * <b>为什么必须有这一步</b>：父回合的模型是用「整表覆盖」写计划的，而它看不到认领者、
     * 也可能懒得抄卡住的原因，写回来的条目自然不带这些。若不做继承，父回合随手一次重写就会把
     * 正在干活的子代理的认领抹掉（于是另一个 run 可能领到同一件事，而第一个 run 的
     * {@code todo_done} 会突然「不是你的了」），或者让一条卡住的活丢掉原因、看起来像没人做。
     * <p>
     * <b>黏的是「模型看不见的那部分状态」</b>：内容没变时，认领者按内容继承；卡住的原因同理
     * （只在新条目自己没写原因、且状态仍是卡住时继承）。
     * <p>
     * <b>认领还是黏的</b>：上一份里已被认领的条目，其状态不会被一次重写改回「未开始」——
     * 那等于把一件正在做的事重新放回池子里。模型照样可以把它标成完成（那是明确的意图）。
     *
     * @param previous 上一份列表，不可为 {@code null}
     * @param incoming 新列表，不可为 {@code null}
     * @return 继承后的新列表，保证非 {@code null}
     */
    private static List<TodoItem> inheritClaims(List<TodoItem> previous, List<TodoItem> incoming) {
        if (previous.isEmpty() || incoming.isEmpty()) {
            return incoming;
        }
        List<TodoItem> merged = new ArrayList<TodoItem>(incoming.size());
        for (TodoItem item : incoming) {
            TodoItem sticky = findSticky(previous, item.content());
            if (sticky == null) {
                merged.add(item);
                continue;
            }
            boolean keepInProgress = sticky.status() == TodoStatus.IN_PROGRESS
                    && item.status() == TodoStatus.PENDING;
            String reason = item.reason() == null && item.status() == TodoStatus.BLOCKED
                    ? sticky.reason() : item.reason();
            merged.add(item.with(keepInProgress ? TodoStatus.IN_PROGRESS : item.status(),
                    sticky.owner(), reason));
        }
        return merged;
    }

    /**
     * 在上一份列表里找同内容、且带着「模型看不见的状态」的那一条。
     * <p>
     * 判据是「有认领者或有卡住原因」——两者都是别人写下的、而整表覆盖的模型看不到的东西。
     * 只判认领者是不够的：父回合自己卡住一条（那时没有认领者）之后，原因照样会被下一次重写丢掉。
     *
     * @param previous 上一份列表，不可为 {@code null}
     * @param content  待办内容，不可为 {@code null}
     * @return 那一条；没有时返回 {@code null}
     */
    private static TodoItem findSticky(List<TodoItem> previous, String content) {
        for (TodoItem item : previous) {
            boolean sticky = item.owner() != null || item.reason() != null;
            if (sticky && item.content().equals(content)) {
                return item;
            }
        }
        return null;
    }

    /**
     * 写入一份列表并同步缓存。
     * <p>
     * 空列表删文件而不是留一个 {@code []}：读路径本来就把「文件不存在」当空，
     * 而一个目录里堆满空文件只会让「这个会话有没有待办」变难判断。
     *
     * @param key   已校验的会话标识
     * @param items 待写列表，不可为 {@code null}
     * @throws JellyfishException 落盘失败时抛出
     */
    private void persist(String key, List<TodoItem> items) {
        List<TodoItem> copy = immutableCopy(items);
        Path file = fileOf(key);
        if (copy.isEmpty()) {
            deleteIfExists(file);
        } else {
            writeAtomically(file, TodoJson.write(copy));
        }
        cache.put(key, copy);
    }

    /**
     * 删除某会话的待办文件并清掉缓存。
     * <p>
     * 内核删除会话时经 {@code SessionDeleteRequest} 调到这里：待办按 {@code sessionId} 归属，
     * 会话没了它的待办文件也就没有意义，留着只会变成孤儿文件。
     * <p>
     * 幂等：文件不存在（该会话从没写过待办）时返回 {@code false}，不算失败。
     *
     * @param sessionId 会话标识，不可为空白且需可安全作为文件名
     * @return 确实删掉了返回 {@code true}
     * @throws JellyfishException 会话标识非法或删除失败时抛出
     */
    synchronized boolean delete(String sessionId) {
        String key = requireSafeFileName(sessionId);
        cache.remove(key);
        return deleteIfExists(fileOf(key));
    }

    /**
     * 取某会话待办文件路径。
     *
     * @param sessionId 会话标识
     * @return 文件路径
     */
    Path fileOf(String sessionId) {
        return directory.resolve(sessionId + SUFFIX);
    }

    /**
     * 从文件加载待办。
     *
     * @param sessionId 已校验的会话标识
     * @return 不可修改的待办列表
     * @throws JellyfishException 读取或解析失败时抛出
     */
    private List<TodoItem> load(String sessionId) {
        Path file = fileOf(sessionId);
        if (!Files.isRegularFile(file)) {
            // 从没写过待办：没有文件不是错误
            return Collections.emptyList();
        }
        String json;
        try {
            json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JellyfishException("读取待办文件失败: " + file + " (" + e.getMessage() + ')', e);
        }
        return immutableCopy(TodoJson.read(json, file.toString()));
    }

    /**
     * 删除文件，不存在时静默跳过。
     *
     * @param file 目标文件
     * @return 确实删掉了返回 {@code true}；文件不存在返回 {@code false}
     * @throws JellyfishException 删除失败时抛出
     */
    private static boolean deleteIfExists(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new JellyfishException("删除待办文件失败: " + file + " (" + e.getMessage() + ')', e);
        }
    }

    /**
     * 原子写入：先写临时文件，再替换目标文件。
     *
     * @param file 目标文件
     * @param json 内容
     * @throws JellyfishException 写入失败时抛出
     */
    private void writeAtomically(Path file, String json) {
        Path temp = file.resolveSibling(file.getFileName() + TEMP_SUFFIX);
        try {
            Files.createDirectories(directory);
            Files.write(temp, json.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // 少数文件系统不支持原子替换，退化到普通替换：至少目标路径上不会留下半截文件
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new JellyfishException("待办落盘失败: " + file + " (" + e.getMessage() + ')', e);
        }
    }

    /**
     * 复制成不可修改列表并拒绝 {@code null} 元素。
     *
     * @param items 原始列表，不可为 {@code null}
     * @return 不可修改副本，保证非 {@code null}
     * @throws JellyfishException 存在 {@code null} 元素时抛出
     */
    private static List<TodoItem> immutableCopy(List<TodoItem> items) {
        if (items.isEmpty()) {
            return Collections.emptyList();
        }
        List<TodoItem> copy = new ArrayList<TodoItem>(items.size());
        for (TodoItem item : items) {
            if (item == null) {
                throw new JellyfishException("todo item must not be null");
            }
            copy.add(item);
        }
        return Collections.unmodifiableList(copy);
    }

    /**
     * 校验会话标识可以安全地当作文件名。
     * <p>
     * 会话标识会直接拼进文件路径。不校验的话，一个写着 {@code ../../x} 的标识就能把文件写到目录之外。
     *
     * @param sessionId 会话标识
     * @return 校验通过的标识
     * @throws JellyfishException 为空白、含路径分隔符或含上跳片段时抛出
     */
    private static String requireSafeFileName(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new JellyfishException("会话标识不能为空");
        }
        if (sessionId.indexOf('/') >= 0 || sessionId.indexOf('\\') >= 0 || sessionId.contains("..")) {
            throw new JellyfishException("会话标识不能作为文件名: " + sessionId);
        }
        return sessionId;
    }
}
