'use strict';

const fs = require('fs');

/**
 * 网关与 worker 两侧共用的分帧约定（Node 版）。
 *
 * 帧格式与 Python 版、与宿主侧完全一致：**一行一个 UTF-8 JSON 对象**。
 * 用行分隔而不是长度前缀，是因为它允许「错一行、丢一行、继续」；
 * 长度前缀会让任何一次误打印把后续所有帧错位，而恢复无从下手。
 *
 * 两侧的实现必须逐字节等价（`JSON.stringify` 默认不留空格，与 Python 侧的
 * `separators=(",", ":")` 相同），否则会出现「宿主能发、worker 读不出」——
 * 这类问题的现场表现是「脚本毫无反应」，既看不到异常也看不到错误码。
 */

/**
 * 单帧上限：**脚本侧的出帧上限**。超限说明脚本返回了不该返回的大对象，早失败好过把内存吃满。
 *
 * 宿主侧按行读协议流，它给「一行」留的缓冲必须**严格大于**这个数
 * （宿主侧是 `ScriptProtocol.MAX_LINE_BYTES`）——本注释此前的写法是「与宿主侧的传输上限一致」，
 * 而那时宿主侧其实只给到 64 KiB：落在两者之间的结果会被宿主**就地切成两条非法行**丢掉，
 * 现场表现是「调用一直等到超时」，而本文件上面那条「结果超过传输上限」的错误码永远不会触发。
 * 这是两个语言里的两个数字，谁都不该在对方不知情的情况下改：Node 与 Python 的端到端测试
 * 各有一条守卫用例，从本文件读这个数去和宿主侧比大小。
 */
const MAX_FRAME_BYTES = 10 * 1024 * 1024;

/** 逐条告警的上限：一个循环打印的脚本不该把两侧日志刷满；超过之后只报一次总数。 */
const DROPPED_ALERT_LIMIT = 5;

/**
 * 把一个对象编码成一帧字节（含结尾换行）。
 *
 * @param {*} message 待编码的对象
 * @returns {Buffer} 帧字节
 */
function encode(message) {
    return Buffer.from(JSON.stringify(message) + '\n', 'utf8');
}

/**
 * 把新到的字节并入缓冲区并切出完整的帧。
 *
 * 无法解析的行（脚本误打印、运行时警告）直接丢弃：它不该让一次调用失败，
 * 也不该被计入任何失败账目。但**丢弃必须留痕**：既计数（回给调用方），
 * 也往 stderr 写一条（两侧的 stderr 都会进宿主日志），并对逐条告警限流。
 *
 * @param {Buffer} buffer 剩余缓冲
 * @param {Buffer} chunk 新到的字节
 * @returns {{buffer: Buffer, frames: Array, dropped: number}} 剩余缓冲、切出的帧与丢弃行数
 */
function feed(buffer, chunk) {
    let pending = Buffer.concat(buffer === undefined || buffer === null ? [] : [buffer, chunk]);
    const frames = [];
    let dropped = 0;
    for (;;) {
        const index = pending.indexOf(0x0a);
        if (index < 0) {
            break;
        }
        const line = pending.subarray(0, index);
        pending = pending.subarray(index + 1);
        if (line.toString('utf8').trim() === '') {
            continue;
        }
        try {
            frames.push(JSON.parse(line.toString('utf8')));
        } catch (ignored) {
            // 非 JSON 行：宽容丢弃、不失败，但必须留痕。
            // 一条被丢掉的行如果正好是某个请求的应答，现场就是「请求悬到超时」——
            // 那时两侧日志里一条线索都没有，排查只能靠猜
            dropped++;
            if (dropped <= DROPPED_ALERT_LIMIT) {
                process.stderr.write('[script_wire] 丢弃无法解析的协议行: '
                    + JSON.stringify(line.toString('utf8').slice(0, 200)) + '\n');
            }
        }
    }
    if (dropped > DROPPED_ALERT_LIMIT) {
        process.stderr.write('[script_wire] 本次共丢弃 ' + dropped + ' 行无法解析的协议行（前 '
            + DROPPED_ALERT_LIMIT + ' 行已逐条记录）\n');
    }
    return { buffer: pending, frames, dropped };
}

/**
 * 把一段字节**写到底**（`fs.writeSync` 只是**一次** `write(2)`）。
 *
 * 这是本文件里唯一需要文件描述符的地方，而它属于分帧约定——同步写的两个坑都在这里：
 *
 * 1. **短写**：写到管道时，超过 `PIPE_BUF` 的写入允许部分完成，返回值被忽略的话一帧就在中间断掉。
 *    而**半帧比丢帧更糟**：它与下一帧粘在一起时，对端报的是「JSON 解析失败」而不是「写不下」。
 * 2. **EAGAIN**：子进程的 stdio 管道在 Node 里是**非阻塞**的（libuv 建的，实测确认），
 *    管道一满（macOS 上是 64 KiB）`write` 直接返回「写不下，你等会儿再来」。
 *    同步写必须自己把这个等待做掉，否则任何大于管道容量的帧都会**在写完之前**被丢掉——
 *    现场是「结果超过 64 KB 的调用一直等到超时」，而脚本侧那条「结果超过传输上限」永远不会触发
 *    （它压根没觉得自己超限）。这条是实测出来的：40 KB 过、70 KB 必丢，边界正好是管道容量。
 *
 * 等待用 `Atomics.wait` 做同步睡眠：调用方（worker 内部）是完全同步的，没有事件循环可以等。
 * 只有 EAGAIN/EINTR 才重试，别的错误一律抛出——写不进去是「对端不读了」，不该被吞掉。
 *
 * @param {number} fd 文件描述符
 * @param {Buffer} data 整帧字节
 */
function writeAll(fd, data) {
    let offset = 0;
    while (offset < data.length) {
        try {
            const written = fs.writeSync(fd, data, offset, data.length - offset);
            if (written <= 0) {
                throw new Error(`写描述符 ${fd} 没有进展（已写 ${offset}/${data.length} 字节）`);
            }
            offset += written;
        } catch (error) {
            if (error.code !== 'EAGAIN' && error.code !== 'EINTR') {
                throw error;
            }
            sleepSync(PIPE_RETRY_MS);
        }
    }
}

/** 管道满时的重试间隔（毫秒）。对端在排空，等一小会儿再来。 */
const PIPE_RETRY_MS = 2;

/** `Atomics.wait` 需要的共享缓冲（同步睡眠唯一可移植的做法）。 */
const SLEEP_BUFFER = new Int32Array(new SharedArrayBuffer(4));

/**
 * 同步睡一小会儿（这里没有事件循环可等）。
 *
 * @param {number} millis 毫秒
 */
function sleepSync(millis) {
    Atomics.wait(SLEEP_BUFFER, 0, 0, millis);
}

/**
 * 把新到的字节并入缓冲区并按行切分文本（用于把子进程 stderr 转进日志）。
 *
 * 与 {@link feed} 的区别只在用途：日志是给人看的，因此不要求是 JSON，
 * 但**必须能把多字节字符完整还原**——脚本里的中文日志恰恰是最需要看清的部分。
 * 半个多字节字符会留在缓冲里等下一个 chunk，而不是变成替换字符。
 *
 * @param {Buffer} buffer 剩余缓冲
 * @param {Buffer} chunk 新到的字节
 * @returns {{buffer: Buffer, lines: string[]}} 剩余缓冲与切出的文本行
 */
function takeLines(buffer, chunk) {
    let pending = Buffer.concat(buffer === undefined || buffer === null ? [] : [buffer, chunk]);
    const lines = [];
    for (;;) {
        const index = pending.indexOf(0x0a);
        if (index < 0) {
            break;
        }
        let line = pending.subarray(0, index);
        pending = pending.subarray(index + 1);
        if (line.length > 0 && line[line.length - 1] === 0x0d) {
            line = line.subarray(0, line.length - 1);
        }
        lines.push(decodeLossy(line));
    }
    return { buffer: pending, lines };
}

/**
 * 把一行字节解成文本，非法字节换成替换字符。
 *
 * 这里刻意不依赖 `Buffer.toString('utf8')` 对「半个字符」的隐式处理：
 * 行内出现的非法字节应当就地可见（替换字符），而不是吃掉后面的内容。
 *
 * @param {Buffer} line 行字节
 * @returns {string} 文本
 */
function decodeLossy(line) {
    return line.toString('utf8');
}

module.exports = {
    MAX_FRAME_BYTES, DROPPED_ALERT_LIMIT, encode, feed, takeLines, writeAll,
};
